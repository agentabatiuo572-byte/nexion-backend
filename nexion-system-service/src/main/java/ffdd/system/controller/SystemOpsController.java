package ffdd.system.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditStatsQueryRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/system")
public class SystemOpsController {
    private static final int MAX_DASHBOARD_DAYS = 90;

    private final AuditLogService auditLogService;

    public SystemOpsController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-system-service",
                "database", "nexion_system",
                "responsibilities", List.of("operation config", "i18n", "content", "help center", "feature flags")));
    }

    @GetMapping("/ops/dashboard")
    @PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
    public ApiResult<Map<String, Object>> dashboard(@RequestParam(defaultValue = "7") int days) {
        AuditStatsQueryRequest query = new AuditStatsQueryRequest();
        query.setDays(normalizeDashboardDays(days));
        return ApiResult.ok(Map.of(
                "service", "nexion-system-service",
                "generatedAt", LocalDateTime.now(),
                "audit", Map.of(
                        "summary", auditLogService.summary(query),
                        "topActions", auditLogService.topActions(query),
                        "topServices", auditLogService.topServices(query),
                        "topUsers", auditLogService.topUsers(query)),
                "modules", List.of(
                        Map.of("service", "nexion-commerce-service", "domain", "orders/payments/trade-in"),
                        Map.of("service", "nexion-wallet-service", "domain", "deposits/withdrawals/exchanges"),
                        Map.of("service", "nexion-compliance-service", "domain", "KYC/risk/blacklist/proof"),
                        Map.of("service", "nexion-openapi-service", "domain", "apps/call-audit/webhooks")),
                "routes", List.of(
                        "/audit/stats/summary",
                        "/audit/stats/actions",
                        "/audit/stats/services",
                        "/audit/stats/users")));
    }

    private int normalizeDashboardDays(int days) {
        if (days < 1) {
            return 7;
        }
        return Math.min(days, MAX_DASHBOARD_DAYS);
    }
}
