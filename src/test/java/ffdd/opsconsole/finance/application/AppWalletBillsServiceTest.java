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
import java.util.Map;
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
    void developmentAccountReceivesCanonicalBusinessLedgerWithProductionShapedContract() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(1));
        when(mapper.count(7L)).thenReturn(1L);
        when(mapper.rows(7L, 50, 0)).thenReturn(List.of(new AppWalletBillsMapper.LedgerRow(
                12L, "QUEST:H3:7", "QUEST_REWARD", "NEX", "IN", new BigDecimal("50"),
                new BigDecimal("50"), "POSTED", "quest reward", LocalDateTime.of(2026, 8, 23, 21, 0))));
        var service = new AppWalletBillsService(mapper, environment("dev"));

        var result = service.list(7L, 1, 50).getData();

        assertThat(result).containsEntry("source", "server").containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("page", 1).containsEntry("pageSize", 50).containsEntry("total", 1L);
        assertThat((List<?>) result.get("bills")).singleElement()
                .isInstanceOfSatisfying(Map.class, bill -> assertThat(bill).containsEntry("status", "SUCCESS"));
        verify(mapper).rows(7L, 50, 0);
    }

    @Test
    void testProfileRemainsIsolatedFromCanonicalLedger() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        var service = new AppWalletBillsService(mapper, environment("test"));

        assertThatThrownBy(() -> service.list(7L)).isInstanceOf(BizException.class)
                .hasMessageContaining("WALLET_PRODUCTION_BILLS_FORBIDDEN");
        verify(mapper, never()).rows(7L, 200);
    }

    @Test
    void canonicalLedgerStatusesMapToTheThreeStateAppContract() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        when(mapper.rows(7L, 200)).thenReturn(List.of(
                ledgerRow(13L, "SUCCESS"),
                ledgerRow(14L, "POSTED"),
                ledgerRow(15L, "COMPLETED"),
                ledgerRow(16L, "CONFIRMED"),
                ledgerRow(17L, "PENDING"),
                ledgerRow(18L, "FAILED"),
                ledgerRow(19L, "REJECTED"),
                ledgerRow(20L, "CANCELLED")));
        var service = new AppWalletBillsService(mapper, environment("prod"));

        var result = service.list(7L).getData();

        List<String> statuses = ((List<?>) result.get("bills")).stream()
                .map(row -> String.valueOf(((Map<?, ?>) row).get("status")))
                .toList();
        assertThat(statuses).containsExactly(
                "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS",
                "PENDING", "FAILED", "FAILED", "FAILED");
    }

    @Test
    void unknownLedgerStatusFailsClosedBeforeSendingAnInvalidApiContract() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        when(mapper.rows(7L, 200)).thenReturn(List.of(ledgerRow(21L, "SETTLED")));
        var service = new AppWalletBillsService(mapper, environment("prod"));

        assertThatThrownBy(() -> service.list(7L)).isInstanceOf(BizException.class)
                .hasMessageContaining("WALLET_LEDGER_STATUS_INVALID");
    }

    @Test
    void businessLocalLedgerTimeIsPublishedAsAnUnambiguousInstant() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(1));
        when(mapper.rows(7L, 200)).thenReturn(List.of(new AppWalletBillsMapper.LedgerRow(
                22L, "G4-SBX-1", "GENESIS_PURCHASE", "USDT", "OUT", new BigDecimal("7999"),
                new BigDecimal("2711.97"), "SUCCESS", "Genesis purchase",
                LocalDateTime.of(2026, 8, 28, 11, 10, 12))));
        var service = new AppWalletBillsService(mapper, environment("dev"));

        @SuppressWarnings("unchecked")
        var bills = (List<Map<String, Object>>) service.list(7L).getData().get("bills");

        assertThat(bills).singleElement()
                .satisfies(bill -> assertThat(bill).containsEntry("createdAt", "2026-08-28T03:10:12Z"));
    }

    private AppWalletBillsMapper.LedgerRow ledgerRow(long id, String status) {
        return new AppWalletBillsMapper.LedgerRow(
                id, "BIZ-" + id, "TEST", "USDT", "IN", BigDecimal.ONE,
                new BigDecimal("10"), status, "test", LocalDateTime.of(2026, 8, 27, 9, 0));
    }

    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
