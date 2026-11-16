package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.PublishedHowContentService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublishedHowContentController {
    private final PublishedHowContentService service;
    private final AdminIdempotencyService idempotency;

    @Autowired
    public PublishedHowContentController(PublishedHowContentService service, AdminIdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    PublishedHowContentController(PublishedHowContentService service) {
        this(service, null);
    }

    @GetMapping("/api/content/how-it-works/{contentKey}")
    public ApiResult<Map<String, Object>> published(@PathVariable String contentKey,
                                                     @RequestParam(defaultValue = "en") String locale,@RequestParam(required=false)Integer schemaVersion) {
        return service.publicContent(contentKey, locale,schemaVersion);
    }
    public ApiResult<Map<String,Object>> published(String key,String locale){return service.publicContent(key,locale);}

    @GetMapping(OpsAdminApi.ADMIN_PREFIX + "/content/how-it-works")
    @PreAuthorize("hasAuthority('platform_a3_read')")
    public ApiResult<Map<String, Object>> adminView() { return service.adminView(); }

    @PutMapping(OpsAdminApi.ADMIN_PREFIX + "/content/how-it-works")
    @PreAuthorize("hasAuthority('platform_a3_write')")
    public ApiResult<Map<String, Object>> update(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) Request request) {
        return request == null ? ApiResult.fail(422, "HOW_CONTENT_INVALID")
                : A3ContentCommandIdempotency.execute(idempotency, "A3_HOW_CONTENT_UPDATE",
                idempotencyKey, request,
                () -> service.update(request.version(), request.status(), request.contents(),
                        request.expectedRevision(), request.reason()));
    }

    ApiResult<Map<String, Object>> update(Request request) {
        return update(null, request);
    }

    public record Request(String version, String status, Map<String, Object> contents,
                          Long expectedRevision, String reason) { }
}
