package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.PublishedDeveloperDocsService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublishedDeveloperDocsController {
    private final PublishedDeveloperDocsService service;

    @GetMapping("/api/developer/docs")
    public ApiResult<Map<String, Object>> published(@RequestParam(defaultValue = "en") String locale) {
        return service.publicDocument(locale);
    }

    @GetMapping(OpsAdminApi.ADMIN_PREFIX + "/developer/docs")
    @PreAuthorize("hasAuthority('platform_a3_read')")
    public ApiResult<Map<String, Object>> adminView() { return service.adminView(); }

    @PutMapping(OpsAdminApi.ADMIN_PREFIX + "/developer/docs")
    @PreAuthorize("hasAuthority('platform_a3_write')")
    public ApiResult<Map<String, Object>> update(@RequestBody(required = false) Request request) {
        return request == null ? ApiResult.fail(422, "DEVELOPER_DOCS_CONTENT_INVALID")
                : service.update(request.version(), request.status(), request.locales(), request.expectedRevision(), request.reason());
    }

    public record Request(String version, String status, Map<String, Object> locales,
                          Long expectedRevision, String reason) { }
}
