package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.SupportInviteCodeService;
import ffdd.opsconsole.content.dto.SupportGroupManagementViews.InviteCodeView;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/content/support-agents")
@RequiredArgsConstructor
public class OpsSupportInviteCodeController {
    private final SupportInviteCodeService invites;
    public record Issue(Long expectedVersion) {}
    public record Revoke(Long expectedVersion,String reason) {}

    @GetMapping("/self/invite-code")
    public ApiResult<InviteCodeView> self() {return ApiResult.ok(invites.self());}

    @PostMapping("/self/invite-code")
    public ApiResult<InviteCodeView> issue(@RequestHeader("Idempotency-Key") String key,@RequestBody Issue request) {
        return ApiResult.ok(invites.change(null,request.expectedVersion(),null,key));
    }

    @GetMapping("/{adminId}/invite-code")
    @PreAuthorize("hasAuthority('service_m1_read')")
    public ApiResult<InviteCodeView> managed(@PathVariable long adminId) {return ApiResult.ok(invites.managed(adminId));}

    @PostMapping("/{adminId}/invite-code/revoke")
    @PreAuthorize("hasAuthority('service_m1_write')")
    public ApiResult<InviteCodeView> revoke(@PathVariable long adminId,@RequestHeader("Idempotency-Key") String key,@RequestBody Revoke request) {
        return ApiResult.ok(invites.change(adminId,request.expectedVersion(),request.reason(),key));
    }
}
