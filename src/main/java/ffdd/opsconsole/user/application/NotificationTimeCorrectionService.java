package ffdd.opsconsole.user.application;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.user.application.NotificationTimeEvidenceService.FactView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.mapper.NotificationTimeCorrectionMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationTimeCorrectionService {
    private final UserOpsRepository users;
    private final AdminOperatorRoleResolver roles;
    private final NotificationTimeEvidenceService evidence;
    private final NotificationTimeCorrectionMapper mapper;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;

    // The dedicated executor commits or rolls back update, required audit and SUCCEEDED receipt together.
    // Failure recording occurs only after that transaction has released its locks.
    public CorrectionView correct(String userKey, Long notificationId, String key, CorrectionRequest request) {
        if (!Set.of("SUPER_ADMIN", "RISK", "AUDITOR", "GROWTH").contains(String.valueOf(roles.resolveCode()))) {
            throw new BizException(403, "PERMISSION_DENIED");
        }
        String actor = AdminActorResolver.resolve(null);
        if (actor == null) throw new BizException(403, "PERMISSION_DENIED");
        if (userKey == null || userKey.isBlank() || notificationId == null || notificationId <= 0
                || request == null || request.expectedCreatedAt() == null || request.expectedCreatedAt().getNano() != 0
                || request.facts() == null || request.facts().size() != 3 || request.facts().stream().anyMatch(f -> f == null)
                || request.reason() == null || request.reason().isBlank() || request.reason().trim().length() > 500) {
            throw new BizException(400, "NOTIFICATION_TIME_CORRECTION_INVALID");
        }
        long userId = users.findUserIdByLookupKey(userKey.trim()).orElseThrow(() -> new BizException(404, "NOTIFICATION_NOT_FOUND"));
        String reason = request.reason().trim();
        String scope = "NOTIFICATION_TIME:" + hash(actor).substring(0, 32) + ":" + notificationId;
        String requestHash = hash(actor + "\n" + userId + "\n" + notificationId + "\n"
                + request.expectedCreatedAt() + "\n" + request.facts() + "\n" + reason);
        return idempotency.executeRepeatableRead(scope, key, requestHash, CorrectionView.class, () -> {
            var row = mapper.lockNotification(userId, notificationId);
            if (row == null) throw new BizException(404, "NOTIFICATION_NOT_FOUND");
            if (!request.expectedCreatedAt().equals(row.createdAt())) throw conflict();
            var checked = evidence.evaluate(row, userId, id -> {
                var deliveries = new ArrayList<>(mapper.lockDelivery("NOVA_NOTIFICATION", id, "nova.push_sent"));
                deliveries.addAll(mapper.lockDelivery("NOTIFICATION", id, "notification.delivered"));
                return deliveries;
            }, mapper::lockRegistration, mapper::lockReceipts).getData();
            if (checked == null || !"MATCHED".equals(checked.status()) || !request.facts().equals(checked.facts())) {
                throw new BizException(409, "NOTIFICATION_TIME_EVIDENCE_CHANGED");
            }
            LocalDateTime corrected = checked.deliveryFactTime().truncatedTo(ChronoUnit.SECONDS);
            if (corrected.equals(row.createdAt())) throw new BizException(409, "NOTIFICATION_TIME_ALREADY_CORRECT");
            if (mapper.correctCreatedAt(row, corrected) != 1) throw conflict();
            audit.recordRequired(AuditLogWriteRequest.builder()
                    .action("USER_NOTIFICATION_TIME_CORRECTED").resourceType("NOTIFICATION")
                    .resourceId(String.valueOf(notificationId)).userId(userId).actorUsername(actor)
                    .actorType("ADMIN").result("SUCCESS").riskLevel("HIGH")
                    .detail(Map.of("reason", reason, "idempotencyKey", key.trim(),
                            "previousCreatedAt", row.createdAt().toString(), "correctedCreatedAt", corrected.toString(),
                            "timeZone", "Asia/Shanghai", "facts", checked.facts(),
                            "basis", "DISPLAY_TIME_FROM_AUTHORITATIVE_DELIVERY"))
                    .build());
            return new CorrectionView(notificationId, "CORRECTED", row.createdAt(), corrected, "Asia/Shanghai");
        });
    }

    private static BizException conflict() { return new BizException(409, "NOTIFICATION_TIME_SNAPSHOT_CHANGED"); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public record CorrectionRequest(LocalDateTime expectedCreatedAt, List<FactView> facts, String reason) {}
    public record CorrectionView(Long notificationId, String status, LocalDateTime previousCreatedAt,
                                 LocalDateTime correctedCreatedAt, String timeZone) {}
}
