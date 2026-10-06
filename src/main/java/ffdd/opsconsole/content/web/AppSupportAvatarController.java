package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.SupportAdminAvatarService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/app/support/advisor/avatar")
@RequiredArgsConstructor
public class AppSupportAvatarController {
    private final SupportAdminAvatarService service;
    @GetMapping("/{adminId}")
    public ResponseEntity<byte[]> content(@PathVariable Long adminId){return OpsSupportAdminAvatarController.image(service.content(adminId,true));}
}
