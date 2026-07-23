package ffdd.opsconsole.bi.web;

import ffdd.opsconsole.bi.application.BehaviorAnalyticsService;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/bi")
@RequiredArgsConstructor
public class OpsBehaviorAnalyticsController {
    private final BehaviorAnalyticsService service;

    @GetMapping("/behavior")
    @PreAuthorize("hasAuthority('bi_l6_read')")
    public ApiResult<Map<String, Object>> behavior(
            @RequestParam(value = "window", required = false) String window,
            @RequestParam(value = "device", required = false) String device,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestParam(value = "depth", required = false) String depth,
            @RequestParam(value = "sort", required = false) String sort) {
        return service.behavior(window, device, locale, depth, sort);
    }

    @GetMapping("/behavior/click-heat")
    @PreAuthorize("hasAuthority('bi_l6_read')")
    public ApiResult<Map<String, Object>> clickHeat(
            @RequestParam("route") String route,
            @RequestParam(value = "window", required = false) String window,
            @RequestParam(value = "device", required = false) String device,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestParam(value = "depth", required = false) String depth) {
        return service.clickHeat(route, window, device, locale, depth);
    }

    @GetMapping("/behavior/page-catalog")
    @PreAuthorize("hasAuthority('bi_l6_read')")
    public ApiResult<Map<String, Object>> pageCatalog() {
        return service.pageCatalog();
    }

    @GetMapping("/export/behavior")
    @PreAuthorize("hasAuthority('bi_l6_export')")
    public ResponseEntity<byte[]> export(
            @RequestParam(value = "window", required = false) String window,
            @RequestParam(value = "device", required = false) String device,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestParam(value = "depth", required = false) String depth,
            @RequestParam(value = "sort", required = false) String sort) {
        byte[] body = service.exportBehavior(window, device, locale, depth, sort);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("l6-behavior.csv", StandardCharsets.UTF_8).build().toString())
                .body(body);
    }
}
