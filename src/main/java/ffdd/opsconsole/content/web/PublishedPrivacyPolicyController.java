package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.PublishedPrivacyPolicyService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PublishedPrivacyPolicyController {
    private final PublishedPrivacyPolicyService service;

    @GetMapping("/api/legal/privacy-policy/current")
    public ApiResult<Map<String, Object>> published(@RequestParam(defaultValue = "en") String locale) {
        return service.publicPolicy(locale);
    }

    @GetMapping("/api/admin/content/privacy-policy")
    @PreAuthorize("hasAuthority('content_legal_terms_read')")
    public ApiResult<Map<String, Object>> adminView() { return service.adminView(); }

    @PutMapping("/api/admin/content/privacy-policy")
    @PreAuthorize("hasAuthority('content_legal_terms_write') and (#request == null or #request.status() == 'DRAFT' or hasAuthority('content_legal_terms_publish'))")
    public ApiResult<Map<String, Object>> update(@RequestBody(required = false) Request request) {
        return request == null ? ApiResult.fail(422, "PRIVACY_POLICY_INVALID")
                : service.update(request.version(), request.status(), request.locales(), request.expectedRevision(), request.reason());
    }
    public record Request(String version, String status, Map<String, Object> locales, Long expectedRevision, String reason) { }
}
