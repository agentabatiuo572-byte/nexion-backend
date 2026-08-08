package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CregisSandboxServiceTest {
    @Test
    void localProbeCompletesAdapterContractButNeverClaimsRealMoneyReadiness() {
        CregisProperties properties = new CregisProperties();
        properties.setMode(CregisProperties.Mode.LOCAL_SANDBOX);
        var router = new CregisGatewayRouter(properties, new ObjectMapper());
        var service = new CregisSandboxService(router, new CregisSigner(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC));

        assertThat(service.overview()).satisfies(overview -> {
            assertThat(overview.mode()).isEqualTo("LOCAL_SANDBOX");
            assertThat(overview.localSandboxAvailable()).isTrue();
            assertThat(overview.productionReady()).isFalse();
            assertThat(overview.fundWorkflowConnected()).isFalse();
        });
        assertThat(service.runProbe()).satisfies(probe -> {
            assertThat(probe.result()).isEqualTo("PASS");
            assertThat(probe.asset()).isEqualTo("USDT-BEP20");
            assertThat(probe.addressLegal()).isTrue();
            assertThat(probe.addressOwned()).isTrue();
            assertThat(probe.callbackSignatureVerified()).isTrue();
            assertThat(probe.externalFundSideEffects()).isFalse();
            assertThat(probe.payoutStatus()).isEqualTo("AWAITING_AUDIT");
        });
        assertThat(service.runProbe().result()).isEqualTo("PASS");
    }

    @Test
    void disabledOrProviderModeCannotUseTheLocalProbeAsProductionEvidence() {
        for (CregisProperties.Mode mode : new CregisProperties.Mode[] {
                CregisProperties.Mode.DISABLED, CregisProperties.Mode.PROVIDER}) {
            CregisProperties properties = new CregisProperties();
            properties.setMode(mode);
            var router = new CregisGatewayRouter(properties, new ObjectMapper());
            var service = new CregisSandboxService(router, new CregisSigner(), Clock.systemUTC());
            assertThatThrownBy(service::runProbe)
                    .isInstanceOf(CregisGatewayException.class)
                    .hasMessage("CREGIS_LOCAL_SANDBOX_DISABLED");
        }
    }
}
