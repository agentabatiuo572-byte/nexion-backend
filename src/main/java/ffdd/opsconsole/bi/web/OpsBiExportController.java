package ffdd.opsconsole.bi.web;

import ffdd.opsconsole.bi.application.OpsBiExportQueryService;
import ffdd.opsconsole.bi.application.OpsBiService;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.dto.BiReportCreateRequest;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/bi/export")
@RequiredArgsConstructor
public class OpsBiExportController {
    private final OpsBiService biService;
    private final OpsBiExportQueryService exportQueryService;

    @PostMapping("/request")
    @PreAuthorize("hasAnyAuthority('bi_l1_write','bi_l2_write','bi_l3_write','bi_l4_write')")
    public ApiResult<Map<String, Object>> request(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiReportCreateRequest request) {
        return biService.createReport(idempotencyKey, request);
    }

    @GetMapping("/network")
    @PreAuthorize("hasAuthority('bi_l4_export_tree')")
    public ApiResult<Map<String, Object>> exportNetworkTree(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String detail,
            @RequestParam(required = false) Integer depth,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operation-Reason", required = false) String reasonHeader,
            @RequestBody(required = false) Map<String, Object> body) {
        String reason = decodeReasonHeader(reasonHeader);
        if ((reason == null || reason.isBlank()) && body != null && body.get("reason") != null) {
            reason = String.valueOf(body.get("reason"));
        }
        return biService.exportNetworkTree(period, detail, depth, reason, idempotencyKey);
    }

    private String decodeReasonHeader(String reasonHeader) {
        if (reasonHeader == null || reasonHeader.isBlank()) return reasonHeader;
        try {
            return URLDecoder.decode(reasonHeader, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return reasonHeader;
        }
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('bi_l5_read')")
    public ApiResult<List<Map<String, Object>>> audit(
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) Integer limit) {
        return exportQueryService.exportAudits(operator, startTime, endTime, limit);
    }

    @GetMapping("/{exportId}")
    @PreAuthorize("hasAuthority('bi_l5_read')")
    public ApiResult<BiReportView> exportTask(@PathVariable String exportId) {
        return exportQueryService.exportTask(exportId);
    }
}
