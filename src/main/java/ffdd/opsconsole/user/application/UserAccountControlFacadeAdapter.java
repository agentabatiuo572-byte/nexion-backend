package ffdd.opsconsole.user.application;

import ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.facade.UserAccountControlFacade;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserAccountControlFacadeAdapter implements UserAccountControlFacade {
    private final UserOpsRepository userRepository;
    private final FinanceWithdrawalControlFacade withdrawalControlFacade;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int freezeActiveUsersByUserNos(List<String> userNos, String reason, String operator, String sourceRef) {
        int updated = 0;
        for (String userNo : new LinkedHashSet<>(userNos == null ? List.of() : userNos)) {
            if (!StringUtils.hasText(userNo)) continue;
            Long userId = userRepository.findUserIdByLookupKey(userNo.trim()).orElse(null);
            UserAccountView account = userId == null ? null : userRepository.findById(userId).orElse(null);
            if (account == null || !"ACTIVE".equalsIgnoreCase(account.status())) continue;
            String normalizedReason = StringUtils.hasText(reason) ? reason.trim() : "K1 cluster freeze";
            String actor = StringUtils.hasText(operator) ? operator.trim() : "system";
            String normalizedSourceRef = StringUtils.hasText(sourceRef) ? sourceRef.trim() : "K1";
            if (!userRepository.freezeUserStatusWithSource(
                    userId, "ACTIVE", normalizedReason, actor, "K1_MULTI_ACCOUNT_CLUSTER", normalizedSourceRef)) continue;
            userRepository.revokeUserSessions(userId, normalizedReason);
            int withdrawalsFrozen = withdrawalControlFacade.freezePendingWithdrawalsForUser(userId, normalizedReason, actor);
            Map<String, Object> detail = Map.of(
                    "source", "K1_MULTI_ACCOUNT_CLUSTER",
                    "sourceRef", normalizedSourceRef,
                    "fromStatus", "ACTIVE",
                    "toStatus", "FROZEN",
                    "withdrawalsFrozen", withdrawalsFrozen,
                    "reason", normalizedReason);
            auditLogService.recordRequired(AuditLogWriteRequest.builder()
                    .action("C2_USER_STATUS_CHANGED_BY_K1")
                    .resourceType("USER")
                    .resourceId(String.valueOf(userId))
                    .bizNo(String.valueOf(userId))
                    .userId(userId)
                    .actorType("ADMIN")
                    .actorUsername(actor)
                    .result("SUCCESS")
                    .riskLevel("HIGH")
                    .detail(detail)
                    .build());
            outboxService.publish("USER", String.valueOf(userId), "C2_USER_STATUS_CHANGED_BY_K1", detail);
            updated++;
        }
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restoreUsersFrozenBySource(List<String> userNos, String reason, String operator, String sourceRef) {
        int updated = 0;
        String normalizedReason = StringUtils.hasText(reason) ? reason.trim() : "K1 cluster released";
        String actor = StringUtils.hasText(operator) ? operator.trim() : "system";
        String normalizedSourceRef = StringUtils.hasText(sourceRef) ? sourceRef.trim() : "K1";
        for (String userNo : new LinkedHashSet<>(userNos == null ? List.of() : userNos)) {
            if (!StringUtils.hasText(userNo)) continue;
            Long userId = userRepository.findUserIdByLookupKey(userNo.trim()).orElse(null);
            if (userId == null || !userRepository.restoreUserStatusByFreezeSource(
                    userId, "K1_MULTI_ACCOUNT_CLUSTER", normalizedSourceRef)) continue;
            int withdrawalsRestored = withdrawalControlFacade.restoreWithdrawalsFrozenByUserStatus(
                    userId, normalizedReason, actor);
            Map<String, Object> detail = Map.of(
                    "source", "K1_MULTI_ACCOUNT_CLUSTER",
                    "sourceRef", normalizedSourceRef,
                    "fromStatus", "FROZEN",
                    "toStatus", "ACTIVE",
                    "withdrawalsRestored", withdrawalsRestored,
                    "reason", normalizedReason);
            auditLogService.recordRequired(AuditLogWriteRequest.builder()
                    .action("C2_USER_STATUS_RESTORED_BY_K1")
                    .resourceType("USER")
                    .resourceId(String.valueOf(userId))
                    .bizNo(String.valueOf(userId))
                    .userId(userId)
                    .actorType("ADMIN")
                    .actorUsername(actor)
                    .result("SUCCESS")
                    .riskLevel("HIGH")
                    .detail(detail)
                    .build());
            outboxService.publish("USER", String.valueOf(userId), "C2_USER_STATUS_RESTORED_BY_K1", detail);
            updated++;
        }
        return updated;
    }
}
