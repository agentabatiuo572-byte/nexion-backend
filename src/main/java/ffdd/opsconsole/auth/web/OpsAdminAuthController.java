package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.OpsAdminAuthService;
import ffdd.opsconsole.auth.dto.AdminLoginRequest;
import ffdd.opsconsole.auth.dto.AdminLoginResponse;
import ffdd.opsconsole.auth.dto.AdminMfaVerifyRequest;
import ffdd.opsconsole.auth.dto.AdminPasswordChangeRequest;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.security.GatewaySecurityProperties;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/auth")
@RequiredArgsConstructor
public class OpsAdminAuthController {
    private final OpsAdminAuthService authService;
    private final GatewaySecurityProperties gatewaySecurity;

    @PostMapping("/login")
    public ApiResult<AdminLoginResponse> login(
            @RequestBody(required = false) AdminLoginRequest request,
            HttpServletRequest httpRequest) {
        return authService.login(request, clientIp(httpRequest), userAgent(httpRequest));
    }

    @PostMapping("/mfa/verify")
    public ApiResult<AdminLoginResponse> verifyMfa(
            @RequestBody(required = false) AdminMfaVerifyRequest request,
            HttpServletRequest httpRequest) {
        return authService.verifyMfa(request, clientIp(httpRequest), userAgent(httpRequest));
    }

    @PostMapping("/logout")
    public ApiResult<Void> logout(Authentication authentication) {
        return authService.logout(authentication);
    }

    @GetMapping("/me")
    public ApiResult<AdminLoginResponse.AdminSession> me(Authentication authentication) {
        return authService.current(authentication);
    }

    @PostMapping("/password/change")
    public ApiResult<AdminLoginResponse> changePassword(
            Authentication authentication,
            @RequestBody(required = false) AdminPasswordChangeRequest request,
            HttpServletRequest httpRequest) {
        return authService.changePassword(
                authentication,
                request,
                clientIp(httpRequest),
                userAgent(httpRequest));
    }

    private String clientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!gatewaySecurity.isTrustedProxy(remoteAddress)) {
            return remoteAddress;
        }
        String clientAddress = validIpLiteral(request.getHeader("X-Nexion-Client-IP"));
        if (clientAddress == null) {
            String forwarded = request.getHeader("X-Forwarded-For");
            clientAddress = validIpLiteral(forwarded == null ? null : forwarded.split(",", 2)[0]);
        }
        return clientAddress == null ? remoteAddress : clientAddress;
    }

    private String validIpLiteral(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.length() > 64 || !candidate.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        return candidate;
    }

    private String userAgent(HttpServletRequest request) {
        String value = request.getHeader("User-Agent");
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim();
        return candidate.length() <= 512 ? candidate : candidate.substring(0, 512);
    }
}
