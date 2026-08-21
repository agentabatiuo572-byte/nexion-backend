package ffdd.opsconsole.content.terms.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.terms.LegalTermsService;
import ffdd.opsconsole.content.terms.domain.LegalTermsCurrentView;
import ffdd.opsconsole.content.terms.domain.LegalTermsVersionView;
import ffdd.opsconsole.content.terms.dto.LegalTermsAckRequest;
import ffdd.opsconsole.content.terms.dto.LegalTermsDraftRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class LegalTermsController {
    private final LegalTermsService service;

    @GetMapping("/api/legal/terms/current")
    public ApiResult<LegalTermsCurrentView> current(
            @RequestParam(defaultValue = "en") String locale,
            @RequestParam(defaultValue = "GLOBAL") String jurisdiction,
            Authentication authentication) {
        return service.current(locale, jurisdiction, userId(authentication));
    }

    @PostMapping("/api/legal/terms/acknowledgment")
    public ApiResult<LegalTermsCurrentView> acknowledge(@RequestBody(required = false) LegalTermsAckRequest request, Authentication authentication) {
        return service.acknowledge(userId(authentication), request);
    }

    @GetMapping(OpsAdminApi.ADMIN_PREFIX + "/content/legal-terms")
    @PreAuthorize("hasAuthority('content_legal_terms_read')")
    public ApiResult<List<LegalTermsVersionView>> list(@RequestParam String locale, @RequestParam String jurisdiction) {
        return service.adminList(locale, jurisdiction);
    }

    @PutMapping(OpsAdminApi.ADMIN_PREFIX + "/content/legal-terms/draft")
    @PreAuthorize("hasAuthority('content_legal_terms_write')")
    public ApiResult<LegalTermsVersionView> draft(@RequestBody LegalTermsDraftRequest request) { return service.saveDraft(request); }

    @PostMapping(OpsAdminApi.ADMIN_PREFIX + "/content/legal-terms/{locale}/{jurisdiction}/{version}/publish")
    @PreAuthorize("hasAuthority('content_legal_terms_publish')")
    public ApiResult<LegalTermsVersionView> publish(@PathVariable String locale, @PathVariable String jurisdiction, @PathVariable String version,
            @RequestBody(required = false) TransitionRequest request) {
        return service.publish(locale, jurisdiction, version, request == null ? -1 : request.expectedRevision(), request == null ? null : request.reason());
    }

    @PostMapping(OpsAdminApi.ADMIN_PREFIX + "/content/legal-terms/{locale}/{jurisdiction}/{version}/revoke")
    @PreAuthorize("hasAuthority('content_legal_terms_publish')")
    public ApiResult<LegalTermsVersionView> revoke(@PathVariable String locale, @PathVariable String jurisdiction, @PathVariable String version,
            @RequestBody(required = false) TransitionRequest request) {
        return service.revoke(locale, jurisdiction, version, request == null ? -1 : request.expectedRevision(), request == null ? null : request.reason());
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try { long id = Long.parseLong(String.valueOf(authentication.getPrincipal())); return id > 0 ? id : null; }
        catch (NumberFormatException ex) { return null; }
    }
    public record TransitionRequest(long expectedRevision, String reason) { }
}
