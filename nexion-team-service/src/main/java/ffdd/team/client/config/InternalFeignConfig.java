package ffdd.team.client.config;

import feign.RequestInterceptor;
import ffdd.common.security.AuthHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class InternalFeignConfig {
    @Value("${nexion.gateway.internal-secret:nexion-local-gateway-secret}")
    private String gatewaySecret;

    @Bean
    public RequestInterceptor internalRequestInterceptor() {
        return template -> {
            template.header(AuthHeaders.GATEWAY_SECRET, gatewaySecret);
            template.header(AuthHeaders.SUBJECT_ID, "0");
            template.header(AuthHeaders.SUBJECT_TYPE, "SERVICE");
            template.header(AuthHeaders.USERNAME, "nexion-team-service");
            template.header(AuthHeaders.AUTHORITIES, "PERM_TEAM_READ,PERM_TEAM_WRITE,PERM_COMMERCE_READ");
        };
    }
}
