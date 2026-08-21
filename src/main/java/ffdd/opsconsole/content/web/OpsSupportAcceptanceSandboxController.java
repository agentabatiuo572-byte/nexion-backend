package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.SupportAcceptanceSandboxProfileGuard;
import ffdd.opsconsole.content.application.SupportAcceptanceSandboxService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** Operations-side proof surface. It never reads production M inbox tables. */
@RestController
@Profile({"dev", "test"})
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/support/acceptance")
@RequiredArgsConstructor
public class OpsSupportAcceptanceSandboxController {
    private final SupportAcceptanceSandboxProfileGuard guard;
    private final SupportAcceptanceSandboxService service;

    @GetMapping("/projection")
    @PreAuthorize("hasAuthority('service_m3_read')")
    public ApiResult<Map<String, Object>> projection() {
        guard.requireAvailable();
        return ApiResult.ok(service.adminProof());
    }
    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('service_m3_read')")
    public ApiResult<List<Map<String,Object>>> conversations() { guard.requireAvailable(); return ApiResult.ok(service.adminConversations()); }
    @PostMapping("/conversations/{id}/reply")
    @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<Object> reply(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body) { return ApiResult.ok(service.adminReply(id,key,body).get("result")); }
    @PostMapping("/conversations/{id}/transfer")
    @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<Object> transfer(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body) { return ApiResult.ok(service.adminTransfer(id,key,body).get("result")); }
    @GetMapping("/tickets")
    @PreAuthorize("hasAuthority('service_m3_read')")
    public ApiResult<List<Map<String,Object>>> tickets() { guard.requireAvailable(); return ApiResult.ok(service.adminTickets()); }
    @GetMapping("/tickets/{id}")
    @PreAuthorize("hasAuthority('service_m3_read')")
    public ApiResult<Map<String,Object>> ticket(@PathVariable String id) { return ApiResult.ok(service.adminTicket(id)); }
    @GetMapping("/commands/{key}")
    @PreAuthorize("hasAuthority('service_m3_read')")
    public ApiResult<Map<String,Object>> command(@PathVariable String key) { return ApiResult.ok(service.adminCommandResult(key)); }
    @PostMapping("/tickets/{id}/reply")
    @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<Object> ticketReply(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body) { return ApiResult.ok(service.adminTicketReply(id,key,body).get("result")); }
    @PostMapping("/tickets/{id}/close")
    @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<Object> ticketClose(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body) { return ApiResult.ok(service.adminTicketClose(id,key,body).get("result")); }
}
