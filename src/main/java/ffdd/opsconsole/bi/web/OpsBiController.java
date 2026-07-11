package ffdd.opsconsole.bi.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.bi.application.OpsBiService;
import ffdd.opsconsole.bi.domain.BiReportDownloadFile;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.dto.BiDashboardValueRequest;
import ffdd.opsconsole.bi.dto.BiRegulatoryTemplateRequest;
import ffdd.opsconsole.bi.dto.BiReportActionRequest;
import ffdd.opsconsole.bi.dto.BiReportCreateRequest;
import ffdd.opsconsole.bi.dto.BiReportQueryRequest;
import ffdd.opsconsole.common.api.OpsAdminApi;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/bi")
@RequiredArgsConstructor
public class OpsBiController {
    private final OpsBiService biService;

    @GetMapping("/overview")
    @PreAuthorize("hasAnyAuthority('bi_l1_read','bi_l2_read','bi_l3_read','bi_l4_read','bi_l5_read','bi_l6_read')")
    public ApiResult<Map<String, Object>> overview() {
        return biService.overview();
    }

    @GetMapping("/kpi/overview")
    @PreAuthorize("hasAuthority('bi_l1_read')")
    public ApiResult<Map<String, Object>> kpiOverview() {
        return biService.kpiOverview();
    }

    @GetMapping("/funnel/overview")
    @PreAuthorize("hasAuthority('bi_l2_read')")
    public ApiResult<Map<String, Object>> funnelOverview() {
        return biService.funnelOverview();
    }

    @GetMapping("/finance/overview")
    @PreAuthorize("hasAuthority('bi_l3_read')")
    public ApiResult<Map<String, Object>> financeOverview() {
        return biService.financeOverview();
    }

    @GetMapping("/operations/overview")
    @PreAuthorize("hasAuthority('bi_l4_read')")
    public ApiResult<Map<String, Object>> operationsOverview() {
        return biService.operationsOverview();
    }

    @GetMapping("/export/overview")
    @PreAuthorize("hasAuthority('bi_l5_read')")
    public ApiResult<Map<String, Object>> exportOverview() {
        return biService.exportOverview();
    }

    @GetMapping("/behavior-heatmap/overview")
    @PreAuthorize("hasAuthority('bi_l6_read')")
    public ApiResult<Map<String, Object>> behaviorHeatmapOverview(
            @RequestParam(value = "window", required = false) String window) {
        return biService.behaviorHeatmapOverview(window);
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('bi_l5_read')")
    public ApiResult<PageResult<BiReportView>> reports(@ModelAttribute BiReportQueryRequest request) {
        return biService.reports(request);
    }

    @PostMapping("/reports")
    @PreAuthorize("hasAuthority('bi_l5_write')")
    public ApiResult<Map<String, Object>> createReport(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiReportCreateRequest request) {
        return biService.createReport(idempotencyKey, request);
    }

    @GetMapping("/regulatory/templates")
    @PreAuthorize("hasAuthority('bi_l5_read')")
    public ApiResult<Map<String, Object>> regulatoryTemplates() {
        return biService.regulatoryTemplates();
    }

    @PostMapping("/regulatory/templates")
    @PreAuthorize("hasAuthority('bi_l5_write')")
    public ApiResult<Map<String, Object>> createRegulatoryTemplate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiRegulatoryTemplateRequest request) {
        return biService.createRegulatoryTemplate(idempotencyKey, request);
    }

    @PatchMapping("/regulatory/schedule")
    @PreAuthorize("hasAuthority('bi_l5_write')")
    public ApiResult<Map<String, Object>> updateRegulatorySchedule(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiDashboardValueRequest request) {
        return biService.updateRegulatorySchedule(idempotencyKey, request);
    }

    @PatchMapping("/export/params/{paramKey}")
    @PreAuthorize("hasAuthority('bi_l5_write')")
    public ApiResult<Map<String, Object>> updateExportParam(
            @PathVariable String paramKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiDashboardValueRequest request) {
        return biService.updateExportParam(paramKey, idempotencyKey, request);
    }

    @GetMapping("/exports/{reportId}/download-token")
    @PreAuthorize("hasAuthority('bi_l5_write')")
    public ApiResult<Map<String, Object>> downloadToken(@PathVariable String reportId) {
        return biService.downloadToken(reportId);
    }

    @GetMapping("/exports/{reportId}/download")
    @PreAuthorize("hasAuthority('bi_l5_write')")
    public ResponseEntity<?> downloadFile(@PathVariable String reportId) {
        ApiResult<BiReportDownloadFile> result = biService.downloadFile(reportId);
        if (result.getCode() != 0) {
            return ResponseEntity.status(result.getCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result);
        }
        BiReportDownloadFile file = result.getData();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.body().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.body());
    }

    @PostMapping("/reports/{reportId}/{action}")
    // action 路径变量多态：可能含 HIGH（放行/执行门槛 bi_l5_task_approve、解密导出 bi_l5_decrypt_export、生成监管报告 bi_l5_regulatory_generate），建议 OpsBiService 层按 action 取值做二次细粒度校验
    @PreAuthorize("hasAnyAuthority('bi_l5_write','bi_l5_task_approve','bi_l5_decrypt_export','bi_l5_regulatory_generate')")
    public ApiResult<Map<String, Object>> reportAction(
            @PathVariable String reportId,
            @PathVariable String action,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiReportActionRequest request) {
        return biService.reportAction(reportId, action, idempotencyKey, request);
    }
}
