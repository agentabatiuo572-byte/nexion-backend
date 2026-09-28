package ffdd.opsconsole.finance.cregis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class CregisGatewayRouter {
    private final CregisProperties properties;
    private final ObjectMapper objectMapper;

    public CregisProperties.Mode mode() {
        return properties.getMode();
    }

    public boolean payoutEnabled() {
        // Current product scope has bank withdrawals only. Provider chain payout
        // cannot be enabled by an environment flag or by the pay-in gate.
        return properties.getMode() == CregisProperties.Mode.LOCAL_SANDBOX;
    }

    public CregisGateway provider() {
        if (properties.getMode() != CregisProperties.Mode.PROVIDER) {
            throw new CregisGatewayException(
                    CregisGatewayException.Kind.CONFIGURATION, "CREGIS_PROVIDER_DISABLED");
        }
        return new HttpCregisGateway(properties, objectMapper);
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
