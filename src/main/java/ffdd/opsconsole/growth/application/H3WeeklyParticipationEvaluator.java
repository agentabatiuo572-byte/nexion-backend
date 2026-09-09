package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.H3WeeklyParticipationMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Converts server-observed weekly participation into one threshold fact per account and ISO week.
 * Raw visits and task.completed are deliberately not H3-bindable event types.
 */
@Service
@RequiredArgsConstructor
public class H3WeeklyParticipationEvaluator {
    static final ZoneId WEEK_ZONE = ZoneId.of("Asia/Shanghai");
    static final String STOREFRONT_PRODUCT_DETAIL = "STOREFRONT_PRODUCT_DETAIL";
    static final String GENESIS_SECONDARY_MARKET = "GENESIS_SECONDARY_MARKET";
    static final String VERIFIED_PRODUCTION_COMPUTE_COMPLETION = "VERIFIED_PRODUCTION_COMPUTE_COMPLETION";
    static final String STOREFRONT_EVENT = "H3_STOREFRONT_THREE_PRODUCTS_VIEWED";
    static final String GENESIS_EVENT = "H3_GENESIS_SECONDARY_MARKET_VIEWED";
    static final String COMPUTE_EVENT = "H3_COMPUTE_COMPLETED_50";

    private final H3WeeklyParticipationMapper mapper;
    private final EventOutboxService outbox;
    private final Clock clock;

    @Transactional(rollbackFor = Exception.class)
    public boolean recordStorefrontProductDetail(Long userId, String productNo) {
        return record(userId, STOREFRONT_PRODUCT_DETAIL, requiredSubject(productNo), STOREFRONT_EVENT, 3, clock.instant());
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean recordGenesisSecondaryMarketView(Long userId) {
        return record(userId, GENESIS_SECONDARY_MARKET, "SECONDARY_MARKET", GENESIS_EVENT, 1, clock.instant());
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean recordVerifiedProductionComputeCompletion(Long userId, String taskNo, LocalDateTime completedAt) {
        String normalizedTaskNo = requiredSubject(taskNo);
        if (completedAt == null) throw new IllegalArgumentException("H3_COMPUTE_COMPLETION_NOT_VERIFIED");
        return record(userId, VERIFIED_PRODUCTION_COMPUTE_COMPLETION, normalizedTaskNo, COMPUTE_EVENT, 50,
                completedAt.atZone(WEEK_ZONE).toInstant());
    }

    String currentWeeklyInstance() {
        return weeklyInstance(clock.instant());
    }

    static String weeklyInstance(Instant observedAt) {
        LocalDate date = observedAt.atZone(WEEK_ZONE).toLocalDate();
        return String.format(Locale.ROOT, "WEEK:%d-W%02d",
                date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private boolean record(Long userId, String observationType, String subjectKey, String thresholdEventType, int threshold,
                           Instant observed) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("H3_PARTICIPATION_USER_INVALID");
        if (observed == null) throw new IllegalArgumentException("H3_PARTICIPATION_TIME_INVALID");
        String instanceKey = weeklyInstance(observed);
        LocalDateTime observedAt = LocalDateTime.ofInstant(observed, WEEK_ZONE);
        // The marker row is the per-user/week/type mutex. Its INSERT ... ON
        // DUPLICATE KEY UPDATE takes the write lock before any observation or
        // current read, so concurrent 49th/50th completions serialize.
        mapper.ensureThreshold(userId, instanceKey, thresholdEventType);
        H3WeeklyParticipationMapper.ThresholdRow marker =
                mapper.lockThreshold(userId, instanceKey, thresholdEventType);
        if (marker == null) throw new IllegalStateException("H3_PARTICIPATION_THRESHOLD_MARKER_UNAVAILABLE");
        if (marker.emittedAt() != null) return false;
        if (mapper.insertObservation(userId, instanceKey, observationType, subjectKey, observedAt) != 1) {
            return false;
        }

        java.util.List<Long> observationIds = mapper.lockObservationIds(userId, instanceKey, observationType);
        int achieved = observationIds == null ? 0 : observationIds.size();
        if (achieved < threshold) return true;
        if (mapper.markThresholdEmitted(userId, instanceKey, thresholdEventType, observedAt) != 1) return true;

        Attribution attribution = Attribution.from(mapper.attribution(userId));
        outbox.publishUserEventAt(
                "H3_WEEKLY_PARTICIPATION",
                userId + ":" + instanceKey + ":" + thresholdEventType,
                thresholdEventType,
                userId,
                attribution.phase(),
                attribution.accountAgeMonths(),
                attribution.cohort(), observedAt,
                Map.of(
                        "instanceKey", instanceKey,
                        "sourceOccurredAt", observedAt.toString(),
                        "observationType", observationType,
                        "threshold", threshold,
                        "achieved", achieved));
        return true;
    }

    private static String requiredSubject(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "";
        if (!normalized.matches("[A-Za-z0-9._:-]{1,96}")) {
            throw new IllegalArgumentException("H3_PARTICIPATION_SUBJECT_INVALID");
        }
        return normalized;
    }

    private record Attribution(String phase, int accountAgeMonths, String cohort) {
        private static Attribution from(Map<String, Object> row) {
            String phase = text(row, "phase").toUpperCase(Locale.ROOT);
            String cohort = text(row, "cohort");
            Object rawAge = row == null ? null : row.get("accountAgeMonths");
            int age;
            try {
                age = rawAge instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(rawAge));
            } catch (RuntimeException ex) {
                throw new IllegalStateException("H3_PARTICIPATION_ATTRIBUTION_INVALID", ex);
            }
            if (!phase.matches("P[1-6]") || age < 0 || !cohort.matches("\\d{4}-W\\d{2}")) {
                throw new IllegalStateException("H3_PARTICIPATION_ATTRIBUTION_INVALID");
            }
            return new Attribution(phase, age, cohort);
        }

        private static String text(Map<String, Object> row, String key) {
            Object value = row == null ? null : row.get(key);
            return value == null ? "" : String.valueOf(value).trim();
        }
    }
}
