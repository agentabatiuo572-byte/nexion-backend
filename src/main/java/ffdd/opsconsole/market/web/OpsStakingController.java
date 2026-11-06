package ffdd.opsconsole.market.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    @PatchMapping("/visibility")
    @PreAuthorize("hasAuthority('finprod_g1_write')")
    public ApiResult<Map<String, Object>> updateVisibility() {
        return retired();
    }

    @PatchMapping("/pools/{tierKey}/params/{paramKey}")
    @PreAuthorize("hasAnyAuthority('finprod_g1_apy_write','finprod_g1_penalty_write','finprod_g1_min_write')")
    public ApiResult<Map<String, Object>> updatePoolParam() {
        return retired();
    }

    @PatchMapping("/pools/{tierKey}/sale-status")
    @PreAuthorize("hasAuthority('finprod_g1_write')")
    public ApiResult<Map<String, Object>> updatePoolSaleStatus() {
        return retired();
    }

    @PatchMapping("/pools/{tierKey}/kill-status")
    @PreAuthorize("hasAuthority('finprod_g1_kill_toggle')")
    public ApiResult<Map<String, Object>> updatePoolKillStatus() {
        return retired();
    }

    @PatchMapping("/pools/{tierKey}/restore")
    @PreAuthorize("hasAuthority('emergency_j1_gate_resume')")
    public ApiResult<Map<String, Object>> restorePool() {
        return retired();
    }

    private ApiResult<Map<String, Object>> retired() {
        return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), OpsErrorCode.RETIRED_FEATURE.name());
    }
}
