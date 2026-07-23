package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper.CompletionFact;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper.MissionDefinition;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Internal H3 fact boundary. There is intentionally no user-facing controller:
 * only trusted server producers can invoke this bean after proving the business fact.
 */
@Service
@RequiredArgsConstructor
public class QuestCompletionFactConsumer {
    private static final Set<String> TRUSTED_PRODUCERS = Set.of(
            "ORDER", "REFERRAL", "LEARNING", "DEVICE", "COMMISSION", "SYSTEM");

    private final QuestCompletionFactMapper mapper;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;

    @Transactional(rollbackFor = Exception.class)
    public CompletionResult consume(QuestCompletionCommand command) {
        if (command == null) throw new BizException(422, "QUEST_COMPLETION_FACT_REQUIRED");
        String producer = reference(command.producer(), 32, "QUEST_COMPLETION_PRODUCER_INVALID")
                .toUpperCase(Locale.ROOT);
        if (!TRUSTED_PRODUCERS.contains(producer)) {
            throw new BizException(403, "QUEST_COMPLETION_PRODUCER_NOT_TRUSTED");
        }
        String eventId = reference(command.eventId(), 96, "QUEST_COMPLETION_EVENT_ID_INVALID");
        String questCode = reference(command.questCode(), 64, "QUEST_CODE_REQUIRED");
        Long userId = command.userId();
        if (userId == null || userId <= 0 || mapper.lockActiveUser(userId) == null) {
            throw new BizException(404, "USER_NOT_FOUND_OR_INACTIVE");
        }
        MissionDefinition mission = mapper.lockMission(questCode);
        if (mission == null) throw new BizException(409, "QUEST_NOT_CONFIGURED");

        String payloadHash = sha256(producer + "|" + eventId + "|" + userId + "|"
                + mission.missionId() + "|" + questCode);
        if (mapper.insertFact(producer, eventId, payloadHash, userId, mission.missionId(), questCode) == 0) {
            CompletionFact existing = mapper.lockFact(producer, eventId);
            if (existing == null || !payloadHash.equals(existing.payloadHash())) {
                throw new BizException(409, "QUEST_COMPLETION_FACT_CONFLICT");
            }
            return new CompletionResult(questCode, "COMPLETED", true);
        }
        String currentStatus = mapper.lockUserMissionStatus(userId, mission.missionId());
        if (currentStatus != null && Set.of("COMPLETED", "CLAIMABLE", "CLAIMED")
                .contains(currentStatus.trim().toUpperCase(Locale.ROOT))) {
            return new CompletionResult(questCode, currentStatus.trim().toUpperCase(Locale.ROOT), true);
        }
        if (mapper.markMissionCompleted(userId, mission.missionId()) < 1) {
            throw new BizException(409, "QUEST_COMPLETION_STATE_CONFLICT");
        }

        Map<String, Object> detail = linked(
                "questId", questCode, "layer", mission.layer(),
                "producer", producer, "sourceEventId", eventId);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("H3_QUEST_COMPLETED").resourceType("USER_MISSION")
                .resourceId(questCode).bizNo(producer + ":" + eventId)
                .userId(userId).actorId(userId).actorType("SYSTEM")
                .actorUsername("system:" + producer).result("SUCCESS").riskLevel("MEDIUM")
                .detail(detail).build());
        Map<String, Object> attribution = mapper.attribution(userId);
        if (attribution == null || attribution.get("accountAgeMonths") == null
                || !StringUtils.hasText(String.valueOf(attribution.get("cohort")))) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        outboxService.publishUserEvent(
                "MISSION", questCode, "quest.completed", userId,
                normalizePhase(attribution.get("phase")),
                Integer.parseInt(String.valueOf(attribution.get("accountAgeMonths"))),
                String.valueOf(attribution.get("cohort")), detail);
        return new CompletionResult(questCode, "COMPLETED", false);
    }

    private String normalizePhase(Object raw) {
        String phase = raw == null ? "P1" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (phase.matches("[1-6]")) phase = "P" + phase;
        return phase.matches("P[1-6]") ? phase : "P1";
    }

    private String reference(String value, int maxLength, String error) {
        if (!StringUtils.hasText(value) || value.length() > maxLength
                || !value.matches("^[A-Za-z0-9._:-]+$")) {
            throw new BizException(422, error);
        }
        return value.trim();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    public record QuestCompletionCommand(String producer, String eventId, Long userId, String questCode) {
    }

    public record CompletionResult(String questCode, String status, boolean replay) {
    }
}
