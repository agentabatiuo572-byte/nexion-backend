package ffdd.opsconsole.shared.audit;

import ffdd.opsconsole.shared.api.ApiResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit")
@PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
public class AuditLogController {
    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
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
    public ApiResult<Map<String, Object>> export(@RequestBody(required = false) Map<String, Object> request) {
        String jobNo = "AUD-EXP-" + System.currentTimeMillis();
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("ADMIN_AUDIT_EXPORTED")
                .resourceType("AUDIT_EXPORT")
                .resourceId(jobNo)
                .bizNo(jobNo)
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(Map.of(
                        "jobNo", jobNo,
                        "filter", request == null ? Map.of() : request))
                .build());
        return ApiResult.ok(Map.of(
                "jobNo", jobNo,
                "status", "CREATED",
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
