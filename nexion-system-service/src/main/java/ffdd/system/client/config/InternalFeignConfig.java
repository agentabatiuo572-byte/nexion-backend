package ffdd.system.client.config;

import feign.RequestInterceptor;
import ffdd.common.security.AuthHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class InternalFeignConfig {
    @Bean
    public RequestInterceptor internalRequestInterceptor(
            @Value("${nexion.gateway.internal-secret:nexion-local-gateway-secret}") String gatewaySecret) {
        return template -> {
            template.header(AuthHeaders.GATEWAY_SECRET, gatewaySecret);
            template.header(AuthHeaders.SUBJECT_ID, "0");
            template.header(AuthHeaders.SUBJECT_TYPE, "SERVICE");
            template.header(AuthHeaders.USERNAME, "nexion-system-service");
            template.header(AuthHeaders.AUTHORITIES, "PERM_SYSTEM_READ,PERM_SYSTEM_WRITE,PERM_NOTIFICATION_WRITE");
        };
    }
}
