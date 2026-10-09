package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.Date;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

class JwtAdminIdleContractTest {
    private final JwtProperties props = new JwtProperties();
    private final JwtTokenProvider provider = new JwtTokenProvider(props);
    @Test void adminHasSameSidButNoAbsoluteExpiration() {
        var claims=provider.parse(provider.createToken(7L,"ADMIN","fixture",List.of(),"sid",Duration.ofHours(8)));
        assertThat(claims.getExpiration()).isNull();
        assertThat(claims.get("sessionId",String.class)).isEqualTo("sid");
    }
    @Test void missingAdminSidFailsClosed() {
        assertThatIllegalArgumentException().isThrownBy(()->provider.createToken(7L,"ADMIN","fixture",List.of()));
        assertThatIllegalArgumentException().isThrownBy(()->provider.createToken(7L,"ADMIN","fixture",List.of()," "));
    }
    @Test void userAndImpersonationKeepExpiration() {
        assertThat(provider.parse(provider.createUserToken(7L,"fixture",List.of(),"sid",Duration.ofMinutes(10),UserAuthEnvironment.SANDBOX)).getExpiration()).isNotNull();
        assertThat(provider.parse(provider.createImpersonationToken(7L,"fixture","sid",10)).getExpiration()).isNotNull();
    }
    @Test void stillValidLegacyAdminExpirationRemainsParseable() {
        String secret=props.getSecret();
        secret=secret.length()>=32?secret:(secret+"0".repeat(32)).substring(0,32);
        String token=Jwts.builder().subject("7").claim("subjectType","ADMIN").claim("sessionId","legacy")
            .expiration(new Date(System.currentTimeMillis()+60000)).signWith(Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
        assertThat(provider.parse(token).get("sessionId",String.class)).isEqualTo("legacy");
    }
}
