package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.content.domain.SupportLeaderboard.Board;
import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper.Evidence;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper.Point;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper.Witness;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * B80 v2 publication cache. Its names/lock are deliberately separate from the analytics v1
 * and leaderboard v1 stores, so old readers and rollback paths never observe a new generation.
 */
@Service
public class SupportPerformanceCache {
    static final String SNAPSHOT_KEY = "uvel:support:performance:v6:snapshot";
    static final String LOCK_KEY = "uvel:support:performance:v6:lock";
    static final String FAILED_KEY = "uvel:support:performance:v6:refresh-failed";
    private static final String CUTOFF_KEY = "uvel:support:performance:v6:cutoff";
    private static final String DAY_PREFIX = "uvel:support:performance:v6:day:";
    static final Duration LOCK_LEASE = Duration.ofMinutes(2);
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        local cutoff = redis.call('GET', KEYS[5])
        if cutoff and tonumber(cutoff) >= tonumber(ARGV[3]) then return 0 end
        redis.call('SET', KEYS[2], ARGV[2])
        if ARGV[4] == '1' then redis.call('SET', KEYS[4], ARGV[2], 'EX', 259200) end
        if ARGV[5] == '1' then redis.call('SET', KEYS[6], ARGV[2], 'EX', 259200) end
        if ARGV[6] == '1' then redis.call('SET', KEYS[7], ARGV[2], 'EX', 259200) end
        redis.call('SET', KEYS[5], ARGV[3])
        local failure = redis.call('GET', KEYS[3])
        if not failure or tonumber(failure) <= tonumber(ARGV[3]) then redis.call('DEL', KEYS[3]) end
        redis.call('DEL', KEYS[1])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        return redis.call('DEL', KEYS[1])
        """, Long.class);
    private static final DefaultRedisScript<Long> MARK_FAILED = new DefaultRedisScript<>("""
        local cutoff = redis.call('GET', KEYS[1])
        if cutoff and tonumber(cutoff) >= tonumber(ARGV[1]) then return 0 end
        local failure = redis.call('GET', KEYS[2])
        if failure and tonumber(failure) >= tonumber(ARGV[1]) then return 0 end
        redis.call('SET', KEYS[2], ARGV[1])
        return 1
        """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AtomicReference<Decoded> decoded = new AtomicReference<>();
    private record Decoded(String payload, DaySnapshot publication) { }

    public SupportPerformanceCache(StringRedisTemplate redis) { this.redis = redis; }

    record RankEvidence(List<SupportLeaderboardMapper.Account> accounts,
                        List<SupportLeaderboardMapper.QualificationInterval> qualifications,
                        List<SupportLeaderboardMapper.MemberInterval> memberships,
                        List<SupportLeaderboardMapper.Group> groups,
                        List<SupportLeaderboardMapper.Binding> bindings, LocalDateTime cutover) {
        static RankEvidence capture(SupportLeaderboardMapper mapper) {
            return new RankEvidence(mapper.accounts(), mapper.qualifications(), mapper.memberships(),
                    mapper.groups(), mapper.currentBindings(), mapper.qualificationCutoverUtc());
        }
    }
    record DaySnapshot(SupportPerformance.Snapshot snapshot, RankEvidence evidence) { }

    DaySnapshot priorDay(LocalDate day, Board board) {
        try {
            String prior = redis.opsForValue().get(DAY_PREFIX + day.minusDays(1) + ":" + board.name());
            return prior == null ? null : json.readValue(prior, DaySnapshot.class);
        } catch (java.io.IOException | DataAccessException unavailable) {
            return null;
        }
    }

    java.util.function.Function<List<Point>, Map<String, List<Evidence>>> historyReader(
            SupportPerformanceHistoryMapper mapper, Instant asOf) {
        var calendar = BusinessCalendar.legacy();
        var openMonth = calendar.month(asOf);
        var closedEnd = LocalDateTime.ofInstant(calendar.startOf(openMonth.atDay(1)), ZoneOffset.UTC);
        return new java.util.function.Function<>() {
            private List<Witness> witnesses;
            private final Map<YearMonth, String> revisions = new HashMap<>();

            @Override
            public Map<String, List<Evidence>> apply(List<Point> points) {
                var byMonth = points.stream().collect(java.util.stream.Collectors.groupingBy(
                        point -> calendar.month(point.atUtc().toInstant(ZoneOffset.UTC)), java.util.TreeMap::new,
                        java.util.stream.Collectors.toList()));
                var result = new HashMap<String, List<Evidence>>();
                byMonth.forEach((month, batch) -> {
                    if (!month.isBefore(openMonth)) {
                        readEvidence(mapper, batch, result);
                        return;
                    }
                    if (witnesses == null) witnesses = mapper.checkpointWitnesses(closedEnd);
                    var end = LocalDateTime.ofInstant(calendar.startOf(month.plusMonths(1).atDay(1)), ZoneOffset.UTC);
                    var revision = revisions.computeIfAbsent(month, unused -> SupportPerformance.digest(witnesses.stream()
                            .filter(row -> row.startsAt().isBefore(end))
                            .toList().toString()));
                    var ordered = batch.stream().sorted(Comparator.comparing(Point::eventId)
                            .thenComparingLong(Point::customerId).thenComparing(Point::atUtc)).toList();
                    readMonthEvidence(mapper, ordered, result, month, revision);
                });
                return result;
            }
        };
    }

    private void readMonthEvidence(SupportPerformanceHistoryMapper mapper, List<Point> points,
            Map<String, List<Evidence>> target, YearMonth month, String revision) {
        String key = "uvel:support:performance:v6:history:" + month + ":"
                + SupportPerformance.digest(revision + "|" + points);
        try {
            String stored = redis.opsForValue().get(key);
            if (stored != null) {
                target.putAll(json.readValue(stored, new TypeReference<Map<String, List<Evidence>>>() { }));
                return;
            }
        } catch (java.io.IOException | DataAccessException unavailable) {
            // The SQL evidence remains authoritative when the checkpoint is unavailable.
            readEvidence(mapper, points, target);
            return;
        }
        var monthEvidence = new HashMap<String, List<Evidence>>();
        readEvidence(mapper, points, monthEvidence);
        try {
            redis.opsForValue().set(key, json.writeValueAsString(monthEvidence), Duration.ofDays(30));
        } catch (java.io.IOException | DataAccessException unavailable) {
            // The complete current read is still usable; the next run may recreate the checkpoint.
        }
        target.putAll(monthEvidence);
    }

    private static void readEvidence(SupportPerformanceHistoryMapper mapper, List<Point> points,
            Map<String, List<Evidence>> result) {
        for (int offset = 0; offset < points.size(); offset += 200) {
            mapper.atEvents(points.subList(offset, Math.min(offset + 200, points.size()))).forEach(row ->
                    result.computeIfAbsent(row.eventId(), unused -> new ArrayList<>()).add(row));
        }
    }

    /** Each bounded, closed-month subtotal is independently recoverable after a restart. */
    List<SupportPerformance.MonthlySubtotal> monthlySubtotals(SupportPerformance.Month month,
            List<SupportPerformance.Event> events) {
        if (!month.to().equals(month.calendarEnd())) {
            return SupportPerformanceAggregation.byMonth(List.of(month), events);
        }
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (var event : events) {
                hash.update(json.writeValueAsBytes(event));
                hash.update((byte) '\n');
            }
            String key = "uvel:support:performance:v6:month:" + month.month() + ":"
                    + HexFormat.of().formatHex(hash.digest());
            try {
                String stored = redis.opsForValue().get(key);
                if (stored != null) return json.readValue(stored, new TypeReference<>() { });
            } catch (java.io.IOException | DataAccessException unavailable) {
                // Rebuild the derived totals from this capture.
            }
            var totals = SupportPerformanceAggregation.byMonth(List.of(month), events);
            try {
                redis.opsForValue().set(key, json.writeValueAsString(totals), Duration.ofDays(30));
            } catch (java.io.IOException | DataAccessException unavailable) {
                // Derived totals are still valid for the current publication.
            }
            return totals;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("SUPPORT_PERFORMANCE_GENERATION_INVALID", failure);
        }
    }

    public Optional<SupportPerformance.Snapshot> current() {
        return currentPublication().map(DaySnapshot::snapshot);
    }

    Optional<DaySnapshot> currentPublication() {
        final String payload;
        try { payload = redis.opsForValue().get(SNAPSHOT_KEY); }
        catch (DataAccessException failure) { throw unavailable(); }
        if (payload == null) return Optional.empty();
        Decoded previous = decoded.get();
        if (previous != null && previous.payload().equals(payload)) return Optional.of(previous.publication());
        try {
            var publication = json.readValue(payload, DaySnapshot.class);
            if (publication.snapshot().asOf().isAfter(Instant.now().plusSeconds(5))) throw invalid();
            decoded.set(new Decoded(payload, publication));
            return Optional.of(publication);
        } catch (java.io.IOException | RuntimeException failure) {
            throw new BizException(503, "SUPPORT_PERFORMANCE_CACHE_INVALID");
        }
    }

    /** A v2 publication is one complete generation; its key is never shared with v1. */
    boolean publish(SupportPerformance.Snapshot snapshot, RankEvidence evidence) {
        if (snapshot == null) throw new IllegalArgumentException("SUPPORT_PERFORMANCE_GENERATION_REQUIRED");
        String token = UUID.randomUUID().toString();
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_LEASE))) return false;
        } catch (DataAccessException failure) { throw unavailable(); }
        try {
            snapshot = snapshot.published(Instant.now());
            var publication = new DaySnapshot(snapshot, evidence);
            String payload = json.writeValueAsString(publication);
            var calendar = BusinessCalendar.legacy();
            var sampleDay = calendar.dateAt(snapshot.asOf());
            String day = DAY_PREFIX + sampleDay;
            var month = snapshot.months().isEmpty() ? null : snapshot.months().get(snapshot.months().size() - 1);
            boolean sameDay = month != null && calendar.dateAt(snapshot.publishedAt()).equals(sampleDay);
            boolean published = Long.valueOf(1).equals(redis.execute(PUBLISH,
                    List.of(LOCK_KEY, SNAPSHOT_KEY, FAILED_KEY, day + ":customers", CUTOFF_KEY,
                            day + ":netContribution", day + ":firstPayment"), token, payload, cutoffMicros(snapshot.asOf()),
                    sameDay ? "1" : "0", sameDay && month.coverage() == SupportPerformance.Coverage.COMPLETE ? "1" : "0",
                    sameDay && month.firstHistoryComplete() ? "1" : "0"));
            if (published) decoded.set(new Decoded(payload, publication));
            return published;
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("SUPPORT_PERFORMANCE_GENERATION_INVALID", failure);
        } catch (DataAccessException failure) {
            throw unavailable();
        } finally {
            try { redis.execute(RELEASE, List.of(LOCK_KEY), token); }
            catch (DataAccessException ignored) { /* lease will expire */ }
        }
    }

    public boolean refreshFailed() {
        try { return Boolean.TRUE.equals(redis.hasKey(FAILED_KEY)); }
        catch (DataAccessException failure) { throw unavailable(); }
    }

    public boolean stale(SupportPerformance.Snapshot snapshot) {
        return !Instant.now().isBefore(snapshot.asOf().plus(Duration.ofMinutes(2)));
    }

    void markRefreshFailed(Instant asOf) {
        try { redis.execute(MARK_FAILED, List.of(CUTOFF_KEY, FAILED_KEY), cutoffMicros(asOf)); }
        catch (DataAccessException unavailable) { /* A reader cannot establish freshness while Redis is unavailable. */ }
    }

    private static String cutoffMicros(Instant asOf) {
        return Long.toString(asOf.getEpochSecond() * 1_000_000 + asOf.getNano() / 1_000);
    }

    private static BizException unavailable() {
        return new BizException(503, "SUPPORT_PERFORMANCE_CACHE_UNAVAILABLE");
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("SUPPORT_PERFORMANCE_CACHE_INVALID");
    }
}
