package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CregisPayoutControlTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ",", " , ,  "})
    void missingCallbackSourcesCannotExposeEnabledCapability(String sources) {
        var config = configuredProperties();
        config.setCallbackSourceIps(sources);
        var db = mock(CregisDepositMapper.class);
        var control = new CregisPayoutControl(config, db);

        assertThat(control.configured()).isFalse();
        assertThat(control.capability()).containsEntry("configured", false)
                .containsEntry("enabled", false).containsEntry("disabledReason", "NOT_CONFIGURED");
        verifyNoInteractions(db);
    }

    @Test void configuredCallbackSourceUsesExistingCommaSeparatedMatching() {
        var config = configuredProperties();
        config.setCallbackSourceIps(" , 18.143.53.174, ");
        assertThat(new CregisPayoutControl(config, mock(CregisDepositMapper.class)).configured()).isTrue();
        assertThat(config.allowsCallbackSourceIp("18.143.53.174")).isTrue();
    }

    @Test void capabilityRequiresTransportIndependentBitAndCompleteReconciliation() {
        var config = new CregisProperties();
        var db = mock(CregisDepositMapper.class);
        var control = new CregisPayoutControl(config,db);
        assertThat(control.capability()).containsEntry("disabledReason","NOT_CONFIGURED");
        verifyNoInteractions(db);
        config.setMode(CregisProperties.Mode.PROVIDER); config.setPayoutEnabled(true);
        config.setProjectId(88); config.setBaseUrl("https://provider.invalid"); config.setApiKey("fixture-only");
        config.setCallbackBaseUrl("https://callback.invalid/api/cregis/callbacks");
        config.setBscRpcUrl("https://rpc.invalid");
        config.setCallbackSourceIps("18.143.53.174");
        when(db.provisionGate()).thenReturn(Map.of("state","IDLE","payoutEnabled",0));
        assertThat(control.capability()).containsEntry("disabledReason","SWITCH_OFF");
        when(db.provisionGate()).thenReturn(Map.of("state","IDLE","payoutEnabled",1));
        when(db.unresolvedExposure(88)).thenReturn(BigDecimal.ZERO);
        assertThat(control.capability()).containsEntry("disabledReason","RECONCILIATION_REQUIRED");
        when(db.recentCompleteReconcileCount(88)).thenReturn(1);
        assertThat(control.capability()).containsEntry("enabled",true).containsEntry("disabledReason",null);
        when(db.openCriticalAlertCountExceptManual(88)).thenReturn(1);
        assertThat(control.capability()).containsEntry("disabledReason","RISK_HOLD");
    }

    private static CregisProperties configuredProperties() {
        var config = new CregisProperties();
        config.setMode(CregisProperties.Mode.PROVIDER);
        config.setPayoutEnabled(true);
        config.setProjectId(88);
        config.setBaseUrl("https://provider.invalid");
        config.setApiKey("fixture-only");
        config.setCallbackBaseUrl("https://callback.invalid/api/cregis/callbacks");
        config.setBscRpcUrl("https://rpc.invalid");
        return config;
    }
}
