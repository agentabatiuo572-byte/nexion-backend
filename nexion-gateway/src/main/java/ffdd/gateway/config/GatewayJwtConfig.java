package ffdd.gateway.config;

import ffdd.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayJwtConfig {
    @Bean
    public JwtTokenProvider jwtTokenProvider(
            @Value("${nexion.jwt.secret:nexion-development-secret-key-change-me-please}") String secret,
            @Value("${nexion.jwt.ttl-minutes:1440}") long expirationMinutes) {
        return new JwtTokenProvider(secret, expirationMinutes);
    }
}
