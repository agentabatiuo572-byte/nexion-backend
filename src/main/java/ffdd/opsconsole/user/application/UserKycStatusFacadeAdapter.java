package ffdd.opsconsole.user.application;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.facade.UserKycStatusFacade;
import java.util.Map;
import java.util.List;
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

    @Override
    public boolean userExists(String userNo) {
        return StringUtils.hasText(userNo) && userRepository.findUserIdByLookupKey(userNo.trim()).isPresent();
    }

    @Override
    public List<Map<String, Object>> reviewCandidates(String keyword, int limit) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        return userRepository.search(normalizedKeyword, "ACTIVE", null, Math.max(1, Math.min(limit, 50))).stream()
                .map(user -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("userNo", user.userNo());
                    row.put("label", StringUtils.hasText(user.nickname())
                            ? user.nickname() + " (" + user.userNo() + ")" : user.userNo());
                    row.put("sub", StringUtils.hasText(user.phoneMasked()) ? user.phoneMasked() : user.countryCode());
                    row.put("kycStatus", user.kycStatus());
                    return row;
                })
                .toList();
    }

    private String actor(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
