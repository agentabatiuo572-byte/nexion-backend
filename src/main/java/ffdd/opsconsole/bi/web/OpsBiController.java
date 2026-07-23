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

    @GetMapping("/kpi")
    @PreAuthorize("hasAuthority('bi_l1_read')")
    public ApiResult<Map<String, Object>> kpi(
            @RequestParam(value = "window", required = false) String window,
            @RequestParam(value = "cohort", required = false) String cohort,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestParam(value = "ref", required = false) String ref) {
        return biService.kpiOverview(window, cohort, phase, locale, ref);
    }

    @GetMapping("/kpi/{kpiId}/drilldown")
    @PreAuthorize("hasAuthority('bi_l1_read')")
    public ApiResult<Map<String, Object>> kpiDrilldown(
            @PathVariable int kpiId,
            @RequestParam(value = "window", required = false) String window,
            @RequestParam(value = "cohort", required = false) String cohort,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestParam(value = "ref", required = false) String ref) {
        return biService.kpiDrilldown(kpiId, window, cohort, phase, locale, ref);
    }

    @GetMapping("/kpi/trend")
    @PreAuthorize("hasAuthority('bi_l1_read')")
    public ApiResult<Map<String, Object>> kpiTrend(
            @RequestParam("kpiId") int kpiId,
            @RequestParam(value = "window", required = false) String window,
            @RequestParam(value = "cohort", required = false) String cohort,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestParam(value = "ref", required = false) String ref) {
        return biService.kpiTrend(kpiId, window, cohort, phase, locale, ref);
    }

    @GetMapping("/funnel/overview")
    @PreAuthorize("hasAuthority('bi_l2_read')")
    public ApiResult<Map<String, Object>> funnelOverview() {
        return biService.funnelOverview();
    }

    @GetMapping("/funnel/drilldown")
    @PreAuthorize("hasAuthority('bi_l2_read')")
    public ApiResult<Map<String, Object>> funnelDrilldown(
            @RequestParam(value = "stage", required = false) String stage,
            @RequestParam(value = "cohort", required = false) String cohort,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestParam(value = "ref", required = false) String ref) {
        return biService.funnelDrilldown(stage, cohort, phase, locale, ref);
    }

    @GetMapping("/retention/cohort-matrix")
    @PreAuthorize("hasAuthority('bi_l2_read')")
    public ApiResult<Map<String, Object>> retentionCohortMatrix(
            @RequestParam(value = "cohortRange", required = false) String cohortRange,
            @RequestParam(value = "window", required = false) String window) {
        return biService.retentionCohortMatrix(cohortRange, window);
    }

    @GetMapping("/retention/curve")
    @PreAuthorize("hasAuthority('bi_l2_read')")
    public ApiResult<Map<String, Object>> retentionCurve(
            @RequestParam(value = "cohort", required = false) String cohort) {
        return biService.retentionCurve(cohort);
    }

    @GetMapping("/funnel/cross")
    @PreAuthorize("hasAuthority('bi_l2_read')")
    public ApiResult<Map<String, Object>> funnelCross(
            @RequestParam(value = "dim1", required = false) String dim1,
            @RequestParam(value = "dim2", required = false) String dim2,
            @RequestParam(value = "metric", required = false) String metric) {
        return biService.funnelCross(dim1, dim2, metric);
    }

    @GetMapping("/finance/overview")
    @PreAuthorize("hasAuthority('bi_l3_read')")
    public ApiResult<Map<String, Object>> financeOverview() {
        return biService.financeOverview();
    }

    @GetMapping("/operations/overview")
    @PreAuthorize("hasAuthority('bi_l4_read')")
    public ApiResult<Map<String, Object>> operationsOverview(
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return biService.operationsOverview(period, phase, from, to);
    }

    @GetMapping("/devices")
    @PreAuthorize("hasAuthority('bi_l4_read')")
    public ApiResult<Map<String, Object>> operationsDevices(
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "generation", required = false) String generation,
            @RequestParam(value = "model", required = false) String model) {
        return biService.operationsDevices(period, phase, from, to, generation, model);
    }

    @GetMapping("/tasks")
    @PreAuthorize("hasAuthority('bi_l4_read')")
    public ApiResult<Map<String, Object>> operationsTasks(
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "tier", required = false) String tier) {
        return biService.operationsTasks(period, phase, from, to, tier);
    }

    @GetMapping("/network")
    @PreAuthorize("hasAuthority('bi_l4_read')")
    public ApiResult<Map<String, Object>> operationsNetwork(
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return biService.operationsNetwork(period, phase, from, to);
    }

    @GetMapping("/phase-effect")
    @PreAuthorize("hasAuthority('bi_l4_read')")
    public ApiResult<Map<String, Object>> operationsPhaseEffect(
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "metric", required = false) String metric) {
        return biService.operationsPhaseEffect(period, phase, from, to, metric);
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
    @PreAuthorize("hasAnyAuthority('bi_l1_write','bi_l2_write','bi_l3_write','bi_l3_export_detail','bi_l4_write','bi_l4_export_tree','bi_l5_write','bi_l5_regulatory_generate')")
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
    @PreAuthorize("hasAnyAuthority('bi_l1_write','bi_l2_write','bi_l3_write','bi_l3_export_detail','bi_l4_write','bi_l4_export_tree','bi_l5_write','user_c4_export')")
    public ApiResult<Map<String, Object>> downloadToken(@PathVariable String reportId) {
        return biService.downloadToken(reportId);
    }

    @GetMapping("/exports/{reportId}/download")
    @PreAuthorize("hasAnyAuthority('bi_l1_write','bi_l2_write','bi_l3_write','bi_l3_export_detail','bi_l4_write','bi_l4_export_tree','bi_l5_write','user_c4_export')")
    public ResponseEntity<?> downloadFile(@PathVariable String reportId,
                                          @RequestParam(value = "token", required = false) String downloadToken) {
        ApiResult<BiReportDownloadFile> result = biService.downloadFile(reportId, downloadToken);
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
    @PreAuthorize("hasAnyAuthority('bi_l1_write','bi_l2_write','bi_l3_write','bi_l3_export_detail','bi_l4_write','bi_l4_export_tree','bi_l5_write','bi_l5_task_approve','bi_l5_decrypt_export','bi_l5_regulatory_generate')")
    public ApiResult<Map<String, Object>> reportAction(
            @PathVariable String reportId,
            @PathVariable String action,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiReportActionRequest request) {
        return biService.reportAction(reportId, action, idempotencyKey, request);
    }
}
