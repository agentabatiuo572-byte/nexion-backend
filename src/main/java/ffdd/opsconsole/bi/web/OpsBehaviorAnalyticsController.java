package ffdd.opsconsole.bi.web;

import ffdd.opsconsole.bi.application.BehaviorAnalyticsService;
import ffdd.opsconsole.bi.application.BehaviorAnalyticsAcceptanceProfileCondition;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.env.Environment;
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
    private final Environment environment;

    @GetMapping("/behavior")
    @PreAuthorize("hasAuthority('bi_l6_read')")
    public ApiResult<Map<String, Object>> behavior(
            @RequestParam(value = "window", required = false) String window,
            @RequestParam(value = "device", required = false) String device,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestParam(value = "depth", required = false) String depth,
            @RequestParam(value = "sort", required = false) String sort) {
        requireProductionSurface();
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
        requireProductionSurface();
        return service.clickHeat(route, window, device, locale, depth);
    }

    @GetMapping("/behavior/page-catalog")
    @PreAuthorize("hasAuthority('bi_l6_read')")
    public ApiResult<Map<String, Object>> pageCatalog() {
        requireProductionSurface();
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
        requireProductionSurface();
        byte[] body = service.exportBehavior(window, device, locale, depth, sort);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("l6-behavior.csv", StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    private void requireProductionSurface() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles == null || activeProfiles.length == 0) return;
        if (!"PRODUCTION".equals(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor(activeProfiles))) {
            throw new BizException(422, "L6_PRODUCTION_SURFACE_FORBIDDEN");
        }
    }
}
