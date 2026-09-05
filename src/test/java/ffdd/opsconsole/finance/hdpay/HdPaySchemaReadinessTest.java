package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
class HdPaySchemaReadinessTest {
    private final HdPayOrderMapper mapper = mock(HdPayOrderMapper.class);

    @Test
    void disabledModeDoesNotRequireProviderTables() {
        new HdPaySchemaReadiness(new HdPayProperties(), mapper).verify();

        verify(mapper, never()).countRequiredSchemaTables();
    }

    @Test
    void providerModeFailsStartupWhenExpandMigrationIsIncomplete() {
        HdPayProperties properties = providerProperties();
        when(mapper.countRequiredSchemaTables()).thenReturn(3);
        when(mapper.countRequiredSchemaColumns()).thenReturn(13);

        assertThatThrownBy(() -> new HdPaySchemaReadiness(properties, mapper).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("HDPAY_SCHEMA_NOT_READY");
    }

    @Test
    void providerModeStartsOnlyWithCompleteSchemaContract() {
        HdPayProperties properties = providerProperties();
        when(mapper.countRequiredSchemaTables()).thenReturn(3);
        when(mapper.countRequiredSchemaColumns()).thenReturn(14);
        when(mapper.countRequiredUniqueIndexes()).thenReturn(6);
        when(mapper.countSettlementTargetCheck()).thenReturn(1);
        when(mapper.countCallbackRecoveryIndex()).thenReturn(1);

        assertThatNoException()
                .isThrownBy(() -> new HdPaySchemaReadiness(properties, mapper).verify());
    }

    private HdPayProperties providerProperties() {
        HdPayProperties properties = new HdPayProperties();
        properties.setMode(HdPayProperties.Mode.PROVIDER);
        properties.setBaseUrl("https://api.hdpayadmin.com/api/order");
        properties.setCallbackBaseUrl("https://payments.example.com");
        properties.setCallbackHosts(java.util.List.of("payments.example.com"));
        properties.setMerchantId("1234567890123456789");
        properties.setMd5Key("0123456789abcdef0123456789abcdef");
        return properties;
    }
}
