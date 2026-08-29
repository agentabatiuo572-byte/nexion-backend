package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.application.PayoutVndSandboxService;
import ffdd.opsconsole.finance.dto.PayoutVndSandboxCallbackRequest;
import ffdd.opsconsole.finance.dto.PayoutVndSandboxCreateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Automated-test VND payout fixture. Deployable dev/prod runtimes do not register these routes. */
@RestController
@Profile("test")
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/finance/payout-vnd/sandbox")
@RequiredArgsConstructor
public class OpsPayoutVndSandboxController {
    private final PayoutVndSandboxService sandbox;

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('finance_d7_read')")
    public ApiResult<Map<String, Object>> orders(@RequestParam Long userId) {
        return sandbox.orders(userId);
    }

    @PostMapping("/orders")
    @PreAuthorize("hasAuthority('finance_d7_manage')")
    public ApiResult<Map<String, Object>> create(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) PayoutVndSandboxCreateRequest request) {
        return sandbox.create(idempotencyKey, request);
    }

    @PostMapping("/callbacks")
    @PreAuthorize("hasAuthority('finance_d7_manage')")
    public ApiResult<Map<String, Object>> callback(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) PayoutVndSandboxCallbackRequest request) {
        return sandbox.callback(idempotencyKey, request);
    }
}
