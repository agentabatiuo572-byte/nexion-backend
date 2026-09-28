package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CregisDepositReconciliationTest {
    @Test
    void twoStableCompletePassesAndCoveredChainAdvanceWatermark() {
        Fixture f = fixture();
        when(f.provider.depositPage(anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(new CregisGateway.DepositPage(1, List.of(row(1))));
        when(f.deposits.materializeProviderRow(any())).thenReturn(true);
        when(f.db.completeReconcileRun(any(), anyLong(), anyLong(), anyLong(), any(), anyLong(), any()))
                .thenReturn(1);
        when(f.db.advanceReconcileWatermark(anyLong(), anyLong(), any())).thenReturn(1);
        assertThat(f.service.runOnce()).containsEntry("status", "COMPLETE");
        verify(f.deposits).scanTrackedAddresses(986);
        verify(f.db).completeReconcileRun(any(), anyLong(), anyLong(), anyLong(), any(), anyLong(), any());
        verify(f.db).advanceReconcileWatermark(anyLong(), anyLong(), any());
    }

    @Test
    void changedSecondPassLeavesWatermarkAtLastCompleteWindow() {
        Fixture f = fixture();
        when(f.provider.depositPage(anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(new CregisGateway.DepositPage(1, List.of(row(1))),
                        new CregisGateway.DepositPage(1, List.of(row(2))));
        assertThatThrownBy(f.service::runOnce).hasMessage("CREGIS_RECONCILE_PROVIDER_DRIFT");
        verify(f.db).failReconcileRun(any(), any());
        verify(f.db, never()).advanceReconcileWatermark(anyLong(), anyLong(), any());
        verify(f.deposits, never()).materializeProviderRow(any());
    }

    @Test
    void incompleteChainCoverageNeverCreditsOrAdvances() {
        Fixture f = fixture();
        when(f.provider.depositPage(anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(new CregisGateway.DepositPage(1, List.of(row(1))));
        when(f.db.cursor()).thenReturn(986L);
        assertThatThrownBy(f.service::runOnce).hasMessage("CREGIS_RECONCILE_CHAIN_CURSOR_INCOMPLETE");
        verify(f.deposits).scanTrackedAddresses(986);
        verify(f.db, never()).advanceReconcileWatermark(anyLong(), anyLong(), any());
        verify(f.deposits, never()).materializeProviderRow(any());
    }

    @Test
    void concurrentTriggerReturnsExistingRunWithoutCreatingOrAdvancingAnother() {
        Fixture f = fixture();
        when(f.db.claimReconcileLease(any())).thenReturn(0);
        when(f.db.activeReconcileRun()).thenReturn("existing-run");
        assertThat(f.service.runOnce()).containsEntry("status", "RUNNING")
                .containsEntry("runId", "existing-run");
        verify(f.db, never()).createReconcileRun(any(), anyLong(), anyLong(), anyLong());
        verify(f.db, never()).advanceReconcileWatermark(anyLong(), anyLong(), any());
    }

    private static CregisGateway.DepositRow row(int status) {
        return new CregisGateway.DepositRow(77, "2510", CregisConstants.USDT_BEP20_TOKEN_ID,
                "0x1111111111111111111111111111111111111111", BigDecimal.TEN,
                "0x" + "a".repeat(64), status, 100, Instant.now().getEpochSecond() - 600);
    }

    private static Fixture fixture() {
        CregisProperties props = new CregisProperties();
        props.setMode(CregisProperties.Mode.PROVIDER);
        props.setProjectId(88);
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway provider = mock(CregisGateway.class);
        CregisDepositService deposits = mock(CregisDepositService.class);
        BscDepositProof chain = mock(BscDepositProof.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(router.provider()).thenReturn(provider);
        long now = Instant.now().getEpochSecond();
        when(db.firstAddressSecond(88)).thenReturn(now - 7200);
        when(db.reconcileWatermark()).thenReturn(now - 3600);
        when(db.claimReconcileLease(any())).thenReturn(1);
        when(db.lockReconcileWatermark()).thenReturn(now - 3600);
        when(db.cursor()).thenReturn(1001L);
        when(chain.head()).thenReturn(new BscDepositProof.Head(1000, "0x" + "b".repeat(64)));
        when(chain.blockHash(986)).thenReturn("0x" + "c".repeat(64));
        return new Fixture(new CregisDepositReconciliation(props, router, chain, db, deposits, tx),
                db, provider, deposits);
    }

    private record Fixture(CregisDepositReconciliation service, CregisDepositMapper db,
                           CregisGateway provider, CregisDepositService deposits) { }
}
