package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.content.mapper.SupportLeaderboardPublicationMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardPublicationMapper.Stored;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal committed snapshots only. HTTP must project Row/Page, never Snapshot/Publication. */
@Service
public class SupportLeaderboardPublicationService {
    private final SupportLeaderboardPublicationMapper mapper;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public SupportLeaderboardPublicationService(SupportLeaderboardPublicationMapper mapper) { this.mapper = mapper; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public Publication publish(Snapshot snapshot) {
        validate(snapshot);
        Context c = snapshot.context(); String stream = streamKey(c); String payload = encode(snapshot);
        mapper.ensurePointer(stream);
        Long previousId = mapper.lockPointer(stream);
        Stored replay = mapper.byVersionForUpdate(stream, snapshot.viewVersion());
        if (replay != null) {
            Publication existing = decode(replay);
            if (!existing.snapshot().equals(snapshot)) throw unavailable();
            return existing;
        }
        if (previousId != null) {
            Publication previous = decode(mapper.byId(previousId));
            if (!stream.equals(streamKey(previous.snapshot().context()))) throw unavailable();
            if (!c.evaluatedAt().isAfter(previous.snapshot().context().evaluatedAt()))
                throw new BizException(409,"SUPPORT_LEADERBOARD_LATE_EVALUATION");
        }
        Map<String,Object> values = identity(c);
        values.put("streamKey",stream); values.put("definitionVersion",c.definitionVersion());
        values.put("sourceVersion",snapshot.sourceVersion()); values.put("viewVersion",snapshot.viewVersion());
        values.put("comparisonKey",snapshot.comparisonKey()); values.put("evaluatedAt",utc(c.evaluatedAt()));
        values.put("state",snapshot.state().name()); values.put("payload",payload); values.put("payloadHash",hash(payload));
        if (mapper.insert(values) != 1) throw unavailable();
        Publication inserted = decode(mapper.byVersionForUpdate(stream,snapshot.viewVersion()));
        if (!inserted.snapshot().equals(snapshot) || mapper.advance(stream,previousId,inserted.publicationId()) != 1)
            throw unavailable();
        return inserted;
    }

    /** Caller owns one repeatable-read boundary for source/authorization and publication reads. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Publication latest(Context current, String expectedVersion) {
        requireReadBoundary();
        if (expectedVersion != null && !SupportLeaderboard.validVersion(expectedVersion))
            throw new BizException(422,"SUPPORT_LEADERBOARD_INVALID_QUERY");
        Stored stored = mapper.latest(streamKey(current));
        if (stored == null) throw new BizException(503,"SUPPORT_LEADERBOARD_PUBLICATION_UNAVAILABLE");
        if (!streamKey(current).equals(stored.streamKey())) throw unavailable();
        Publication publication = decode(stored);
        if (!sameViewIdentity(current,publication.snapshot().context())
                || expectedVersion != null && !expectedVersion.equals(publication.snapshot().viewVersion()))
            throw new BizException(409,"SUPPORT_LEADERBOARD_VERSION_CHANGED");
        return publication;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Publication> previousBusinessDay(Context current) {
        requireReadBoundary();
        LocalDate today = current.evaluatedAt().atZone(SupportLeaderboard.BUSINESS_ZONE).toLocalDate();
        Instant from = today.minusDays(1).atStartOfDay(SupportLeaderboard.BUSINESS_ZONE).toInstant();
        Instant to = today.atStartOfDay(SupportLeaderboard.BUSINESS_ZONE).toInstant();
        Map<String,Object> query = identity(current); query.put("from",utc(from)); query.put("to",utc(to));
        List<Publication> result = new ArrayList<>();
        for (Stored row : mapper.previousDay(query)) {
            Publication p = decode(row); Context c = p.snapshot().context();
            if (p.snapshot().state() != State.COMPLETE || p.publishedAt().isBefore(from) || !p.publishedAt().isBefore(to)
                    || c.board() != current.board() || !Objects.equals(c.rankMonth(),current.rankMonth())
                    || !Objects.equals(c.rankCurrency(),current.rankCurrency()) || c.scope() != current.scope()
                    || !c.approvedGroupIds().equals(current.approvedGroupIds())) throw unavailable();
            result.add(p);
        }
        result.sort(Comparator.comparing(Publication::publishedAt).thenComparingLong(Publication::publicationId));
        return List.copyOf(result);
    }

    private Publication decode(Stored row) {
        try {
            if (row == null || !hash(row.payload()).equals(row.payloadHash())) throw unavailable();
            Snapshot snapshot = json.readValue(row.payload(),Snapshot.class); validate(snapshot);
            Context c = snapshot.context();
            if (!streamKey(c).equals(row.streamKey()) || !c.board().name().equals(row.board())
                    || !Objects.equals(text(c.rankMonth()),row.rankMonth()) || !text(c.referenceMonth()).equals(row.referenceMonth())
                    || !c.currency().equals(row.currency()) || !Objects.equals(c.rankCurrency(),row.rankCurrency())
                    || !c.scope().name().equals(row.scope()) || !groups(c).equals(row.groupsKey())
                    || !c.definitionVersion().equals(row.definitionVersion()) || !snapshot.sourceVersion().equals(row.sourceVersion())
                    || !snapshot.viewVersion().equals(row.viewVersion()) || !snapshot.comparisonKey().equals(row.comparisonKey())
                    || !utc(c.evaluatedAt()).equals(row.evaluatedAt()) || !snapshot.state().name().equals(row.state())) throw unavailable();
            return new Publication(row.id(),row.publishedAt().toInstant(ZoneOffset.UTC),snapshot);
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException | BizException ex) {
            throw unavailable();
        }
    }

    private void validate(Snapshot snapshot) {
        if (snapshot == null || snapshot.context() == null || snapshot.context().evaluatedAt().getNano() % 1000 != 0
                || !SupportLeaderboard.validVersion(snapshot.viewVersion()) || snapshot.comparisonKey() == null
                || snapshot.comparisonKey().length() != 64) throw unavailable();
        List<Candidate> candidates = snapshot.rows().stream().map(r -> new Candidate(r.agentId(),r.name(),r.avatarUrl(),
            r.groupName(),r.qualification(),r.firstPayment(),r.customers(),r.amount(),snapshot.qualificationBirths().get(r.agentId()))).toList();
        Snapshot base = SupportLeaderboard.calculate(snapshot.context(),snapshot.sourceVersion(),snapshot.candidateCoverage(),candidates);
        if (snapshot.rows().isEmpty() && snapshot.candidateCoverage() != Coverage.COMPLETE
                || snapshot.state() != base.state() || !snapshot.comparisonKey().equals(base.comparisonKey())
                || !snapshot.qualificationBirths().equals(base.qualificationBirths()) || snapshot.rows().size() != base.rows().size()) throw unavailable();
        for (int i=0; i<base.rows().size(); i++) {
            Row actual = snapshot.rows().get(i), expected = base.rows().get(i);
            if (actual.agentId() != expected.agentId() || !Objects.equals(actual.rank(),expected.rank())
                    || actual.isTied() != expected.isTied() || actual.rankMetricCoverage() != expected.rankMetricCoverage()
                    || actual.movement() == null || actual.movement().kind() == null || actual.movement().reason() == null)
                throw unavailable();
            Movement m = actual.movement();
            if (m.kind() == MovementKind.UNAVAILABLE) {
                if (m.places() != null || m.previousRank() != null || m.baselineAt() != null
                        || m.baselineVersion() != null || m.reason() == Reason.NONE) throw unavailable();
                continue;
            }
            if (snapshot.state() != State.COMPLETE || actual.rank() == null || actual.rank() <= 0
                    || m.reason() != Reason.NONE || m.baselineAt() == null
                    || !m.baselineAt().isBefore(snapshot.context().evaluatedAt())
                    || !SupportLeaderboard.validVersion(m.baselineVersion())) throw unavailable();
            if (m.kind() == MovementKind.NEW) {
                Instant birth = snapshot.qualificationBirths().get(actual.agentId());
                if (snapshot.context().scope() != Scope.all || m.places() != null || m.previousRank() != null
                        || birth == null || !birth.isAfter(m.baselineAt())
                        || birth.isAfter(snapshot.context().evaluatedAt())) throw unavailable();
            } else {
                if (m.previousRank() == null || m.previousRank() <= 0 || m.places() == null) throw unavailable();
                int delta = m.previousRank() - actual.rank();
                MovementKind direction = delta > 0 ? MovementKind.UP : delta < 0 ? MovementKind.DOWN : MovementKind.SAME;
                if (m.kind() != direction || m.places() != Math.abs(delta)) throw unavailable();
            }
        }
        if (!SupportLeaderboard.matchesSnapshotVersion(snapshot)) throw unavailable();
    }
    private String encode(Snapshot value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw unavailable(); }
    }
    private static void requireReadBoundary() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),Connection.TRANSACTION_REPEATABLE_READ))
            throw new IllegalStateException("Leaderboard publication reads require an active REPEATABLE_READ transaction");
    }
    private static boolean sameViewIdentity(Context a, Context b) {
        return streamKey(a).equals(streamKey(b)) && a.definitionVersion().equals(b.definitionVersion());
    }
    public static String streamKey(Context c) {
        return hash(String.join("|","slb-stream-v1",c.board().name(),Objects.toString(c.rankMonth(),""),
            c.referenceMonth().toString(),c.currency(),c.scope().name(),groups(c)));
    }
    private static String groups(Context c) { return String.join(",",c.approvedGroupIds().stream().sorted().map(Object::toString).toList()); }
    private static String text(Object value) { return value == null ? null : value.toString(); }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
    private static Map<String,Object> identity(Context c) {
        Map<String,Object> values = new HashMap<>(); values.put("board",c.board().name()); values.put("rankMonth",text(c.rankMonth()));
        values.put("referenceMonth",text(c.referenceMonth())); values.put("currency",c.currency()); values.put("rankCurrency",c.rankCurrency());
        values.put("scope",c.scope().name()); values.put("groupsKey",groups(c)); return values;
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static BizException unavailable() { return new BizException(503,"SUPPORT_LEADERBOARD_PUBLICATION_UNAVAILABLE"); }
}
