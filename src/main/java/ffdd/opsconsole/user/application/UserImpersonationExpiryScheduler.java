package ffdd.opsconsole.user.application;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.domain.UserImpersonationSessionView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class UserImpersonationExpiryScheduler {
    private static final String EXPIRE_REASON = "TTL_EXPIRED";
    private final UserOpsRepository userRepository;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;

    @Scheduled(fixedDelayString = "${nexion.user.impersonation-expiry-delay-ms:30000}")
    @Transactional(rollbackFor = Exception.class)
    public void expireDueSessions() {
        for (UserImpersonationSessionView session : userRepository.expiredActiveImpersonations(100)) {
            if (!userRepository.expireActiveImpersonation(session.sessionNo(), EXPIRE_REASON, "system")) continue;
            LocalDateTime sessionEnd = LocalDateTime.now();
            LocalDateTime sessionStart = session.createdAt() == null
                    ? sessionEnd.minusMinutes(session.ttlMinutes())
                    : session.createdAt();
            long durationSec = Math.max(0L, Duration.between(sessionStart, sessionEnd).getSeconds());
            Map<String, Object> detail = Map.of(
                    "endType", "EXPIRED",
                    "reason", EXPIRE_REASON,
                    "ttlMinutes", session.ttlMinutes(),
                    "durationSec", durationSec);
            auditLogService.recordRequired(AuditLogWriteRequest.builder()
                    .action("C2_USER_IMPERSONATION_TERMINATED")
                    .resourceType("USER_IMPERSONATION")
                    .resourceId(session.sessionNo())
                    .bizNo(session.sessionNo())
                    .userId(session.userId())
                    .actorType("SYSTEM")
                    .actorUsername("system")
                    .result("SUCCESS")
                    .riskLevel("HIGH")
                    .detail(detail)
                    .build());
            outboxService.publish("USER_IMPERSONATION", session.sessionNo(), "admin.user_impersonation_ended", Map.of(
                    "userId", session.userId(),
                    "targetUserId", session.userId(),
                    "operator", "system",
                    "reason", EXPIRE_REASON,
                    "ttlMinutes", session.ttlMinutes(),
                    "sessionStart", sessionStart.toString(),
                    "sessionEnd", sessionEnd.toString(),
                    "durationSec", durationSec,
                    "endType", "EXPIRED",
                    "occurredAt", sessionEnd.toString()));
        }
    }
}
