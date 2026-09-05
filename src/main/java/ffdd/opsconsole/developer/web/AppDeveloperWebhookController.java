package ffdd.opsconsole.developer.web;

import ffdd.opsconsole.developer.application.AppDeveloperWebhookService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/app/developer/webhooks")
@RequiredArgsConstructor
public class AppDeveloperWebhookController {
    private final AppDeveloperWebhookService service;
    @GetMapping public ApiResult<List<Map<String,Object>>> list(Authentication auth) { Long id = userId(auth); return id == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.list(id); }
    @PostMapping public ApiResult<Map<String,Object>> create(@RequestBody Request request, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) { Long id = userId(auth); return id == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.create(id, request == null ? null : request.name(), request == null ? null : request.url(), eventsJson(request), key); }
    @PutMapping("/{id}") public ApiResult<Map<String,Object>> update(@PathVariable Long id, @RequestBody Request request, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) { Long uid = userId(auth); return uid == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.update(uid, id, request == null ? null : request.name(), request == null ? null : request.url(), eventsJson(request), request != null && request.rotateSecret(), key); }
    @DeleteMapping("/{id}") public ApiResult<Void> delete(@PathVariable Long id, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) { Long uid = userId(auth); return uid == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.delete(uid, id, key); }
    @PostMapping("/{id}/enable") public ApiResult<Map<String,Object>> enable(@PathVariable Long id, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) { Long uid = userId(auth); return uid == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.setEnabled(uid, id, true, key); }
    @PostMapping("/{id}/disable") public ApiResult<Map<String,Object>> disable(@PathVariable Long id, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) { Long uid = userId(auth); return uid == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.setEnabled(uid, id, false, key); }
    @GetMapping("/{id}/deliveries") public ApiResult<Map<String,Object>> deliveries(@PathVariable Long id, @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "50") int limit, Authentication auth) { Long uid = userId(auth); return uid == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.deliveryLog(uid, id, beforeId, limit); }
    @PostMapping("/{id}/rotate-secret") public ApiResult<Map<String,Object>> rotateSecret(@PathVariable Long id, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) { Long uid = userId(auth); return uid == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.rotateSecret(uid, id, key); }
    private String eventsJson(Request request) { if (request == null) return null; if (request.events() != null) { try { return new ObjectMapper().writeValueAsString(request.events()); } catch (JsonProcessingException ignored) { return null; } } return request.eventsJson(); }
    private Long userId(Authentication auth) { if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null || !(auth.getDetails() instanceof Map<?,?> details) || !"USER".equals(String.valueOf(details.get("subjectType")))) return null; try { long value = Long.parseLong(String.valueOf(auth.getPrincipal())); return value > 0 ? value : null; } catch (NumberFormatException ex) { return null; } }
    public record Request(String name, String url, String eventsJson, List<String> events, boolean rotateSecret) { }
}
