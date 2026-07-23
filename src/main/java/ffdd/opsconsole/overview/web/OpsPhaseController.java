package ffdd.opsconsole.overview.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.overview.application.OpsPhaseOverviewService;
import ffdd.opsconsole.overview.application.OpsPhaseOverviewService.PhaseCsvFile;
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
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/phase")
@RequiredArgsConstructor
public class OpsPhaseController {
    private final OpsPhaseOverviewService phaseOverviewService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('overview_b4_read')")
    public ApiResult<Map<String, Object>> overview(
            @RequestParam(value = "granularity", required = false) String granularity,
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "phase", required = false) String phase) {
        return phaseOverviewService.overview(granularity, month, phase);
    }

    @GetMapping("/jump")
    @PreAuthorize("hasAuthority('overview_b4_jump')")
    public ApiResult<Map<String, Object>> jump(
            @RequestParam(value = "dial", required = false) String dial,
            @RequestParam(value = "phase", required = false) String phase) {
        return phaseOverviewService.jump(dial, phase);
    }

    @GetMapping("/distribution/export")
    @PreAuthorize("hasAuthority('overview_b4_export')")
    public ResponseEntity<byte[]> export(
            @RequestParam(value = "granularity", required = false) String granularity,
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "phase", required = false) String phase) {
        PhaseCsvFile file = phaseOverviewService.export(granularity, month, phase);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.body());
    }
}
