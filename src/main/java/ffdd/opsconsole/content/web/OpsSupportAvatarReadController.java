package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.SupportAdminAvatarService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/content/support-agents")
@RequiredArgsConstructor
public class OpsSupportAvatarReadController {
    private final SupportAdminAvatarService service;

    @GetMapping("/{adminId}/avatar")
    @PreAuthorize("hasAuthority('service_m1_read') or hasAuthority('service_m3_read')")
    public ResponseEntity<byte[]> content(@PathVariable Long adminId,@RequestParam(required=false) Long customerId) {
        return OpsSupportAdminAvatarController.image(service.supportContent(adminId,customerId));
    }
}
