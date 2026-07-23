package ffdd.opsconsole.user.application;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.facade.UserKycStatusFacade;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserKycStatusFacadeAdapter implements UserKycStatusFacade {
    private final UserOpsRepository userRepository;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;

    @Override
    public boolean updateKycStatusByUserNo(String userNo, String kycStatus, String reason, String operator) {
        return updateKycStatusByUserNo(userNo, kycStatus, reason, operator, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateKycStatusByUserNo(
            String userNo, String kycStatus, String reason, String operator, String ticketId) {
        if (!StringUtils.hasText(userNo) || !StringUtils.hasText(kycStatus)) {
            return false;
        }
        Long userId = userRepository.findUserIdByLookupKey(userNo.trim()).orElse(null);
        if (userId == null) {
            return false;
        }
        var before = userRepository.findKycRecord(userId).orElse(null);
        if (before == null) {
            return false;
        }
        String nextStatus = kycStatus.trim().toUpperCase();
        String actor = actor(operator);
        if (!userRepository.transitionKycStatus(
                userId, before.status(), before.version(), nextStatus,
                "K5_DECISION", text(reason, "K5 review decision"),
                text(ticketId, "K5-review"), "K5_REVIEW_DECISION", actor,
                "k5:" + text(ticketId, userNo), ticketId)) {
            return false;
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("C4_KYC_STATUS_CHANGED_BY_K5")
                .resourceType("USER_KYC")
                .resourceId(String.valueOf(userId))
                .bizNo(userNo.trim())
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(actor)
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of("userNo", userNo.trim(), "fromStatus", before.status(), "kycStatus", nextStatus,
                        "reason", text(reason, ""), "ticketId", text(ticketId, "")))
                .build());
        outboxService.publish("USER_KYC", String.valueOf(userId), "admin.kyc_status_changed", Map.of(
                "targetUserId", userId,
                "fromStatus", before.status(),
                "toStatus", nextStatus,
                "reasonCode", "K5_DECISION",
                "evidenceRef", text(ticketId, "K5-review"),
                "operator", actor,
                "source", "K5_REVIEW_DECISION",
                "occurredAt", java.time.Instant.now().toString()));
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
                    row.put("kycStatus", canonicalK5KycStatus(user.kycStatus()));
                    return row;
                })
                .toList();
    }

    private String canonicalK5KycStatus(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("C4_KYC_STATUS_INVALID");
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "VERIFIED", "APPROVED" -> "APPROVED";
            case "PENDING" -> "PENDING";
            case "NONE" -> "NONE";
            case "REJECTED" -> "REJECTED";
            default -> throw new IllegalStateException("C4_KYC_STATUS_INVALID");
        };
    }

    private String actor(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
