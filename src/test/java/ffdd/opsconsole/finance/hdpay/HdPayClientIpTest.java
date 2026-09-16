package ffdd.opsconsole.finance.hdpay;

import ffdd.opsconsole.shared.security.GatewaySecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class HdPayClientIpTest {
    final GatewaySecurityProperties gateway = new GatewaySecurityProperties();
    @Test void untrustedPeerCannotForgeEitherHeader() {
        var request = new MockHttpServletRequest(); request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Nexion-Client-IP", "198.51.100.1");
        request.addHeader("X-Forwarded-For", "198.51.100.2, 127.0.0.1");
        assertEquals("203.0.113.7", HdPayClientIp.resolve(request, gateway));
    }
    @Test void trustedProxyUsesValidatedOriginalIpAndFallsBackToPeer() {
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Nexion-Client-IP", "2001:db8::12");
        assertEquals("2001:db8::12", HdPayClientIp.resolve(request, gateway));
        request.removeHeader("X-Nexion-Client-IP"); request.addHeader("X-Nexion-Client-IP", "999.1.2.3");
        request.addHeader("X-Forwarded-For", "203.0.113.8, 198.51.100.1");
        assertEquals("203.0.113.8", HdPayClientIp.resolve(request, gateway));
        request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For", "attacker.example");
        assertEquals("127.0.0.1", HdPayClientIp.resolve(request, gateway));
    }
    @Test void invalidLiteralsNeverBecomeProviderRequestParameters() {
        for (String ip : new String[]{null,"", "example.com", "1.2.3.999", ":::", "fe80::1%eth0", "1.2.3.4&x=y"})
            assertThrows(HdPayGatewayException.class, () -> HdPayClientIp.requireLiteral(ip));
        for (String ip : new String[]{"203.0.113.1", "::1", "2001:db8::1", "::ffff:192.0.2.1"})
            assertEquals(ip, HdPayClientIp.requireLiteral(ip));
    }
}
