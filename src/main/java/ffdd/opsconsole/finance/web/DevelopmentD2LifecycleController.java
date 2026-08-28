package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.application.DevelopmentD2LifecycleService;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.DevelopmentD2CooldownSimulationRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev & !prod")
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/finance/withdrawals/development")
@RequiredArgsConstructor
public class DevelopmentD2LifecycleController {
    private final DevelopmentD2LifecycleService lifecycleService;

    @GetMapping("/capabilities")
    @PreAuthorize("hasAuthority('finance_d2_read')")
    public ApiResult<Map<String, Object>> capabilities() {
        return ApiResult.ok(lifecycleService.capabilities());
    }

    @PostMapping("/{withdrawalNo}/simulate-cooldown-expiry")
    @PreAuthorize("hasAuthority('finance_d2_withdrawal_approve')")
    public ApiResult<WithdrawalOrderView> simulateCooldownExpiry(
            @PathVariable String withdrawalNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) DevelopmentD2CooldownSimulationRequest request) {
        return lifecycleService.simulateCooldownExpiry(withdrawalNo, idempotencyKey, request);
    }
}
