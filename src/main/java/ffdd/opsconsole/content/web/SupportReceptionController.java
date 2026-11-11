package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.application.SupportSessionService;
import ffdd.opsconsole.content.application.SupportWelcomeService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/content")
@RequiredArgsConstructor
public class SupportReceptionController {
    private final SupportWelcomeService welcome;
    private final SupportSessionService sessions;
    private final ProductionSupportPathGuard production;

    @GetMapping("/support-agents/welcome-message")
    @PreAuthorize("hasAuthority('service_m1_read')")
    public ApiResult<SupportWelcomeService.Config> welcome() {
        production.requireOpsWriteAllowed();
        return ApiResult.ok(welcome.config());
    }

    @PutMapping("/support-agents/welcome-message")
    @PreAuthorize("hasAuthority('service_m1_write')")
    public ApiResult<SupportWelcomeService.Config> update(@RequestHeader("Idempotency-Key") String key,
                                                         @RequestBody SupportWelcomeService.Update request) {
        production.requireOpsWriteAllowed();
        return welcome.update(key,request);
    }

    @GetMapping("/support-workbench/customers/{customer}/session-hold")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<SupportSessionService.State> hold(@PathVariable long customer) {
        return ApiResult.ok(sessions.state(customer));
    }

    @PostMapping("/support-workbench/customers/{customer}/session-hold/end")
    @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<?> end(@PathVariable long customer,@RequestHeader("Idempotency-Key") String key,
                            @RequestBody(required=false) SupportSessionService.End body) {
        try {
            return sessions.end(customer,key,body);
        } catch(BizException rejected) {
            sessions.rejected(customer,"M3_SESSION_END",rejected.getMessage());
            throw rejected;
        }
    }
}
