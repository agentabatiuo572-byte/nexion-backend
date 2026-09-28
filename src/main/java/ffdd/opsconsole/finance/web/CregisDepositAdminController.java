package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.cregis.CregisDepositService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/finance/cregis")
@RequiredArgsConstructor
public class CregisDepositAdminController {
    private final CregisDepositService deposits;

    @GetMapping("/exceptions")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String, Object>> exceptions() {
        return ApiResult.ok(deposits.exceptions());
    }

    @PostMapping("/addresses/provision")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> provisionAddress() {
        return ApiResult.ok(deposits.provisionPoolAddress());
    }
}
