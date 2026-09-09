package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.DayOneSnapshotBinding;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper.MissionDefinition;
import ffdd.opsconsole.growth.mapper.H3DayOnePageObservationReceiptMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Server-owned intake for the three Day One page facts. The app only proves a
 * matching authenticated render; mission eligibility, bindings and completion
 * remain owned by the existing H3 outbox projection.
 */
@Service
public class H3DayOnePageObservationService {
    private final DayOneInstanceMapper dayOneInstanceMapper;
    private final QuestCompletionFactMapper completionFactMapper;
    private final H3DayOnePageObservationReceiptMapper receiptMapper;
    private final EventOutboxService outboxService;
    private final Clock clock;

    public H3DayOnePageObservationService(
            DayOneInstanceMapper dayOneInstanceMapper,
            QuestCompletionFactMapper completionFactMapper,
            H3DayOnePageObservationReceiptMapper receiptMapper,
            EventOutboxService outboxService,
            Clock clock) {
        this.dayOneInstanceMapper = dayOneInstanceMapper;
        this.completionFactMapper = completionFactMapper;
        this.receiptMapper = receiptMapper;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> observe(Long userId, String surface) {
        H3DayOnePageObservationContract.Rule rule = H3DayOnePageObservationContract.forSurface(surface);
        if (rule == null) return ApiResult.fail(422, "H3_DAY_ONE_PAGE_OBSERVATION_INVALID");
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");

        if (completionFactMapper.lockActiveUser(userId) == null) {
            return ignored();
        }
        LocalDateTime observedAt = LocalDateTime.now(clock);
        DayOneSnapshotBinding binding = matchingSnapshotBinding(userId, rule, observedAt);
        if (binding == null) return ignored();
        // This verifies the frozen item and window under lock. Current PC
        // mission/binding rows are deliberately never consulted here.
        MissionDefinition mission = completionFactMapper.lockDayOneSnapshotMissionAt(
                userId, binding.sourceMissionId(), rule.questCode(), binding.instanceKey(), observedAt);
        if (mission == null || isTerminal(completionFactMapper.lockUserMissionStatus(
                userId, mission.missionId(), mission.instanceKey()))) {
            return ignored();
        }

        Map<String, Object> attribution = completionFactMapper.attribution(userId);
        Integer accountAgeMonths = nonNegativeInteger(attribution == null ? null : attribution.get("accountAgeMonths"));
        String cohort = attribution == null ? "" : String.valueOf(attribution.get("cohort")).trim();
        if (accountAgeMonths == null || !cohort.matches("^\\d{4}-W\\d{2}$")) {
            return ApiResult.fail(503, "H3_DAY_ONE_PAGE_OBSERVATION_ATTRIBUTION_UNAVAILABLE");
        }
        // The outbox generates a random event id. This receipt is the durable
        // source idempotency boundary before the scheduler can project it.
        if (receiptMapper.insertIfAbsent(userId, mission.instanceKey(), rule.surface()) != 1) {
            return ignored();
        }
        outboxService.publishUserEvent(
                "H3_DAY_ONE_PAGE", userId + ":" + mission.instanceKey() + ":" + rule.surface(),
                rule.eventType(), userId, normalizePhase(attribution.get("phase")), accountAgeMonths, cohort,
                Map.of("surface", rule.surface(), "questCode", rule.questCode(), "instanceKey", mission.instanceKey()));
        return ApiResult.ok(Map.of("accepted", true, "recorded", true));
    }

    private DayOneSnapshotBinding matchingSnapshotBinding(
            Long userId, H3DayOnePageObservationContract.Rule rule, LocalDateTime observedAt) {
        List<DayOneSnapshotBinding> bindings = dayOneInstanceMapper.listInWindowSnapshotBindings(
                List.of(userId), rule.eventType(), observedAt);
        if (bindings == null) return null;
        return bindings.stream().filter(binding -> binding != null && userId.equals(binding.userId())
                && rule.questCode().equals(binding.questCode())
                && H3DayOnePageObservationContract.matches(
                        binding.producer(), binding.eventType(), binding.questCode(), binding.userIdField()))
                .findFirst().orElse(null);
    }

    private boolean isTerminal(String status) {
        if (!StringUtils.hasText(status)) return false;
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "COMPLETED", "CLAIMABLE", "CLAIMED" -> true;
            default -> false;
        };
    }

    private Integer nonNegativeInteger(Object value) {
        try {
            int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            return parsed >= 0 ? parsed : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String normalizePhase(Object raw) {
        String phase = raw == null ? "P1" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (phase.matches("[1-6]")) phase = "P" + phase;
        return phase.matches("P[1-6]") ? phase : "P1";
    }

    private ApiResult<Map<String, Object>> ignored() {
        return ApiResult.ok(Map.of("accepted", true, "recorded", false));
    }

}
