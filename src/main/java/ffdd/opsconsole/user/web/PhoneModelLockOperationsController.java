package ffdd.opsconsole.user.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.user.application.PhoneModelLockOperationsService;
import ffdd.opsconsole.user.dto.PhoneReplacementCommands.Confirm;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class PhoneModelLockOperationsController {
    private final PhoneModelLockOperationsService service;

    @GetMapping("/users/profiles/{userId}/phone-model-locks")
    @PreAuthorize("hasAuthority('user_c5_read')")
    public ApiResult<?> locks(@PathVariable long userId) { return ApiResult.ok(service.locks(userId)); }

    @PostMapping("/users/profiles/{userId}/phone-model-locks/{installationId}/release")
    @PreAuthorize("hasAuthority('user_c5_phone_replacement_exception_grant')")
    public ApiResult<?> release(@PathVariable long userId, @PathVariable String installationId,
                                @RequestHeader("Idempotency-Key") String key, @RequestBody Confirm body) {
        return ApiResult.ok(service.release(userId, installationId, body, key));
    }
}
