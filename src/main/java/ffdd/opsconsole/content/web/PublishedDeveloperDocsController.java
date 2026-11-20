package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.PublishedDeveloperDocsService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublishedDeveloperDocsController {
    private final PublishedDeveloperDocsService service;
    private final AdminIdempotencyService idempotency;

    @GetMapping("/api/developer/docs")
    public ApiResult<Map<String, Object>> published(@RequestParam(defaultValue = "en") String locale) {
        return service.publicDocument(locale);
    }

    @GetMapping(OpsAdminApi.ADMIN_PREFIX + "/developer/docs")
    @PreAuthorize("hasAuthority('platform_a3_read')")
    public ApiResult<Map<String, Object>> adminView() { return service.adminView(); }

    @PutMapping(OpsAdminApi.ADMIN_PREFIX + "/developer/docs")
    @PreAuthorize("hasAuthority('platform_a3_write')")
    public ApiResult<Map<String, Object>> update(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) Request request) {
        return request == null ? ApiResult.fail(422, "DEVELOPER_DOCS_CONTENT_INVALID")
                : A3ContentCommandIdempotency.execute(idempotency, "A3_DEVELOPER_DOCS_UPDATE",
                idempotencyKey, request,
                () -> service.update(request.version(), request.status(), request.locales(),
                        request.expectedRevision(), request.reason()));
    }

    public record Request(String version, String status, Map<String, Object> locales,
                          Long expectedRevision, String reason) { }
}
