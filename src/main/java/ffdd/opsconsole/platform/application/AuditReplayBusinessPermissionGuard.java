package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.application.DisclosureContentHash;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ApplicationService
@RequiredArgsConstructor
public class AuditReplayBusinessPermissionGuard {
    private static final Set<String> SENSITIVE_TRUST_SECTIONS = Set.of(
            "financials", "nexnarrative", "nexstory", "auditsreserves", "compliancebadges");

    private final TrustDisclosureRepository trustDisclosureRepository;

    public record DelegatedProposalDescriptor(
            String action,
            String objectId,
            String beforeValue,
            String afterValue,
            String sourceDomain,
            String operationType,
            boolean amplifies,
            AuditLockTarget target) {
    }

    public ApiResult<Void> validateProposal(AuditReplayCommand command) {
        if (command == null || command.op() == null) {
            return delegatedProposal()
                    ? ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "A2_BUSINESS_COMMAND_REQUIRED")
                    : ApiResult.ok();
        }
        if ("J".equalsIgnoreCase(command.domain())) {
            String operation = command.op().trim().toLowerCase(Locale.ROOT);
            if (operation.startsWith("j1_")) {
                return ApiResult.fail(409, "J1_DIRECT_EXECUTION_REQUIRED");
            }
        }
        String operation = command.op().trim().toLowerCase(Locale.ROOT);
        String requiredAuthority = requiredAuthority(command, operation);
        if (delegatedProposal() && requiredAuthority == null) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "A2_BUSINESS_PERMISSION_UNMAPPED");
        }
        if (requiredAuthority != null && !hasAuthority(requiredAuthority)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "A2_BUSINESS_PERMISSION_DENIED:" + requiredAuthority);
        }
        if ("I".equalsIgnoreCase(command.domain())
                && Set.of("i4_disclosure_publish", "i5_disclosure_publish").contains(operation)) {
            return validateDisclosureSnapshot(command.params());
        }
        return ApiResult.ok();
    }

    /**
     * A delegated proposer may choose a business command, but may not choose a
     * different display object, lock target, risk direction or approval copy.
     * Canonical fields are derived from the executable command and replace the
     * client-supplied ticket description before persistence.
     */
    public ApiResult<DelegatedProposalDescriptor> validateProposalContext(
            AuditOperationProposalRequest request) {
        boolean delegated = delegatedProposal();
        AuditReplayCommand command = request == null ? null : request.command();
        DelegatedProposalDescriptor descriptor = delegatedDescriptor(command);
        if (descriptor == null) {
            return delegated
                    ? ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "A2_BUSINESS_CONTEXT_UNMAPPED")
                    : ApiResult.ok(null);
        }
        AuditLockTarget suppliedTarget = request.target();
        boolean targetMatches = suppliedTarget != null
                && descriptor.target().domain().equalsIgnoreCase(text(suppliedTarget.domain()))
                && descriptor.target().type().equalsIgnoreCase(text(suppliedTarget.type()))
                && descriptor.target().id().equals(text(suppliedTarget.id()));
        boolean contextMatches = descriptor.sourceDomain().equalsIgnoreCase(text(request.sourceDomain()))
                && descriptor.objectId().equals(text(request.obj()))
                && (request.targets() == null || request.targets().isEmpty());
        if (!targetMatches || !contextMatches) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "A2_BUSINESS_CONTEXT_MISMATCH");
        }
        return ApiResult.ok(descriptor);
    }

    private DelegatedProposalDescriptor delegatedDescriptor(AuditReplayCommand command) {
        if (command == null || command.domain() == null || command.op() == null) {
            return null;
        }
        String domain = command.domain().trim().toUpperCase(Locale.ROOT);
        String operation = command.op().trim().toLowerCase(Locale.ROOT);
        Map<String, Object> params = command.params() == null ? Map.of() : command.params();
        if ("C".equals(domain)) {
            return delegatedC2Descriptor(operation, params);
        }
        if ("K".equals(domain)) {
            return delegatedKDescriptor(operation, params);
        }
        return null;
    }

    private DelegatedProposalDescriptor delegatedC2Descriptor(String operation, Map<String, Object> params) {
        return switch (operation) {
            case "c2_account_freeze" -> userStatusDescriptor(params, "冻结账户", "FROZEN", false);
            case "c2_account_unfreeze" -> userStatusDescriptor(params, "恢复账户", "ACTIVE", true);
            case "c2_session_revoke_all" -> userDescriptor(params, "强制登出", "0 个活跃会话", false, "user");
            case "c2_impersonate_start" -> {
                Integer ttlMinutes = impersonationTtlMinutes(params.get("ttlMinutes"));
                yield ttlMinutes == null ? null : userDescriptor(
                        params, "发起模拟登录", "只读会话 · " + ttlMinutes + " 分钟", false, "user");
            }
            case "c2_blocklist_upsert" -> {
                String kind = value(params, "kind").toUpperCase(Locale.ROOT);
                String expiryLabel = accountListExpiryLabel(params.get("expiresAt"));
                if (!Set.of("ALLOW", "BLOCK").contains(kind) || expiryLabel.isBlank()) {
                    yield null;
                }
                yield userDescriptor(params, "ALLOW".equals(kind) ? "加入信任名单" : "加入禁入名单",
                        ("ALLOW".equals(kind) ? "信任" : "禁入") + " · " + expiryLabel,
                        false, "accountlist");
            }
            case "c2_impersonate_terminate" -> {
                String sessionNo = value(params, "sessionNo");
                yield descriptor("终止模拟会话", sessionNo, "TERMINATED", false,
                        "C2", "C", "impersonation", sessionNo);
            }
            default -> null;
        };
    }

    private DelegatedProposalDescriptor delegatedKDescriptor(String operation, Map<String, Object> params) {
        String clusterId = value(params, "clusterId");
        String rowId = value(params, "rowId");
        return switch (operation) {
            case "k1_cluster_freeze" -> descriptor("批量冻结关联账户", clusterId, "frozen", false,
                    "K1", "K", "cluster", clusterId);
            case "k1_cluster_release" -> descriptor("解除误判", clusterId, "released", true,
                    "K1", "K", "cluster", clusterId);
            case "k1_cluster_cleared" -> descriptor("判定为正常", clusterId, "cleared", true,
                    "K1", "K", "cluster", clusterId);
            case "k1_cluster_flag" -> descriptor("标记可疑账户簇", clusterId, "flagged", false,
                    "K1", "K", "cluster", clusterId);
            case "k2_row_flag" -> descriptor("标记套利账户", rowId, "已标记套利", false,
                    "K2", "K", "arbitrage_row", rowId);
            case "k2_row_blockgift" -> descriptor("拦截新人礼", rowId, "新人礼已拦截", false,
                    "K2", "K", "arbitrage_row", rowId);
            case "k2_row_boardflag" -> descriptor("标记刷榜账户", rowId, "已标记刷榜", false,
                    "K2", "K", "arbitrage_row", rowId);
            case "k2_row_freeze" -> descriptor("联动 K1 批量冻结", rowId, "已联动 K1 冻结", false,
                    "K2", "K", "arbitrage_row", rowId);
            default -> null;
        };
    }

    private DelegatedProposalDescriptor userDescriptor(
            Map<String, Object> params, String action, String afterValue, boolean amplifies, String targetType) {
        String userId = positiveIdentifier(params.get("userId"));
        return descriptor(action, userId, afterValue, amplifies, "C2", "C", targetType, userId);
    }

    private DelegatedProposalDescriptor userStatusDescriptor(
            Map<String, Object> params, String action, String expectedStatus, boolean amplifies) {
        if (!expectedStatus.equalsIgnoreCase(value(params, "status"))) {
            return null;
        }
        return userDescriptor(params, action, expectedStatus, amplifies, "user");
    }

    private DelegatedProposalDescriptor descriptor(
            String action, String objectId, String afterValue, boolean amplifies,
            String sourceDomain, String targetDomain, String targetType, String targetId) {
        if (objectId == null || objectId.isBlank() || targetId == null || targetId.isBlank()) {
            return null;
        }
        return new DelegatedProposalDescriptor(
                action + " · " + objectId,
                objectId,
                "以服务器执行时状态为准",
                afterValue,
                sourceDomain,
                "acct",
                amplifies,
                new AuditLockTarget(targetDomain, targetType, targetId));
    }

    private String positiveIdentifier(Object value) {
        String identifier = value == null ? "" : String.valueOf(value).trim();
        try {
            long parsed = Long.parseLong(identifier);
            return parsed > 0 ? Long.toString(parsed) : "";
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    private Integer impersonationTtlMinutes(Object value) {
        String raw = value == null ? "" : String.valueOf(value).trim();
        if (raw.isBlank()) {
            return 15;
        }
        try {
            int parsed = Integer.parseInt(raw);
            return parsed >= 5 && parsed <= 30 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String accountListExpiryLabel(Object value) {
        String raw = value == null ? "" : String.valueOf(value).trim();
        if (raw.isBlank() || Set.of("LONG_TERM", "PERMANENT", "长期").contains(raw.toUpperCase(Locale.ROOT))) {
            return "长期";
        }
        try {
            LocalDateTime expiresAt;
            if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
                expiresAt = LocalDate.parse(raw).atTime(23, 59, 59);
            } else {
                expiresAt = LocalDateTime.parse(raw);
            }
            return expiresAt.isAfter(LocalDateTime.now())
                    ? "有效期至 " + expiresAt.toString().replace('T', ' ')
                    : "";
        } catch (DateTimeParseException ex) {
            return "";
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String requiredAuthority(AuditReplayCommand command, String operation) {
        String domain = command.domain() == null ? "" : command.domain().trim().toUpperCase(Locale.ROOT);
        return switch (domain) {
            case "C" -> switch (operation) {
                case "c2_account_freeze" -> "user_c2_account_freeze";
                case "c2_account_unfreeze" -> "user_c2_account_unfreeze";
                case "c2_session_revoke_all" -> "user_c2_session_revoke_all";
                case "c2_impersonate_terminate" -> "user_c2_impersonate_terminate";
                case "c2_impersonate_start" -> "user_c2_impersonate_start";
                case "c2_blocklist_upsert" -> "user_c2_blocklist_add";
                default -> null;
            };
            case "K" -> switch (operation) {
                case "k1_cluster_freeze" -> "risk_k1_cluster_freeze";
                case "k1_cluster_release" -> "risk_k1_cluster_release";
                case "k1_cluster_cleared" -> "risk_k1_cluster_cleared";
                case "k1_cluster_flag" -> "risk_k1_cluster_flag";
                case "k2_row_flag" -> "risk_k2_row_flag";
                case "k2_row_freeze" -> "risk_k2_row_freeze";
                case "k2_row_blockgift" -> "risk_k2_row_blockgift";
                case "k2_row_boardflag" -> "risk_k2_row_boardflag";
                default -> null;
            };
            case "I" -> switch (operation) {
                case "i4_trust_section_manage" -> sectionAuthority(command.params());
                case "i4_disclosure_publish", "i5_disclosure_publish",
                        "i5_matrix_configure", "i5_matrix_archive",
                        "i5_jurisdiction_status", "i5_jurisdiction_delete" -> "content_i5_disclosure_publish";
                case "i4_gate_adjust", "i5_gate_adjust" -> "content_i5_gate_adjust";
                default -> null;
            };
            default -> null;
        };
    }

    /**
     * Returns whether the current caller is limited to proposal creation.
     * The audit service uses the same server-side authority decision when it
     * records the proposer's role, so a full writer is not mislabeled as risk.
     */
    public boolean delegatedProposal() {
        return hasAuthority("platform_a2_proposal_create") && !hasAuthority("platform_a2_write");
    }

    private ApiResult<Void> validateDisclosureSnapshot(Map<String, Object> params) {
        String jurisdiction = value(params, "jurisdiction");
        String version = value(params, "version");
        String expectedHash = value(params, "expectedContentHash");
        Long expectedRevision = longValue(params, "expectedRevision");
        DisclosureDraftView draft = trustDisclosureRepository
                .findDisclosureVersion(jurisdiction, version).orElse(null);
        if (draft == null || !"draft".equalsIgnoreCase(draft.status())
                || expectedRevision == null || expectedRevision != draft.revision()
                || expectedHash.isBlank() || !expectedHash.equals(draft.contentHash())
                || !expectedHash.equals(DisclosureContentHash.from(
                        draft, trustDisclosureRepository.listChapters(jurisdiction, version)))) {
            return ApiResult.fail(409, "A2_DISCLOSURE_SNAPSHOT_CHANGED");
        }
        return ApiResult.ok();
    }

    private String sectionAuthority(Map<String, Object> params) {
        String sectionKey = value(params, "sectionKey");
        String action = value(params, "action");
        if (!Set.of("publish", "rollback", "archive").contains(action.toLowerCase(Locale.ROOT))) {
            return null;
        }
        String normalizedKey = sectionKey.toLowerCase(Locale.ROOT);
        boolean sensitive = SENSITIVE_TRUST_SECTIONS.contains(normalizedKey)
                || trustDisclosureRepository.listTrustSections().stream()
                .filter(section -> section.key().equalsIgnoreCase(sectionKey))
                .map(TrustSectionView::highSensitivity)
                .findFirst()
                .orElse(false);
        return sensitive ? "content_i4_trust_section_manage" : "content_i4_publish_standard";
    }

    private boolean hasAuthority(String requiredAuthority) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(requiredAuthority::equals);
    }

    private String value(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Long longValue(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
