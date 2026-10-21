package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.*;
import ffdd.opsconsole.auth.application.OAuthProviderFlowService.Proof;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/users/oauth/provider")
@RequiredArgsConstructor
public class OAuthProviderController {
    private final OAuthProviderFlowService flows;
    private final AppUserRefreshCookieService cookies;
    private final OAuthProviderClient providers;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public record StartRequest(String provider, String intent, String sponsorCode) {}
    public record CompleteRequest(String provider, String flowId, String flowKey, String receipt, String challengeNo, String code, String captchaTicket) {
        @Override public String toString() { return "CompleteRequest[REDACTED]"; }
    }
    public record LinkRequest(String flowId, String flowKey, String receipt, String password) {
        @Override public String toString() { return "LinkRequest[REDACTED]"; }
    }

    @GetMapping("/available") public ApiResult<List<String>> available() { return ApiResult.ok(flows.available()); }
    @GetMapping("/linked") public ApiResult<List<String>> linked(Authentication authentication) {
        return ApiResult.ok(flows.linked(userId(authentication)));
    }

    @PostMapping("/start")
    public ApiResult<OAuthProviderFlowService.Start> start(@RequestBody StartRequest request,
            Authentication authentication, HttpServletRequest http) {
        if (!"LOGIN".equals(request.intent()) && !"LINK".equals(request.intent()))
            throw new BizException(422, "OAUTH_REQUEST_INVALID");
        Long userId = "LINK".equals(request.intent()) ? userId(authentication) : null;
        return ApiResult.ok(flows.start(request.provider(), userId, http.getRemoteAddr(), request.sponsorCode()));
    }

    @GetMapping("/authorize/{state}")
    public ResponseEntity<Void> authorize(@PathVariable String state) {
        var start = flows.openBrowser(state);
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, start.location())
                .header(HttpHeaders.SET_COOKIE, browserCookie(state, start.browserKey(), 600))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("Referrer-Policy", "no-referrer").build();
    }

    @RequestMapping(value = "/callback/{provider}", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> callback(@PathVariable String provider, @RequestParam(required = false) String state,
            @RequestParam(required = false) String code, @RequestParam(required = false) String error,
            HttpServletRequest request) {
        String key = null;
        if (state != null && state.matches("[A-Za-z0-9_-]{43}") && request.getCookies() != null) {
            for (var cookie : request.getCookies())
                if (cookie.getName().equals(cookieName(state))) key = cookie.getValue();
        }
        OAuthProviderFlowService.Callback result;
        try { result = flows.callback(provider, state, key, code, error); }
        catch (BizException exception) { result = null; }
        boolean success = result != null;
        String text = success ? "Authorization complete. Return to UVEL to continue."
                : "Authorization was not completed. Return to UVEL and try again.";
        String nonce = java.util.UUID.randomUUID().toString().replace("-", "");
        String continuation = "";
        if (success) {
            try {
                String payload = json.writeValueAsString(Map.of("type", "uvel-oauth",
                        "flowId", result.flowId(), "receipt", result.receipt()));
                String origin = json.writeValueAsString(providers.h5Origin());
                continuation = "<p><a href=\"uvel://oauth?flowId=" + result.flowId() + "&amp;receipt=" + result.receipt()
                        + "\">Return to UVEL App</a></p><script nonce=\"" + nonce
                        + "\">if(window.opener){window.opener.postMessage(" + payload + "," + origin + ");}</script>";
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException("OAUTH_CALLBACK_RENDER_FAILED");
            }
        }
        var response = ResponseEntity.status(success ? 200 : 400).contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("Referrer-Policy", "no-referrer")
                .header("Cross-Origin-Opener-Policy", "unsafe-none")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce
                        + "'; frame-ancestors 'none'; base-uri 'none'");
        if (state != null && state.matches("[A-Za-z0-9_-]{43}"))
            response.header(HttpHeaders.SET_COOKIE, browserCookie(state, "", 0));
        return response.body("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>UVEL</title><h1>UVEL</h1><p>" + text + "</p>" + continuation + "</html>");
    }

    @PostMapping("/status")
    public ApiResult<OAuthProviderFlowService.Status> status(@RequestBody Proof proof) {
        return ApiResult.ok(flows.status(proof));
    }

    @PostMapping("/complete")
    public ApiResult<UserLoginResponse> complete(@RequestBody CompleteRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        AppSessionSurface group = AppSessionSurface.from(cookies.cookieMode(request),
                request.getHeader(AppSessionSurface.APP_HEADER));
        String capability = request.getHeader("X-NexGrid-Execution-Supported");
        if (!"true".equals(capability) && !"false".equals(capability))
            throw new BizException(422, "LOGIN_EXECUTION_CONTEXT_INVALID");
        var context = new AppLoginContext(group, request.getHeader("X-NexGrid-Installation-Id"),
                Boolean.parseBoolean(capability), request.getHeader("X-NexGrid-Login-Attempt-Id"),
                request.getHeader("X-NexGrid-Login-Recovery-Key"), "OAUTH", body.provider() + ":" + body.flowId());
        context.validate();
        return cookies.issue(flows.login(new Proof(body.flowId(), body.flowKey(), body.receipt()), request.getRemoteAddr(),
                context, body.challengeNo(), body.code(), body.captchaTicket()), request, response);
    }

    @PostMapping("/link")
    public ApiResult<Void> link(@RequestBody LinkRequest request, Authentication authentication, HttpServletRequest http) {
        return flows.link(new Proof(request.flowId(), request.flowKey(), request.receipt()),
                userId(authentication), request.password(), http.getRemoteAddr(), authentication);
    }

    private long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(details.get("subjectType"))
                || !(details.get("sessionId") instanceof String sessionId) || sessionId.isBlank())
            throw new BizException(401, "USER_AUTH_REQUIRED");
        try {
            long id = Long.parseLong(authentication.getName());
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw new BizException(401, "USER_AUTH_REQUIRED");
    }

    private static String cookieName(String state) { return "__Host-uvel-oauth-" + state; }
    private static String browserCookie(String state, String key, int seconds) {
        return ResponseCookie.from(cookieName(state), key).httpOnly(true).secure(true)
                .path("/").sameSite("None").maxAge(seconds).build().toString();
    }
}
