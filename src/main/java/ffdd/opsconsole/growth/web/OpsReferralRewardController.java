package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.growth.application.OpsReferralRewardService;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.dto.ReferralSettlementRunRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/growth/referral-rewards")
@RequiredArgsConstructor
public class OpsReferralRewardController {
    private final OpsReferralRewardService service;

    @GetMapping
    @PreAuthorize("hasAuthority('growth_h8_read')")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(service.overview());
    }

    @PatchMapping("/params/{paramKey}")
    @PreAuthorize("hasAuthority('growth_h8_write')")
    public ApiResult<Map<String, Object>> updateParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String key,
            @PathVariable String paramKey, @RequestBody GrowthConfigUpdateRequest request) {
        return ApiResult.ok(service.updateParam(paramKey, key, request));
    }

    @PostMapping("/settlements/run")
    @PreAuthorize("hasAuthority('growth_h8_settle')")
    public ApiResult<Map<String, Object>> runSettlements(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String key,
            @RequestBody ReferralSettlementRunRequest request) {
        return ApiResult.ok(service.runSettlements(key, request));
    }
}
