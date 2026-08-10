package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.mapper.F5CommissionMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommissionEventUnlockProcessor {
    private final F5CommissionMapper mapper;
    private final EventOutboxService eventOutboxService;
    private final AuditLogService auditLogService;

    @Transactional(rollbackFor = Exception.class)
    public boolean unlock(Map<String, Object> row) {
        Long eventId = asLong(row.get("id"));
        Long userId = asLong(row.get("userId"));
        Long expectedVersion = asLong(row.get("version"));
        if (eventId == null || userId == null || expectedVersion == null || expectedVersion < 0) {
            return false;
        }
        if (mapper.unlockCoolingEventCas(eventId, expectedVersion) != 1) {
            return false;
        }
        if (mapper.insertAutoUnlockOperation(eventId, expectedVersion) != 1) {
            throw new BizException(409, "F5_AUTO_UNLOCK_OPERATION_CONFLICT");
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("F5_COMMISSION_AUTO_UNLOCKED")
                .resourceType("COMMISSION_EVENT")
                .resourceId(String.valueOf(eventId))
                .actorUsername("system")
                .riskLevel("HIGH")
                .detail(Map.of("eventId", eventId, "userId", userId,
                        "versionBefore", expectedVersion, "versionAfter", expectedVersion + 1,
                        "reason", "cooling period elapsed"))
                .build());
        eventOutboxService.publish(
                "COMMISSION",
                String.valueOf(eventId),
                "COMMISSION_UNLOCKED",
                Map.of("user_id", userId, "commission_event_id", eventId));
        return true;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

}
