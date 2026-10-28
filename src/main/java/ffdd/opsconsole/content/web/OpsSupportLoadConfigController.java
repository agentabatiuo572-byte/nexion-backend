package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.application.OpsSupportAgentService;
import ffdd.opsconsole.content.application.OpsSupportLoadConfigService;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.dto.SupportLoadConfigUpdateRequest;
import ffdd.opsconsole.content.dto.SupportLoadRebalanceRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seat capacity of the live customer-service workbench. The historical URL prefix is kept for
 * existing console callers; every other route under it is answered 410 by {@link RetiredSupportTickets}.
 */
@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/tickets/load-config")
@RequiredArgsConstructor
public class OpsSupportLoadConfigController {
    private final OpsSupportLoadConfigService loadConfigService;
    private final OpsSupportAgentService supportAgentService;
    private final ProductionSupportPathGuard productionPathGuard;

    // 坐席负载配置查询 — M1 客服中心总览 读
    @PreAuthorize("hasAuthority('service_m1_read')")
    @GetMapping
    public ApiResult<Map<String, Object>> loadConfig() {
        return loadConfigService.loadConfig();
    }

    // 坐席负载配置更新 — M1 客服中心总览 写
    @PreAuthorize("hasAuthority('service_m1_write')")
    @PatchMapping
    public ApiResult<Map<String, Object>> updateLoadConfig(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportLoadConfigUpdateRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        if (!supportAgentService.canManageSupportSeats()) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "SUPPORT_LOAD_MANAGEMENT_FORBIDDEN");
        }
        return loadConfigService.updateLoadConfig(idempotencyKey, request);
    }

    // 坐席负载手动均衡 — M1 客服中心总览 写
    @PreAuthorize("hasAuthority('service_m1_write')")
    @PostMapping("/rebalance")
    public ApiResult<Map<String, Object>> rebalanceLoad(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportLoadRebalanceRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        if (!supportAgentService.canManageSupportSeats()) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "SUPPORT_LOAD_MANAGEMENT_FORBIDDEN");
        }
        return loadConfigService.rebalanceLoad(idempotencyKey, request);
    }
}
