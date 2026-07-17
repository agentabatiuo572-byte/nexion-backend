package ffdd.opsconsole.auth.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.auth.application.OpsAdminAuthService;
import ffdd.opsconsole.auth.dto.AdminLoginRequest;
import ffdd.opsconsole.auth.dto.AdminMfaVerifyRequest;
import ffdd.opsconsole.auth.dto.AdminPasswordChangeRequest;
import ffdd.opsconsole.shared.security.GatewaySecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

class OpsAdminAuthControllerTest {

    private final OpsAdminAuthService authService = mock(OpsAdminAuthService.class);
    private final GatewaySecurityProperties gatewaySecurity = new GatewaySecurityProperties();
    private final OpsAdminAuthController controller = new OpsAdminAuthController(authService, gatewaySecurity);

    @Test
    void loginForwardsTrustedSessionMetadataForTemporaryBypassSessions() {
        AdminLoginRequest body = new AdminLoginRequest("superadmin", "secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/auth/login");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Nexion-Client-IP", "203.0.113.24");
        request.addHeader("User-Agent", "Mozilla/5.0 Chrome/126 Windows NT 10.0");

        controller.login(body, request);

        verify(authService).login(body, "203.0.113.24", "Mozilla/5.0 Chrome/126 Windows NT 10.0");
    }

    @Test
    void verifyMfaAcceptsClientMetadataOnlyFromATrustedProxy() {
        AdminMfaVerifyRequest body = new AdminMfaVerifyRequest("challenge", "123456");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/auth/mfa/verify");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Nexion-Client-IP", "203.0.113.24");
        request.addHeader("User-Agent", "Mozilla/5.0 Chrome/126 Windows NT 10.0");

        controller.verifyMfa(body, request);

        verify(authService).verifyMfa(body, "203.0.113.24", "Mozilla/5.0 Chrome/126 Windows NT 10.0");
    }

    @Test
    void verifyMfaIgnoresForwardedClientIpFromAnUntrustedPeer() {
        AdminMfaVerifyRequest body = new AdminMfaVerifyRequest("challenge", "123456");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/auth/mfa/verify");
        request.setRemoteAddr("198.51.100.8");
        request.addHeader("X-Nexion-Client-IP", "203.0.113.24");

        controller.verifyMfa(body, request);

        verify(authService).verifyMfa(body, "198.51.100.8", null);
    }

    @Test
    void firstPasswordChangeUsesTheSameTrustedSessionMetadata() {
        Authentication authentication = mock(Authentication.class);
        AdminPasswordChangeRequest body = new AdminPasswordChangeRequest("old", "new");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/auth/password/change");
        request.setRemoteAddr("::1");
        request.addHeader("X-Nexion-Client-IP", "2001:db8::24");
        request.addHeader("User-Agent", "Mozilla/5.0 Firefox/128 Mac OS X");

        controller.changePassword(authentication, body, request);

        verify(authService).changePassword(
                authentication,
                body,
                "2001:db8::24",
                "Mozilla/5.0 Firefox/128 Mac OS X");
    }
}
