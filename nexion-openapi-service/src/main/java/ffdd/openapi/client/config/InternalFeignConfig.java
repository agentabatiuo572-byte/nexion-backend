package ffdd.openapi.client.config;

import ffdd.common.security.AuthHeaders;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class InternalFeignConfig {
    @Bean
    public RequestInterceptor internalGatewayHeaders(
            @Value("${nexion.gateway.internal-secret:nexion-local-gateway-secret}") String gatewaySecret) {
        return template -> {
            template.header(AuthHeaders.GATEWAY_SECRET, gatewaySecret);
            template.header(AuthHeaders.SUBJECT_ID, "0");
            template.header(AuthHeaders.SUBJECT_TYPE, "OPENAPI");
            template.header(AuthHeaders.USERNAME, "nexion-openapi-service");
            template.header(AuthHeaders.AUTHORITIES, "ROLE_OPENAPI");
        };
    }
}
