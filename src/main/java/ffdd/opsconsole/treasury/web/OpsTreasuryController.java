package ffdd.opsconsole.treasury.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import ffdd.opsconsole.treasury.dto.TreasuryInjectionRequest;
import ffdd.opsconsole.treasury.dto.TreasuryScopeRequest;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/treasury")
public class OpsTreasuryController {
    private final OpsTreasuryService treasuryService;

    public OpsTreasuryController(OpsTreasuryService treasuryService) {
        this.treasuryService = treasuryService;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('PERM_TREASURY_READ')")
    public ApiResult<Map<String, Object>> overview(@RequestParam(defaultValue = "7") int days) {
        return treasuryService.overview(days);
    }

    @GetMapping("/dual-ledger")
    @PreAuthorize("hasAuthority('PERM_TREASURY_READ')")
    public ApiResult<Map<String, Object>> dualLedger() {
        return treasuryService.dualLedger();
    }

    @PostMapping("/injections")
    @PreAuthorize("hasAuthority('PERM_TREASURY_WRITE')")
    public ApiResult<Map<String, Object>> createInjection(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TreasuryInjectionRequest request) {
        return treasuryService.createInjection(idempotencyKey, request);
    }

    @PatchMapping("/dual-ledger/scope")
    @PreAuthorize("hasAuthority('PERM_TREASURY_WRITE')")
    public ApiResult<Map<String, Object>> updateScope(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TreasuryScopeRequest request) {
        return treasuryService.updateScope(idempotencyKey, request);
    }
}
