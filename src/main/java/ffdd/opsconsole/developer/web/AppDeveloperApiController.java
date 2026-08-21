package ffdd.opsconsole.developer.web;

import ffdd.opsconsole.developer.application.AppDeveloperApiService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/app/developer/api-keys")
@RequiredArgsConstructor
public class AppDeveloperApiController {
    private final AppDeveloperApiService service;
    @GetMapping public ApiResult<List<Map<String,Object>>> list(Authentication auth) { Long id = userId(auth); return id == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.listKeys(id); }
    @PostMapping public ApiResult<Map<String,Object>> create(@RequestBody Request request, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) { Long id = userId(auth); return id == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.createKey(id, request == null ? null : request.name(), key); }
    @DeleteMapping("/{id}") public ApiResult<Map<String,Object>> revoke(@PathVariable Long id, Authentication auth) { Long uid = userId(auth); return uid == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.revoke(uid, id); }
    @PostMapping("/{id}/revoke") public ApiResult<Map<String,Object>> revokePost(@PathVariable Long id, Authentication auth) { Long uid = userId(auth); return uid == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.revoke(uid, id); }
    private Long userId(Authentication auth) { if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null || !(auth.getDetails() instanceof Map<?,?> details) || !"USER".equals(String.valueOf(details.get("subjectType")))) return null; try { long value = Long.parseLong(String.valueOf(auth.getPrincipal())); return value > 0 ? value : null; } catch (NumberFormatException ex) { return null; } }
    public record Request(String name) { }
}
