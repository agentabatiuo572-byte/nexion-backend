package ffdd.bff.client.config;

import feign.RequestInterceptor;
import ffdd.common.security.AuthHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class OpsFeignConfig {
    @Bean
    public RequestInterceptor internalOpsGatewayHeaders(
            @Value("${nexion.gateway.internal-secret:nexion-local-gateway-secret}") String gatewaySecret) {
        return template -> {
            template.header(AuthHeaders.GATEWAY_SECRET, gatewaySecret);
            template.header(AuthHeaders.SUBJECT_ID, "0");
            template.header(AuthHeaders.SUBJECT_TYPE, "BFF_OPS");
            template.header(AuthHeaders.USERNAME, "nexion-bff-service");
            template.header(AuthHeaders.AUTHORITIES,
                    "ROLE_BFF,PERM_AUDIT_READ,PERM_COMMERCE_READ,PERM_WALLET_READ,PERM_COMPLIANCE_READ,PERM_OPENAPI_ADMIN");
        };
    }
}
