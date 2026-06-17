package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsQueryRequest;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.dto.AuditExportRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/audit")
@PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
public class OpsAuditController {
    private final AuditLogService auditLogService;

    public OpsAuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/logs")
    public ApiResult<List<AuditLogRecord>> logs(AuditLogQueryRequest request) {
        return ApiResult.ok(auditLogService.list(request));
    }

    @GetMapping("/logs/trace/{traceId}")
    public ApiResult<List<AuditLogRecord>> byTrace(@PathVariable String traceId) {
        AuditLogQueryRequest request = new AuditLogQueryRequest();
        request.setTraceId(traceId);
        request.setLimit(200);
        return ApiResult.ok(auditLogService.list(request));
    }

    @PostMapping("/exports")
    @PreAuthorize("hasAuthority('PERM_AUDIT_EXPORT')")
    public ApiResult<Map<String, Object>> export(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AuditExportRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(),
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }

        String normalizedKey = idempotencyKey.trim();
        String jobNo = "A2-AUD-EXP-" + System.currentTimeMillis();
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("A2_AUDIT_EXPORTED")
                .resourceType("A2_AUDIT_EXPORT")
                .resourceId(jobNo)
                .bizNo(jobNo)
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(Map.of(
                        "jobNo", jobNo,
                        "filter", request.filter(),
                        "reason", request.reason().trim(),
                        "idempotencyKey", normalizedKey))
                .build());
        return ApiResult.ok(Map.of(
                "jobNo", jobNo,
                "status", "CREATED",
                "idempotencyKey", normalizedKey,
                "createdAt", LocalDateTime.now().toString()));
    }

    @GetMapping("/stats/summary")
    public ApiResult<AuditStatsSummaryResponse> summary(AuditStatsQueryRequest request) {
        return ApiResult.ok(auditLogService.summary(request));
    }

    @GetMapping("/stats/actions")
    public ApiResult<List<AuditStatsBucket>> actions(AuditStatsQueryRequest request) {
        return ApiResult.ok(auditLogService.topActions(request));
    }

    @GetMapping("/stats/services")
    public ApiResult<List<AuditStatsBucket>> services(AuditStatsQueryRequest request) {
        return ApiResult.ok(auditLogService.topServices(request));
    }

    @GetMapping("/stats/users")
    public ApiResult<List<AuditStatsBucket>> users(AuditStatsQueryRequest request) {
        return ApiResult.ok(auditLogService.topUsers(request));
    }
}
