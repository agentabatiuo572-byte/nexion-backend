package ffdd.opsconsole.finance.cregis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class CregisGatewayRouter {
    private final CregisProperties properties;
    private final ObjectMapper objectMapper;
    private HttpCregisGateway providerGateway;

    public CregisProperties.Mode mode() {
        return properties.getMode();
    }

    public boolean payoutEnabled() {
        return properties.getMode() == CregisProperties.Mode.LOCAL_SANDBOX
                || properties.getMode() == CregisProperties.Mode.PROVIDER && properties.isPayoutEnabled();
    }

    public synchronized CregisGateway provider() {
        if (properties.getMode() != CregisProperties.Mode.PROVIDER) {
            throw new CregisGatewayException(
                    CregisGatewayException.Kind.CONFIGURATION, "CREGIS_PROVIDER_DISABLED");
        }
        if (providerGateway == null) {
            providerGateway = new HttpCregisGateway(properties, objectMapper);
        }
        return providerGateway;
    }

    public CregisGateway isolatedLocalSandbox() {
        if (properties.getMode() != CregisProperties.Mode.LOCAL_SANDBOX) {
            throw new CregisGatewayException(
                    CregisGatewayException.Kind.CONFIGURATION, "CREGIS_LOCAL_SANDBOX_DISABLED");
        }
        return new LocalCregisSandboxGateway();
    }

    public String payoutCallbackUrl() {
        if (properties.getMode() == CregisProperties.Mode.LOCAL_SANDBOX) {
            return "https://sandbox.invalid/cregis/payout";
        }
        if (properties.getMode() != CregisProperties.Mode.PROVIDER
                || !properties.isPayoutEnabled()
                || properties.getCallbackBaseUrl() == null || properties.getCallbackBaseUrl().isBlank()) {
            throw new CregisGatewayException(
                    CregisGatewayException.Kind.CONFIGURATION, "CREGIS_CALLBACK_NOT_CONFIGURED");
        }
        return properties.getCallbackBaseUrl().trim().replaceAll("/+$", "") + "/payout";
    }

    public String depositCallbackUrl() {
        if (properties.getMode() != CregisProperties.Mode.PROVIDER
                || properties.getCallbackBaseUrl() == null || properties.getCallbackBaseUrl().isBlank()) {
            throw new CregisGatewayException(
                    CregisGatewayException.Kind.CONFIGURATION, "CREGIS_CALLBACK_NOT_CONFIGURED");
        }
        return properties.getCallbackBaseUrl().trim().replaceAll("/+$", "") + "/deposit";
    }
}
