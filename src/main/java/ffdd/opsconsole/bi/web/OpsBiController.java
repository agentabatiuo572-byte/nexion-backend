package ffdd.opsconsole.bi.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.bi.application.OpsBiService;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.dto.BiDashboardValueRequest;
import ffdd.opsconsole.bi.dto.BiRegulatoryTemplateRequest;
import ffdd.opsconsole.bi.dto.BiReportActionRequest;
import ffdd.opsconsole.bi.dto.BiReportCreateRequest;
import ffdd.opsconsole.bi.dto.BiReportQueryRequest;
import ffdd.opsconsole.common.api.OpsAdminApi;
import java.util.Map;
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
    public ApiResult<Map<String, Object>> overview() {
        return biService.overview();
    }

    @GetMapping("/kpi/overview")
    public ApiResult<Map<String, Object>> kpiOverview() {
        return biService.kpiOverview();
    }

    @GetMapping("/funnel/overview")
    public ApiResult<Map<String, Object>> funnelOverview() {
        return biService.funnelOverview();
    }

    @GetMapping("/finance/overview")
    public ApiResult<Map<String, Object>> financeOverview() {
        return biService.financeOverview();
    }

    @GetMapping("/operations/overview")
    public ApiResult<Map<String, Object>> operationsOverview() {
        return biService.operationsOverview();
    }

    @GetMapping("/export/overview")
    public ApiResult<Map<String, Object>> exportOverview() {
        return biService.exportOverview();
    }

    @GetMapping("/behavior-heatmap/overview")
    public ApiResult<Map<String, Object>> behaviorHeatmapOverview(
            @RequestParam(value = "window", required = false) String window) {
        return biService.behaviorHeatmapOverview(window);
    }

    @GetMapping("/reports")
    public ApiResult<PageResult<BiReportView>> reports(@ModelAttribute BiReportQueryRequest request) {
        return biService.reports(request);
    }

    @PostMapping("/reports")
    public ApiResult<Map<String, Object>> createReport(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiReportCreateRequest request) {
        return biService.createReport(idempotencyKey, request);
    }

    @GetMapping("/regulatory/templates")
    public ApiResult<Map<String, Object>> regulatoryTemplates() {
        return biService.regulatoryTemplates();
    }

    @PostMapping("/regulatory/templates")
    public ApiResult<Map<String, Object>> createRegulatoryTemplate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiRegulatoryTemplateRequest request) {
        return biService.createRegulatoryTemplate(idempotencyKey, request);
    }

    @PatchMapping("/regulatory/schedule")
    public ApiResult<Map<String, Object>> updateRegulatorySchedule(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiDashboardValueRequest request) {
        return biService.updateRegulatorySchedule(idempotencyKey, request);
    }

    @PatchMapping("/export/params/{paramKey}")
    public ApiResult<Map<String, Object>> updateExportParam(
            @PathVariable String paramKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiDashboardValueRequest request) {
        return biService.updateExportParam(paramKey, idempotencyKey, request);
    }

    @GetMapping("/exports/{reportId}/download-token")
    public ApiResult<Map<String, Object>> downloadToken(@PathVariable String reportId) {
        return biService.downloadToken(reportId);
    }

    @PostMapping("/reports/{reportId}/{action}")
    public ApiResult<Map<String, Object>> reportAction(
            @PathVariable String reportId,
            @PathVariable String action,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiReportActionRequest request) {
        return biService.reportAction(reportId, action, idempotencyKey, request);
    }
}
