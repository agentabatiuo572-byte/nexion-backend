package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.shared.api.ApiResult;
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

    public ApiResult<Void> validateProposal(AuditReplayCommand command) {
        if (command == null || !"I".equalsIgnoreCase(command.domain()) || command.op() == null) {
            return ApiResult.ok();
        }
        String operation = command.op().trim().toLowerCase(Locale.ROOT);
        String requiredAuthority = switch (operation) {
            case "i4_trust_section_manage" -> sectionAuthority(command.params());
            case "i4_disclosure_publish", "i5_disclosure_publish" -> "content_i5_disclosure_publish";
            case "i4_gate_adjust", "i5_gate_adjust" -> "content_i5_gate_adjust";
            default -> null;
        };
        if (requiredAuthority == null || hasAuthority(requiredAuthority)) {
            return ApiResult.ok();
        }
        return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "A2_BUSINESS_PERMISSION_DENIED:" + requiredAuthority);
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
}
