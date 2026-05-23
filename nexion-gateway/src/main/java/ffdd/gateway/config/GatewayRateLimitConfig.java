package ffdd.gateway.config;

import ffdd.gateway.sentinel.GatewaySentinelProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GatewaySentinelProperties.class)
public class GatewayRateLimitConfig {
    @Bean
    public Clock gatewayRateLimitClock() {
        return Clock.systemUTC();
    }
}
