package ffdd.opsconsole.user.application;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.facade.UserKycStatusFacade;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserKycStatusFacadeAdapter implements UserKycStatusFacade {
    private final UserOpsRepository userRepository;
    private final AuditLogService auditLogService;

    @Override
    public boolean updateKycStatusByUserNo(String userNo, String kycStatus, String reason, String operator) {
        if (!StringUtils.hasText(userNo) || !StringUtils.hasText(kycStatus)) {
            return false;
        }
        Long userId = userRepository.findUserIdByLookupKey(userNo.trim()).orElse(null);
        if (userId == null) {
            return false;
        }
        userRepository.updateKycStatus(userId, kycStatus.trim().toUpperCase(), text(reason, "K5 review decision"));
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("C4_KYC_STATUS_CHANGED_BY_K5")
                .resourceType("USER_KYC")
                .resourceId(String.valueOf(userId))
                .bizNo(userNo.trim())
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(actor(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of("userNo", userNo.trim(), "kycStatus", kycStatus.trim().toUpperCase(), "reason", text(reason, "")))
                .build());
        return true;
    }

    private String actor(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
