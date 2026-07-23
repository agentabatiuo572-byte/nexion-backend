package ffdd.opsconsole.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {
    private final JwtProperties properties;

    public String createToken(Long subjectId, String subjectType, String username, Collection<String> authorities) {
        return createToken(subjectId, subjectType, username, authorities, null);
    }

    public String createToken(Long subjectId, String subjectType, String username, Collection<String> authorities, String sessionId) {
        return createToken(subjectId, subjectType, username, authorities, sessionId, expiration());
    }

    /** Issues a token with a server-resolved policy TTL instead of the static bootstrap default. */
    public String createToken(
            Long subjectId,
            String subjectType,
            String username,
            Collection<String> authorities,
            String sessionId,
            Duration ttl) {
        Duration safeTtl = ttl == null || ttl.isZero() || ttl.isNegative() ? expiration() : ttl;
        return createTokenInternal(subjectId, subjectType, username, authorities, sessionId, safeTtl);
    }

    public String createImpersonationToken(Long userId, String username, String sessionNo, int ttlMinutes) {
        return createTokenInternal(
                userId,
                "IMPERSONATION",
                username,
                java.util.List.of("impersonate_readonly"),
                sessionNo,
                Duration.ofMinutes(ttlMinutes));
    }

    private String createTokenInternal(
            Long subjectId,
            String subjectType,
            String username,
            Collection<String> authorities,
            String sessionId,
            Duration ttl) {
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + ttl.toMillis());
        var builder = Jwts.builder()
                .subject(String.valueOf(subjectId))
                .claim("subjectType", subjectType)
                .claim("username", username)
                .claim("authorities", authorities)
                .issuedAt(now)
                .expiration(expiresAt);
        if (sessionId != null && !sessionId.isBlank()) {
            builder.claim("sessionId", sessionId);
        }
        return builder.signWith(secretKey()).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(secretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(padSecret(properties.getSecret()).getBytes(StandardCharsets.UTF_8));
    }

    private Duration expiration() {
        return Duration.ofMinutes(properties.getTtlMinutes());
    }

    private String padSecret(String secret) {
        if (secret.length() >= 32) {
            return secret;
        }
        return (secret + "0".repeat(32)).substring(0, 32);
    }
}
