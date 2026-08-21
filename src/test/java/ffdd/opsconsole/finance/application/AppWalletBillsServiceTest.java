package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.AppWalletBillsMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppWalletBillsServiceTest {
    @Test
    void productionUserReceivesOnlyTheirCanonicalLedger() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        when(mapper.rows(7L, 200)).thenReturn(List.of(new AppWalletBillsMapper.LedgerRow(
                11L, "WD-1", "WITHDRAWAL", "USDT", "OUT", new BigDecimal("12.5"),
                new BigDecimal("87.5"), "SUCCESS", "withdrawal", LocalDateTime.of(2026, 8, 14, 9, 0))));
        var service = new AppWalletBillsService(mapper, environment("prod"));

        var result = service.list(7L).getData();

        assertThat(result).containsEntry("source", "server").containsEntry("sourceEnvironment", "PRODUCTION");
        assertThat((List<?>) result.get("bills")).hasSize(1);
        verify(mapper).rows(7L, 200);
    }

    @Test
    void isolatedProfileFailsBeforeReadingProductionLedger() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(1));
        var service = new AppWalletBillsService(mapper, environment("dev"));

        assertThatThrownBy(() -> service.list(7L)).isInstanceOf(BizException.class)
                .hasMessageContaining("WALLET_PRODUCTION_BILLS_FORBIDDEN");
        verify(mapper, never()).rows(7L, 200);
    }

    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
