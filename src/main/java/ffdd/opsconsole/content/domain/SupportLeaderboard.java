package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.exception.BizException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;

/** Pure projection of a full, server-authorized aggregate set; never reads or creates financial facts. */
public final class SupportLeaderboard {
    private SupportLeaderboard() { }
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    public enum Board { firstPayment, deposit, purchase, customers }
    public enum Scope { all, ownGroup, managedGroups }
    public enum Coverage { COMPLETE, PARTIAL, UNKNOWN, FAILED }
    public enum State { COMPLETE, PROVISIONAL }
    public enum Qualification { ACTIVE, DISABLED, REMOVED, HANDOVER_REQUIRED, UNKNOWN }
    public enum AmountKind { DEPOSIT, PURCHASE }
    public enum Reason { NONE, SOURCE_INCOMPLETE, HISTORY_UNKNOWN, ATTRIBUTION_UNKNOWN,
        REFUNDS_UNKNOWN, SOURCE_FAILED, CANDIDATES_INCOMPLETE, METRIC_INCOMPLETE,
        HISTORICAL_MONTH, MONTH_START, NO_BASELINE, DEFINITION_CHANGED, SCOPE_CHANGED,
        MEMBERS_CHANGED, NOT_A_CANDIDATE, LEADER }
    public enum MovementKind { UP, DOWN, SAME, NEW, UNAVAILABLE }

    public record Count(Long value, Coverage coverage, Reason reason) {
        public Count { validateMetric(value == null ? null : BigDecimal.valueOf(value), coverage, reason); }
    }
    public record Amount(BigDecimal value, String currency, YearMonth month, AmountKind kind,
                         Coverage coverage, Reason reason, String sourceVersion) {
        public Amount {
            validateMetric(value, coverage, reason);
            if (currency == null || !currency.matches("[A-Z][A-Z0-9]{1,15}") || month == null || kind == null)
                throw invalid();
            requireToken(sourceVersion);
        }
    }
    /** qualificationBirth is a proven first-ever SERVICE qualification, never account creation or a regrant. */
    public record Candidate(long agentId, String name, String avatarUrl, String groupName,
                            Qualification qualification, Count firstPayment, Count customers,
                            Amount amount, Instant qualificationBirth) {
        public Candidate {
            if (agentId <= 0 || name == null || name.isBlank() || groupName == null || qualification == null
                    || firstPayment == null || customers == null || amount == null) throw invalid();
            // Public avatars are served through this resource; no storage key, arbitrary URL or private avatar route.
            String path = "/api/admin/content/support-workbench/leaderboard/" + agentId + "/avatar";
            if (avatarUrl != null && !avatarUrl.equals(path) && !avatarUrl.startsWith(path + "?")) throw invalid();
        }
    }
    /** Scope/groups come from current server authorization. Current labels are deliberately outside comparison identity. */
    public record Context(Board board, YearMonth rankMonth, YearMonth referenceMonth, String currency,
                          Scope scope, Set<Long> approvedGroupIds, String definitionVersion, Instant evaluatedAt) {
        public Context {
            Objects.requireNonNull(board); Objects.requireNonNull(scope); Objects.requireNonNull(evaluatedAt);
            requireToken(definitionVersion);
            approvedGroupIds = Set.copyOf(approvedGroupIds);
            YearMonth current = YearMonth.from(evaluatedAt.atZone(BUSINESS_ZONE));
            if (currency == null || !currency.matches("[A-Z][A-Z0-9]{1,15}") || referenceMonth == null
                    || (board == Board.customers ? rankMonth != null || !referenceMonth.equals(current)
                        : rankMonth == null || rankMonth.isAfter(current) || !referenceMonth.equals(rankMonth))
                    || (scope == Scope.all ? !approvedGroupIds.isEmpty() : approvedGroupIds.isEmpty())
                    || scope == Scope.ownGroup && approvedGroupIds.size() != 1
                    || approvedGroupIds.stream().anyMatch(id -> id <= 0)) throw invalid();
        }
        public String rankCurrency() { return board == Board.deposit || board == Board.purchase ? currency : null; }
    }
    public record Movement(MovementKind kind, Integer places, Integer previousRank, Instant baselineAt,
                           String baselineVersion, Reason reason) { }
    /** Public whitelist: no customer, order, contact, storage or evidence identifier. */
    public record Row(long agentId, String name, String avatarUrl, String groupName, Qualification qualification,
                      Integer rank, boolean isTied, Count firstPayment, Count customers, Amount amount,
                      Coverage rankMetricCoverage, Movement movement) { }
    public record Snapshot(Context context, String sourceVersion, Coverage candidateCoverage,
                           State state, String comparisonKey, String viewVersion, List<Row> rows,
                           Map<Long, Instant> qualificationBirths) {
        public Snapshot {
            rows = List.copyOf(rows); qualificationBirths = Map.copyOf(qualificationBirths);
        }
    }
    /** Only the persistence reader constructs these from immutable, successfully committed publications. */
    public record Publication(long publicationId, Instant publishedAt, Snapshot snapshot) {
        public Publication {
            if (publicationId <= 0 || publishedAt == null || snapshot == null
                    || publishedAt.isBefore(snapshot.context().evaluatedAt())) throw invalid();
        }
    }
    public record Self(Row row, BigDecimal gap, Reason reason, Integer pageNum) { }
    public record Page(String viewVersion, State state, Coverage candidateCoverage, int total, int ranked,
                       int unranked, int matched, long pageNum, int pageSize, List<Row> rows, Self self) {
        public Page { rows = List.copyOf(rows); }
    }

    public static Snapshot calculate(Context context, String sourceVersion, Coverage candidateCoverage,
                                     List<Candidate> fullCandidates) {
        Objects.requireNonNull(context); Objects.requireNonNull(candidateCoverage); requireToken(sourceVersion);
        if (candidateCoverage == Coverage.FAILED) throw new BizException(503,"SUPPORT_LEADERBOARD_SOURCE_FAILED");
        List<Candidate> candidates = new ArrayList<>(fullCandidates);
        Set<Long> ids = new HashSet<>(); Map<Long, Instant> births = new HashMap<>();
        boolean complete = candidateCoverage == Coverage.COMPLETE;
        boolean allMetricsFailed = !candidates.isEmpty();
        for (Candidate c : candidates) {
            if (!ids.add(c.agentId())) throw invalid();
            if (!c.amount().currency().equals(context.currency()) || !c.amount().month().equals(context.referenceMonth())
                    || c.amount().kind() != (context.board() == Board.purchase ? AmountKind.PURCHASE : AmountKind.DEPOSIT))
                throw invalid();
            if (c.qualificationBirth() != null) {
                if (c.qualificationBirth().isAfter(context.evaluatedAt())) throw invalid();
                births.put(c.agentId(), c.qualificationBirth());
            }
            complete &= metricCoverage(c, context.board()) == Coverage.COMPLETE;
            allMetricsFailed &= metricCoverage(c, context.board()) == Coverage.FAILED;
        }
        if (allMetricsFailed) throw new BizException(503,"SUPPORT_LEADERBOARD_SOURCE_FAILED");
        State state = complete ? State.COMPLETE : State.PROVISIONAL;
        candidates.sort((a,b) -> {
            BigDecimal x = metric(a, context.board()), y = metric(b, context.board());
            int amount = x == null ? (y == null ? 0 : 1) : y == null ? -1 : y.compareTo(x);
            return amount != 0 ? amount : Long.compare(a.agentId(), b.agentId());
        });
        List<Row> rows = new ArrayList<>();
        BigDecimal previous = null; int rank = 0;
        Map<BigDecimal, Integer> frequencies = new TreeMap<>();
        if (complete) for (Candidate c : candidates) frequencies.merge(metric(c, context.board()), 1, Integer::sum);
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i); BigDecimal value = metric(c, context.board());
            if (complete && (previous == null || previous.compareTo(value) != 0)) rank = i + 1;
            rows.add(new Row(c.agentId(), c.name(), c.avatarUrl(), c.groupName(), c.qualification(),
                complete ? rank : null, complete && frequencies.get(value) > 1, c.firstPayment(), c.customers(), c.amount(),
                metricCoverage(c, context.board()), unavailable(complete ? Reason.NO_BASELINE
                    : candidateCoverage != Coverage.COMPLETE ? Reason.CANDIDATES_INCOMPLETE : Reason.METRIC_INCOMPLETE)));
            previous = value;
        }
        String comparison = hash(comparisonParts(context, ids));
        return new Snapshot(context, sourceVersion, candidateCoverage, state, comparison,
            viewVersion(context,sourceVersion,candidateCoverage,rows,births), rows, births);
    }

    /** Select the previous business day's last committed COMPLETE version; never synthesize or recalculate yesterday. */
    public static Snapshot withMovement(Snapshot current, List<Publication> persistedPublications) {
        Context c = current.context(); LocalDate today = c.evaluatedAt().atZone(BUSINESS_ZONE).toLocalDate();
        Reason blocked = current.state() != State.COMPLETE ? Reason.METRIC_INCOMPLETE
            : c.rankMonth() != null && !c.rankMonth().equals(YearMonth.from(today)) ? Reason.HISTORICAL_MONTH
            : c.rankMonth() != null && today.getDayOfMonth() == 1 ? Reason.MONTH_START : null;
        List<Publication> previousDay = persistedPublications.stream()
            .filter(p -> p.snapshot().state() == State.COMPLETE
                && p.publishedAt().atZone(BUSINESS_ZONE).toLocalDate().equals(today.minusDays(1))
                && sameBoardPeriodCurrency(c, p.snapshot().context()))
            .toList();
        Publication baseline = previousDay.stream().filter(p -> sameScope(c, p.snapshot().context()))
            .max(Comparator.comparing(Publication::publishedAt).thenComparingLong(Publication::publicationId)).orElse(null);
        if (blocked == null && baseline == null) blocked = previousDay.isEmpty() ? Reason.NO_BASELINE : Reason.SCOPE_CHANGED;
        if (blocked == null && !c.definitionVersion().equals(baseline.snapshot().context().definitionVersion()))
            blocked = Reason.DEFINITION_CHANGED;
        Map<Long, Row> old = new HashMap<>();
        if (baseline != null) baseline.snapshot().rows().forEach(r -> old.put(r.agentId(), r));
        Set<Long> currentIds = new HashSet<>(); current.rows().forEach(r -> currentIds.add(r.agentId()));
        if (blocked == null && !currentIds.equals(old.keySet())) {
            boolean provedNew = c.scope() == Scope.all && currentIds.containsAll(old.keySet())
                && currentIds.stream().filter(id -> !old.containsKey(id)).allMatch(id -> {
                    Instant birth = current.qualificationBirths().get(id);
                    return birth != null && birth.isAfter(baseline.snapshot().context().evaluatedAt()) && !birth.isAfter(c.evaluatedAt());
                });
            if (!provedNew) blocked = Reason.MEMBERS_CHANGED;
        }
        final Reason reason = blocked; final Publication b = baseline;
        List<Row> rows = current.rows().stream().map(r -> {
            Movement movement;
            if (reason != null) movement = unavailable(reason);
            else if (!old.containsKey(r.agentId())) movement = new Movement(MovementKind.NEW, null, null, b.snapshot().context().evaluatedAt(), b.snapshot().viewVersion(), Reason.NONE);
            else {
                int previousRank = old.get(r.agentId()).rank(), delta = previousRank - r.rank();
                movement = new Movement(delta > 0 ? MovementKind.UP : delta < 0 ? MovementKind.DOWN : MovementKind.SAME,
                    Math.abs(delta), previousRank, b.snapshot().context().evaluatedAt(), b.snapshot().viewVersion(), Reason.NONE);
            }
            return withMovement(r, movement);
        }).toList();
        return new Snapshot(c, current.sourceVersion(), current.candidateCoverage(), current.state(), current.comparisonKey(),
            viewVersion(c,current.sourceVersion(),current.candidateCoverage(),rows,current.qualificationBirths()), rows, current.qualificationBirths());
    }

    public static Page page(Snapshot snapshot, String keyword, long pageNum, int pageSize, String expectedVersion, long actorId) {
        if (pageNum < 1 || pageNum > 9007199254740991L || pageSize < 1 || pageSize > 100
                || pageNum > Long.MAX_VALUE / pageSize || keyword != null && keyword.length() > 200) throw invalid();
        requireExpected(snapshot, expectedVersion, pageNum > 1);
        String search = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        List<Row> matches = snapshot.rows().stream().filter(r -> search.isEmpty()
            || r.name().toLowerCase(Locale.ROOT).contains(search) || Long.toString(r.agentId()).equals(search)).toList();
        long offset = (pageNum - 1) * pageSize;
        List<Row> records = offset >= matches.size() ? List.of() : matches.subList((int)offset, (int)Math.min(offset + pageSize, matches.size()));
        Row mine = snapshot.rows().stream().filter(r -> r.agentId() == actorId).findFirst().orElse(null);
        Self self;
        if (mine == null) self = new Self(null, null, Reason.NOT_A_CANDIDATE, null);
        else {
            int myPage = snapshot.rows().indexOf(mine) / pageSize + 1;
            BigDecimal gap = null; Reason why = snapshot.state() != State.COMPLETE ? Reason.METRIC_INCOMPLETE : Reason.LEADER;
            if (snapshot.state() == State.COMPLETE) {
                BigDecimal value = metric(mine, snapshot.context().board());
                for (Row r : snapshot.rows()) {
                    BigDecimal better = metric(r, snapshot.context().board());
                    if (better.compareTo(value) > 0) { gap = better.subtract(value); why = Reason.NONE; }
                    else break;
                }
            }
            self = new Self(mine, gap, why, myPage);
        }
        int ranked = snapshot.state() == State.COMPLETE ? snapshot.rows().size() : 0;
        return new Page(snapshot.viewVersion(), snapshot.state(), snapshot.candidateCoverage(), snapshot.rows().size(), ranked,
            snapshot.rows().size() - ranked, matches.size(), pageNum, pageSize, records, self);
    }

    public static Row publicSummary(Snapshot snapshot, long agentId, String expectedVersion) {
        requireExpected(snapshot, expectedVersion, true);
        return snapshot.rows().stream().filter(r -> r.agentId() == agentId).findFirst()
            .orElseThrow(() -> new BizException(404, "SUPPORT_LEADERBOARD_NOT_FOUND"));
    }
    public static boolean validVersion(String value) { return value != null && value.matches("slb-v1:[0-9a-f]{64}"); }
    /** Persistence validates the same digest that calculation and movement enrichment produce. */
    public static boolean matchesSnapshotVersion(Snapshot snapshot) {
        return snapshot != null && validVersion(snapshot.viewVersion())
            && snapshot.viewVersion().equals(viewVersion(snapshot.context(),snapshot.sourceVersion(),
                snapshot.candidateCoverage(),snapshot.rows(),snapshot.qualificationBirths()));
    }
    private static void requireExpected(Snapshot snapshot, String expectedVersion, boolean required) {
        if (required && expectedVersion == null) throw new BizException(422, "SUPPORT_LEADERBOARD_VERSION_REQUIRED");
        if (expectedVersion != null && !validVersion(expectedVersion)) throw invalid();
        if (expectedVersion != null && !expectedVersion.equals(snapshot.viewVersion()))
            throw new BizException(409, "SUPPORT_LEADERBOARD_VERSION_CHANGED");
    }
    private static Coverage metricCoverage(Candidate c, Board b) { return b == Board.firstPayment ? c.firstPayment().coverage() : b == Board.customers ? c.customers().coverage() : c.amount().coverage(); }
    private static BigDecimal metric(Candidate c, Board b) { return b == Board.firstPayment ? decimal(c.firstPayment()) : b == Board.customers ? decimal(c.customers()) : c.amount().value(); }
    private static BigDecimal metric(Row r, Board b) { return b == Board.firstPayment ? decimal(r.firstPayment()) : b == Board.customers ? decimal(r.customers()) : r.amount().value(); }
    private static BigDecimal decimal(Count c) { return c.value() == null ? null : BigDecimal.valueOf(c.value()); }
    private static void validateMetric(BigDecimal value, Coverage coverage, Reason reason) {
        if (coverage == null || reason == null || value != null && value.signum() < 0
                || coverage == Coverage.COMPLETE && (value == null || reason != Reason.NONE)
                || coverage != Coverage.COMPLETE && reason == Reason.NONE
                || (coverage == Coverage.UNKNOWN || coverage == Coverage.FAILED) && value != null) throw invalid();
    }
    private static void requireToken(String token) { if (token == null || token.isBlank() || token.length() > 512) throw invalid(); }
    private static Movement unavailable(Reason reason) { return new Movement(MovementKind.UNAVAILABLE, null, null, null, null, reason); }
    private static Row withMovement(Row r, Movement m) { return new Row(r.agentId(),r.name(),r.avatarUrl(),r.groupName(),r.qualification(),r.rank(),r.isTied(),r.firstPayment(),r.customers(),r.amount(),r.rankMetricCoverage(),m); }
    private static boolean sameBoardPeriodCurrency(Context a, Context b) { return a.board() == b.board() && Objects.equals(a.rankMonth(),b.rankMonth()) && Objects.equals(a.rankCurrency(),b.rankCurrency()); }
    private static boolean sameScope(Context a, Context b) { return a.scope() == b.scope() && a.approvedGroupIds().equals(b.approvedGroupIds()); }
    private static List<String> comparisonParts(Context c, Set<Long> ids) {
        List<String> parts = new ArrayList<>(Arrays.asList("slb-comparison-v1",c.board().name(),Objects.toString(c.rankMonth(),null),c.rankCurrency(),c.definitionVersion(),c.scope().name()));
        parts.add("groups"); c.approvedGroupIds().stream().sorted().forEach(id -> parts.add(id.toString()));
        parts.add("members"); ids.stream().sorted().forEach(id -> parts.add(id.toString())); return parts;
    }
    private static void appendRow(List<String> parts, Row r) {
        Collections.addAll(parts, Long.toString(r.agentId()),r.name(),r.avatarUrl(),r.groupName(),r.qualification().name(),
            Objects.toString(r.rank(),null), Boolean.toString(r.isTied()),r.firstPayment().toString(),r.customers().toString(),
            r.amount().value() == null ? null : r.amount().value().stripTrailingZeros().toPlainString(),r.amount().currency(),
            r.amount().month().toString(),r.amount().kind().name(),r.amount().coverage().name(),r.amount().reason().name(),r.amount().sourceVersion(),
            r.movement().kind().name(),Objects.toString(r.movement().places(),null),Objects.toString(r.movement().previousRank(),null),
            Objects.toString(r.movement().baselineAt(),null),r.movement().baselineVersion(),r.movement().reason().name());
    }
    private static String viewVersion(Context c,String sourceVersion,Coverage candidateCoverage,List<Row> rows,Map<Long,Instant> births) {
        Set<Long> ids=new HashSet<>();rows.forEach(r -> ids.add(r.agentId()));
        List<String> view=new ArrayList<>(comparisonParts(c,ids));
        Collections.addAll(view,sourceVersion,c.referenceMonth().toString(),c.currency(),c.evaluatedAt().toString(),candidateCoverage.name());
        rows.forEach(r -> appendRow(view,r));
        births.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {view.add(e.getKey().toString());view.add(e.getValue().toString());});
        return "slb-v1:"+hash(view);
    }
    private static String hash(List<String> parts) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            for (String part : parts) {if (part == null) out.writeInt(-1); else {byte[] text=part.getBytes(StandardCharsets.UTF_8);out.writeInt(text.length);out.write(text);}}
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static BizException invalid() { return new BizException(422, "SUPPORT_LEADERBOARD_QUERY_INVALID"); }
}
