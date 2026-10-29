package ffdd.opsconsole.content.push;

import ffdd.opsconsole.shared.api.ApiResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
public class NativePushController {
    private final NativePushService service;
    public NativePushController(NativePushService service) { this.service = service; }

    @PutMapping("/push-installations/{installationId}")
    public ApiResult<Void> register(Authentication auth, @PathVariable String installationId,
            @RequestHeader(value="X-Push-Binding-Token",required=false) String token,
            @RequestBody NativePushService.Registration request) {
        service.register(auth, installationId, token, request);
        return ApiResult.ok();
    }

    @DeleteMapping("/push-installations/{installationId}")
    public ApiResult<Void> remove(Authentication auth, @PathVariable String installationId,
            @RequestHeader(value="X-Push-Binding-Token",required=false) String token) {
        service.remove(auth, installationId, token);
        return ApiResult.ok();
    }

    @GetMapping("/push-notifications/{notificationId}")
    public ApiResult<NativePushService.Route> resolve(Authentication auth, @PathVariable long notificationId) {
        return ApiResult.ok(service.resolve(auth, notificationId));
    }

    public record Opened(String installationId) { }
    @PostMapping("/push-notifications/{notificationId}/opened")
    public ApiResult<Integer> opened(Authentication auth, @PathVariable long notificationId, @RequestBody Opened body) {
        return ApiResult.ok(service.opened(auth, notificationId, body == null ? null : body.installationId()));
    }
}
