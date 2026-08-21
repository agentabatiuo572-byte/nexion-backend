package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.PublishedHowContentService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublishedHowContentController {
    private final PublishedHowContentService service;

    @GetMapping("/api/content/how-it-works/{contentKey}")
    public ApiResult<Map<String, Object>> published(@PathVariable String contentKey,
                                                     @RequestParam(defaultValue = "en") String locale) {
        return service.publicContent(contentKey, locale);
    }

    @GetMapping(OpsAdminApi.ADMIN_PREFIX + "/content/how-it-works")
    @PreAuthorize("hasAuthority('platform_a3_read')")
    public ApiResult<Map<String, Object>> adminView() { return service.adminView(); }

    @PutMapping(OpsAdminApi.ADMIN_PREFIX + "/content/how-it-works")
    @PreAuthorize("hasAuthority('platform_a3_write')")
    public ApiResult<Map<String, Object>> update(@RequestBody(required = false) Request request) {
        return request == null ? ApiResult.fail(422, "HOW_CONTENT_INVALID")
                : service.update(request.version(), request.status(), request.contents(), request.expectedRevision(), request.reason());
    }

    public record Request(String version, String status, Map<String, Object> contents,
                          Long expectedRevision, String reason) { }
}
