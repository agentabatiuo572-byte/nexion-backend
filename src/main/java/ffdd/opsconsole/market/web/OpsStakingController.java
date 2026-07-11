package ffdd.opsconsole.market.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/market/staking")
@RequiredArgsConstructor
public class OpsStakingController {
    private final OpsNexMarketService marketService;

    @GetMapping
    @PreAuthorize("hasAuthority('finprod_g1_read')")
    public ApiResult<Map<String, Object>> overview() {
        return marketService.stakingOverview();
    }

    @PatchMapping("/pools/{tierKey}/params/{paramKey}")
    @PreAuthorize("hasAnyAuthority('finprod_g1_apy_write','finprod_g1_penalty_write','finprod_g1_min_write')")
    public ApiResult<Map<String, Object>> updatePoolParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String tierKey,
            @PathVariable String paramKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return marketService.updateStakingPoolParam(idempotencyKey, tierKey, paramKey, request);
    }

    @PatchMapping("/pools/{tierKey}/sale-status")
    @PreAuthorize("hasAuthority('finprod_g1_write')")
    public ApiResult<Map<String, Object>> updatePoolSaleStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String tierKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return marketService.updateStakingPoolSaleStatus(idempotencyKey, tierKey, request);
    }

    @PatchMapping("/pools/{tierKey}/kill-status")
    @PreAuthorize("hasAuthority('finprod_g1_kill_toggle')")
    public ApiResult<Map<String, Object>> updatePoolKillStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String tierKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return marketService.updateStakingPoolKillStatus(idempotencyKey, tierKey, request);
    }
}
