package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.OpsTrustDisclosureService;
import ffdd.opsconsole.content.domain.AppTrustSectionsView;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/content/trust/sections")
@RequiredArgsConstructor
public class AppTrustSectionController {
    private final OpsTrustDisclosureService service;

    @GetMapping("/current")
    @PreAuthorize("permitAll()")
    public ApiResult<AppTrustSectionsView> current() {
        return service.publishedSections();
    }

    @PostMapping("/{sectionKey}/view")
    @PreAuthorize("isAuthenticated()")
    public ApiResult<Void> recordSectionView(
            @PathVariable String sectionKey,
            @RequestBody(required = false) TrustSectionViewEventRequest request,
            Authentication authentication) {
        if (!(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        }
        return service.recordSectionView(sectionKey, request == null ? null : request.locale());
    }

    public record TrustSectionViewEventRequest(String locale) {
    }
}
