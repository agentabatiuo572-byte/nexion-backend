package ffdd.opsconsole.user.application;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.user.mapper.UserPaymentMethodMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically closes a claimed provider revocation together with card state and required audit. */
@Service
@RequiredArgsConstructor
public class PaymentMethodRevocationFinalizer {
    private final UserPaymentMethodMapper mapper;
    private final AuditLogService audit;

    @Transactional(rollbackFor = Exception.class)
    public boolean fail(UserPaymentMethodMapper.RevokeCommandRow command, String error) {
        if (mapper.finishRevoke(command.commandNo(), "FAILED", null, error, command.sourceEnvironment()) != 1) {
            return false;
        }
        if (mapper.updateCardRevokeStatus(command.methodId(), "FAILED", command.sourceEnvironment()) != 1) {
            throw new BizException(409, "PAYMENT_METHOD_REVOKE_CARD_FINALIZE_FAILED");
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("PAYMENT_METHOD_PROVIDER_REVOKE_FAILED")
                .resourceType("USER_PAYMENT_METHOD_REVOKE").resourceId(command.commandNo())
                .userId(command.userId()).riskLevel("CRITICAL").result("FAILED")
                .detail(Map.of("error", error, "attempts", command.attempts() == null ? 1 : command.attempts() + 1))
                .build());
        return true;
    }
}
