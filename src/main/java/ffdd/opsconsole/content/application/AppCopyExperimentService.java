package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.AppCopyDeliveryView;
import ffdd.opsconsole.content.domain.AppExperimentConversionView;
import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository;
import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository.Assignment;
import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository.CopyBody;
import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository.RunningExperiment;
import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository.UserAudienceProfile;
import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository.Variant;
import ffdd.opsconsole.content.domain.CopyAudienceTarget;
import ffdd.opsconsole.content.domain.CopyAudiencePhaseProvider;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class AppCopyExperimentService {
    private static final Pattern COPY_KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9._-]{1,95}$");
    private static final Pattern EXPERIMENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern CONVERSION_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:._-]{0,95}$");
    private static final int BUCKET_COUNT = 10_000;

    private final ContentExperimentRuntimeRepository repository;
    private final CopyAudiencePhaseProvider phaseProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final EventOutboxService eventOutboxService;

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<AppCopyDeliveryView> deliver(long userId, String copyKey) {
        if (userId <= 0 || !StringUtils.hasText(copyKey)
                || !COPY_KEY_PATTERN.matcher(copyKey.trim()).matches()) {
            return ApiResult.fail(422, "CONTENT_COPY_REQUEST_INVALID");
        }
        String normalizedCopyKey = copyKey.trim();
        CopyBody published = repository.findPublishedCopy(normalizedCopyKey).orElse(null);
        if (published == null) {
            return ApiResult.fail(404, "CONTENT_COPY_NOT_FOUND");
        }
        RunningExperiment experiment = repository.findRunningExperimentForUpdate(normalizedCopyKey).orElse(null);
        if (experiment == null) {
            return ApiResult.ok(view(published, null));
        }

        Assignment existing = repository.findAssignment(experiment.experimentId(), userId).orElse(null);
        if (existing != null) {
            return assignedView(normalizedCopyKey, existing);
        }

        UserAudienceProfile user = repository.findUserAudienceProfile(userId).orElse(null);
        if (!matchesAudience(user, experiment.audienceSnapshotJson())) {
            return ApiResult.ok(view(published, null));
        }
        List<Variant> variants = validVariants(repository.listVariants(experiment.experimentId()));
        if (variants.isEmpty()) {
            return ApiResult.ok(view(published, null));
        }
        int bucket = stableBucket(experiment.experimentId(), userId);
        Variant selected = select(variants, bucket);
        Assignment computed = new Assignment(
                experiment.experimentId(), userId, selected.name(), selected.copyVersion(), bucket, null);
        repository.insertAssignmentIfAbsent(computed);
        Assignment persisted = repository.findAssignment(experiment.experimentId(), userId).orElse(computed);
        return assignedView(normalizedCopyKey, persisted);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<AppExperimentConversionView> convert(long userId, String experimentId, String conversionKey) {
        if (userId <= 0 || !StringUtils.hasText(experimentId) || !StringUtils.hasText(conversionKey)
                || !EXPERIMENT_ID_PATTERN.matcher(experimentId.trim()).matches()
                || !CONVERSION_KEY_PATTERN.matcher(conversionKey.trim()).matches()) {
            return ApiResult.fail(422, "CONTENT_EXPERIMENT_CONVERSION_INVALID");
        }
        String normalizedExperimentId = experimentId.trim();
        String normalizedConversionKey = conversionKey.trim();
        if (!repository.isEligibleConversionOrder(userId, normalizedConversionKey)) {
            return ApiResult.fail(422, "CONTENT_EXPERIMENT_CONVERSION_ORDER_INVALID");
        }
        if (!repository.isRunningExperiment(normalizedExperimentId)) {
            return ApiResult.fail(409, "CONTENT_EXPERIMENT_NOT_RUNNING");
        }
        Assignment assignment = repository.findAssignment(normalizedExperimentId, userId).orElse(null);
        if (assignment == null) {
            return ApiResult.fail(409, "CONTENT_EXPERIMENT_ASSIGNMENT_REQUIRED");
        }
        boolean counted = repository.insertConversionIfAbsent(
                normalizedExperimentId,
                userId,
                normalizedConversionKey,
                assignment.variant(),
                LocalDateTime.now(clock));
        if (counted) {
            publishConversionEvent(userId, assignment, normalizedConversionKey);
        }
        if (!counted && !repository.isEligibleConversionOrder(userId, normalizedConversionKey)) {
            return ApiResult.fail(422, "CONTENT_EXPERIMENT_CONVERSION_ORDER_INVALID");
        }
        if (!counted && !repository.isRunningExperiment(normalizedExperimentId)) {
            return ApiResult.fail(409, "CONTENT_EXPERIMENT_NOT_RUNNING");
        }
        return ApiResult.ok(new AppExperimentConversionView(
                normalizedExperimentId, normalizedConversionKey, counted));
    }

    private ApiResult<AppCopyDeliveryView> assignedView(String copyKey, Assignment assignment) {
        CopyBody body = repository.findCopyVersion(copyKey, assignment.copyVersion()).orElse(null);
        if (body == null) {
            CopyBody published = repository.findPublishedCopy(copyKey).orElse(null);
            return published == null
                    ? ApiResult.fail(404, "CONTENT_COPY_NOT_FOUND")
                    : ApiResult.ok(view(published, null));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (assignment.exposedAt() == null
                && repository.markExposedIfFirst(assignment.experimentId(), assignment.userId(), now)) {
            publishExposureEvent(copyKey, assignment);
        }
        return ApiResult.ok(view(body, assignment));
    }

    private void publishExposureEvent(String copyKey, Assignment assignment) {
        publishUserEvent(
                assignment.userId(),
                assignment.experimentId(),
                "content.variant_exposed",
                Map.of(
                        "experiment_id", assignment.experimentId(),
                        "variant", assignment.variant(),
                        "copy_key", copyKey,
                        "copy_version", assignment.copyVersion(),
                        "bucket_no", assignment.bucket()));
    }

    private void publishConversionEvent(long userId, Assignment assignment, String conversionKey) {
        publishUserEvent(
                userId,
                assignment.experimentId(),
                "content.variant_converted",
                Map.of(
                        "experiment_id", assignment.experimentId(),
                        "variant", assignment.variant(),
                        "order_id", conversionKey,
                        "paid_or_completed_at", LocalDateTime.now(clock).toString()));
    }

    private void publishUserEvent(
            long userId,
            String experimentId,
            String eventName,
            Map<String, Object> payload) {
        UserAudienceProfile user = repository.findUserAudienceProfile(userId)
                .orElseThrow(() -> new IllegalStateException("CONTENT_EXPERIMENT_USER_NOT_FOUND"));
        String phase = phaseProvider.currentPhase();
        LocalDate registeredDate = user.registeredAt().toLocalDate();
        LocalDate currentDate = LocalDateTime.now(clock).toLocalDate();
        int accountAgeMonths = Math.toIntExact(Math.max(0L, ChronoUnit.MONTHS.between(registeredDate, currentDate)));
        WeekFields iso = WeekFields.ISO;
        String cohort = String.format(
                Locale.ROOT,
                "%d-W%02d",
                registeredDate.get(iso.weekBasedYear()),
                registeredDate.get(iso.weekOfWeekBasedYear()));
        Map<String, Object> eventPayload = new java.util.LinkedHashMap<>(payload);
        eventPayload.put("source", "server_copy_experiment");
        eventPayload.put("locale", normalizedLanguage(user.language()));
        eventOutboxService.publishUserEvent(
                "CONTENT_EXPERIMENT",
                experimentId,
                eventName,
                userId,
                phase,
                accountAgeMonths,
                cohort,
                eventPayload);
    }

    private boolean matchesAudience(UserAudienceProfile user, String audienceJson) {
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.status()) || user.registeredAt() == null
                || !StringUtils.hasText(audienceJson)) {
            return false;
        }
        try {
            CopyAudienceTarget target = objectMapper.readValue(audienceJson, CopyAudienceTarget.class);
            List<String> tiers = target.tiers() == null ? List.of() : target.tiers();
            if (!tiers.isEmpty()) {
                String phase = phaseProvider.currentPhase();
                if (!StringUtils.hasText(phase)
                        || tiers.stream().noneMatch(value -> phase.equalsIgnoreCase(value))) {
                    return false;
                }
            }
            List<String> locales = target.locales() == null ? List.of() : target.locales();
            if (!locales.isEmpty()) {
                String language = normalizedLanguage(user.language());
                if (locales.stream().noneMatch(value -> language.equals(normalizedLanguage(value)))) {
                    return false;
                }
            }
            Integer minDays = target.registrationDaysMin();
            if (minDays != null && user.registeredAt().isAfter(LocalDateTime.now(clock).minusDays(minDays))) {
                return false;
            }
            Integer maxDays = target.registrationDaysMax();
            return maxDays == null || !user.registeredAt().isBefore(LocalDateTime.now(clock).minusDays(maxDays));
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<Variant> validVariants(List<Variant> variants) {
        if (variants == null || variants.size() < 2
                || variants.stream().anyMatch(value -> value == null || !StringUtils.hasText(value.name())
                || !StringUtils.hasText(value.copyVersion()) || value.splitPct() < 1 || value.splitPct() > 99)
                || variants.stream().mapToInt(Variant::splitPct).sum() != 100) {
            return List.of();
        }
        return variants.stream().sorted(Comparator.comparingInt(Variant::sortOrder)).toList();
    }

    private Variant select(List<Variant> variants, int bucket) {
        int cursor = 0;
        for (Variant variant : variants) {
            cursor += variant.splitPct() * 100;
            if (bucket < cursor) {
                return variant;
            }
        }
        return variants.get(variants.size() - 1);
    }

    static int stableBucket(String experimentId, long userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((experimentId + ":" + userId).getBytes(StandardCharsets.UTF_8));
            long prefix = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
            return (int) Long.remainderUnsigned(prefix, BUCKET_COUNT);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String normalizedLanguage(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int separator = normalized.indexOf('-');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }

    private AppCopyDeliveryView view(CopyBody body, Assignment assignment) {
        return new AppCopyDeliveryView(
                body.copyKey(), body.version(), body.zh(), body.en(), body.vi(),
                assignment == null ? null : assignment.experimentId(),
                assignment == null ? null : assignment.variant());
    }
}
