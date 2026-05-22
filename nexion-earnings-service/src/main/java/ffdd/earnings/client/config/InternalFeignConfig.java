package ffdd.earnings.client.config;

import feign.RequestInterceptor;
import ffdd.common.security.AuthHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class InternalFeignConfig {
    @Bean
    public RequestInterceptor internalServiceAuthInterceptor(
            @Value("${nexion.gateway.internal-secret:nexion-local-gateway-secret}") String gatewaySecret) {
        return template -> {
            template.header(AuthHeaders.GATEWAY_SECRET, gatewaySecret);
            template.header(AuthHeaders.SUBJECT_ID, "0");
            template.header(AuthHeaders.SUBJECT_TYPE, "SERVICE");
            template.header(AuthHeaders.USERNAME, "nexion-earnings-service");
            template.header(AuthHeaders.AUTHORITIES, "PERM_WALLET_READ");
        };
    }
}
