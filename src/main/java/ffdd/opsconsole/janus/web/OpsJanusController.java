package ffdd.opsconsole.janus.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.janus.application.OpsJanusService;
import ffdd.opsconsole.janus.domain.JanusDeviceView;
import ffdd.opsconsole.janus.domain.JanusStrategyView;
import ffdd.opsconsole.janus.dto.JanusDeviceQueryRequest;
import ffdd.opsconsole.janus.dto.JanusStatusChangeRequest;
import ffdd.opsconsole.janus.dto.JanusStrategyActionRequest;
import ffdd.opsconsole.janus.dto.JanusStrategyUpsertRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/janus")
@RequiredArgsConstructor
public class OpsJanusController {
    private final OpsJanusService janusService;

    @GetMapping("/metadata")
    @PreAuthorize("hasAuthority('risk_k6_read')")
    public ApiResult<Map<String, Object>> metadata() {
        return janusService.metadata();
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('risk_k6_read')")
    public ApiResult<Map<String, Object>> dashboard() {
        return janusService.dashboard();
    }

    @GetMapping("/devices")
    @PreAuthorize("hasAuthority('risk_k6_read')")
    public ApiResult<PageResult<JanusDeviceView>> devices(JanusDeviceQueryRequest request) {
        return janusService.devices(request);
    }

    @GetMapping("/devices/{sid}")
    @PreAuthorize("hasAuthority('risk_k6_read')")
    public ApiResult<JanusDeviceView> device(@PathVariable String sid) {
        return janusService.device(sid);
    }

    @PostMapping("/devices/{sid}/status")
    @PreAuthorize("hasAuthority('risk_k6_write')")
    public ApiResult<JanusDeviceView> updateStatus(
            @PathVariable String sid,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JanusStatusChangeRequest request) {
        return janusService.updateStatus(sid, idempotencyKey, request);
    }

    @GetMapping("/strategies")
    @PreAuthorize("hasAuthority('risk_k6_read')")
    public ApiResult<List<JanusStrategyView>> strategies() {
        return janusService.strategies();
    }

    @GetMapping("/strategies/{strategyId}")
    @PreAuthorize("hasAuthority('risk_k6_read')")
    public ApiResult<JanusStrategyView> strategy(@PathVariable String strategyId) {
        return janusService.strategy(strategyId);
    }

    @PostMapping("/strategies")
    @PreAuthorize("hasAuthority('risk_k6_write')")
    public ApiResult<JanusStrategyView> createStrategy(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JanusStrategyUpsertRequest request) {
        return janusService.createStrategy(idempotencyKey, request);
    }

    @PutMapping("/strategies/{strategyId}")
    @PreAuthorize("hasAuthority('risk_k6_write')")
    public ApiResult<JanusStrategyView> updateStrategy(
            @PathVariable String strategyId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JanusStrategyUpsertRequest request) {
        return janusService.updateStrategy(strategyId, idempotencyKey, request);
    }

    @DeleteMapping("/strategies/{strategyId}")
    @PreAuthorize("hasAuthority('risk_k6_write')")
    public ApiResult<Void> deleteStrategy(
            @PathVariable String strategyId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) JanusStrategyActionRequest request) {
        return janusService.deleteStrategy(strategyId, idempotencyKey, request);
    }

    @PostMapping("/strategies/{strategyId}/dry-run")
    @PreAuthorize("hasAuthority('risk_k6_write')")
    public ApiResult<Map<String, Object>> dryRun(
            @PathVariable String strategyId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JanusStrategyActionRequest request) {
        return janusService.dryRun(strategyId, idempotencyKey, request);
    }

    @PostMapping("/strategies/{strategyId}/{action:publish|pause|archive}")
    @PreAuthorize("hasAuthority('risk_k6_write')")
    public ApiResult<JanusStrategyView> lifecycle(
            @PathVariable String strategyId,
            @PathVariable String action,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JanusStrategyActionRequest request) {
        return janusService.lifecycle(strategyId, action, idempotencyKey, request);
    }

    @PostMapping("/strategies/{strategyId}/rollback")
    @PreAuthorize("hasAuthority('risk_k6_write')")
    public ApiResult<JanusStrategyView> rollback(
            @PathVariable String strategyId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JanusStrategyActionRequest request) {
        return janusService.rollback(strategyId, idempotencyKey, request);
    }

    @GetMapping("/health")
    @PreAuthorize("hasAuthority('risk_k6_read')")
    public ApiResult<Map<String, Object>> health() {
        return janusService.health();
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('risk_k6_read')")
    public ApiResult<List<Map<String, Object>>> audit(
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit) {
        return janusService.audit(targetType, q, limit);
    }

    @PostMapping("/exports")
    @PreAuthorize("hasAuthority('risk_k6_write')")
    public ApiResult<Map<String, Object>> export(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> request) {
        return janusService.export(idempotencyKey, request);
    }
}
