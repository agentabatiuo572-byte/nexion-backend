package ffdd.opsconsole.bi.web;

import ffdd.opsconsole.bi.application.OpsFunnelService;
import ffdd.opsconsole.bi.application.OpsFunnelService.FunnelCsvFile;
import ffdd.opsconsole.bi.dto.B3FunnelViewRequest;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/funnel")
@RequiredArgsConstructor
public class OpsFunnelController {
    private final OpsFunnelService funnelService;

    @GetMapping
    @PreAuthorize("hasAuthority('overview_b3_read')")
    public ApiResult<Map<String, Object>> overview(
            @RequestParam(value = "cohort", required = false) String cohort,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "ref", required = false) String ref) {
        return funnelService.overview(cohort, phase, ref);
    }

    @GetMapping("/aux-metrics")
    @PreAuthorize("hasAuthority('overview_b3_read')")
    public ApiResult<Map<String, Object>> auxMetrics(
            @RequestParam(value = "cohort", required = false) String cohort,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "ref", required = false) String ref) {
        return funnelService.auxMetrics(cohort, phase, ref);
    }

    @GetMapping("/cohort-trend")
    @PreAuthorize("hasAuthority('overview_b3_read')")
    public ApiResult<Map<String, Object>> cohortTrend(
            @RequestParam(value = "stage", required = false) String stage,
            @RequestParam(value = "cohortRange", required = false) String cohortRange,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "ref", required = false) String ref) {
        return funnelService.cohortTrend(stage, cohortRange, phase, ref);
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('overview_b3_export')")
    public ResponseEntity<byte[]> export(
            @RequestParam(value = "cohort", required = false) String cohort,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "ref", required = false) String ref) {
        FunnelCsvFile file = funnelService.export(cohort, phase, ref);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.body());
    }

    @PostMapping("/view")
    @PreAuthorize("hasAuthority('overview_b3_view_write')")
    public ApiResult<Map<String, Object>> saveView(@RequestBody B3FunnelViewRequest request) {
        return funnelService.saveView(request);
    }
}
