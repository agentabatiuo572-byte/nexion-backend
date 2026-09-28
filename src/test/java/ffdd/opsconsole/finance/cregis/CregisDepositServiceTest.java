package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CregisDepositServiceTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String ADDRESS = "0x1111111111111111111111111111111111111111";
    private static final String TXID = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String BLOCK = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void creditsOnlySignedProviderAndChainConfirmedNetAmountWithFinanceVoucher() throws Exception {
        CregisProperties props = properties();
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway gateway = mock(CregisGateway.class);
        BscDepositProof chain = mock(BscDepositProof.class);
        TreasuryLedgerRepository treasury = mock(TreasuryLedgerRepository.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(router.provider()).thenReturn(gateway);
        when(gateway.depositsByTxid(TXID)).thenReturn(List.of(new CregisGateway.DepositTrade(
                77, CregisConstants.BSC_CHAIN_ID, CregisConstants.USDT_BEP20_TOKEN_ID,
                ADDRESS, BigDecimal.TEN, TXID, 1)));
        when(chain.verify(eq(TXID), eq(ADDRESS), any(BigDecimal.class), eq(101L)))
                .thenReturn(Optional.of(new BscDepositProof.Proof(0, 101, BLOCK, 15)));
        when(db.addressOwner(88, CregisConstants.BSC_CHAIN_ID, ADDRESS))
                .thenReturn(List.of(Map.of("userId", 42L, "allocationBlock", 100L,
                        "allocationHash", BLOCK)));
        when(chain.blockHash(100)).thenReturn(BLOCK);
        when(db.lockEvent(88, 77)).thenReturn(List.of());
        when(db.lockProvisionGate()).thenReturn(Map.of("state", "IDLE"));
        when(db.lockActiveUser(42)).thenReturn(42L);
        when(db.insertEvent(eq(42L), eq(88L), eq(77L), eq(TXID), eq(0), eq(ADDRESS),
                argThat(n -> n.compareTo(BigDecimal.TEN) == 0), eq(BigDecimal.ONE), eq(new BigDecimal("9")),
                eq(101L), eq(BLOCK), eq(15), eq("CREDITED"))).thenReturn(1);
        when(db.lockWallet(42)).thenReturn(Map.of("usdtAvailable", new BigDecimal("5"), "version", 7L));
        when(db.creditWallet(new BigDecimal("9"), 42, 7)).thenReturn(1);
        when(db.insertLedger(eq("CR-77"), eq(42L), eq(new BigDecimal("9")),
                eq(new BigDecimal("14")), any())).thenReturn(1);
        when(db.ledgerId("CR-77")).thenReturn(11L);
        when(db.eventId(88, 77)).thenReturn(12L);
        when(db.linkLedger(11, 12)).thenReturn(1);
        when(db.insertDepositOrder(42, "CR-77", TXID, 0, new BigDecimal("9"), 15, 11)).thenReturn(1);

        CregisDepositService service = new CregisDepositService(props, router, chain,
                new CregisSigner(), new ObjectMapper(), db, manager, treasury,
                mock(FinanceWithdrawalControlFacade.class));
        String raw = signedCallback();
        assertThat(service.receive(raw)).isEqualTo("success");
        when(db.pendingDeliveries()).thenReturn(List.of(Map.of("id", 1L, "rawJson", raw)));
        service.reconcile();
        verify(db).creditWallet(new BigDecimal("9"), 42, 7);
        verify(db).insertDepositOrder(42, "CR-77", TXID, 0, new BigDecimal("9"), 15, 11);
        verify(treasury).recordTopupReserve("CR-77", new BigDecimal("9"), "CREGIS:77");
        verify(db).finishDelivery(1, "CREDITED");
    }

    @Test
    void rejectsUnsignedCallbackBeforeDatabaseWrite() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisDepositService service = new CregisDepositService(properties(),
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class));
        assertThat(service.receive("{\"pid\":88,\"cid\":77}")).isEqualTo("rejected");
        verify(db, never()).insertDelivery(any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void disabledModeListsNoDepositsWithoutRequiringTheOptionalMigration() {
        CregisProperties disabled = new CregisProperties();
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisDepositService service = new CregisDepositService(disabled,
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class));
        assertThat(service.deposits(42)).isEmpty();
        verify(db, never()).deposits(anyLong(), anyLong());
    }

    @Test
    void exceptionsReportPayInSwitchesWithoutReadingDatabaseWhenDisabled() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisDepositService disabled = new CregisDepositService(new CregisProperties(),
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class));
        assertThat(disabled.exceptions()).containsEntry("mode", "DISABLED")
                .containsEntry("depositEnabled", false).containsEntry("depositCreditEnabled", false);
        verify(db, never()).provisionGate();

        CregisProperties enabled = properties();
        enabled.setDepositEnabled(true);
        enabled.setDepositCreditEnabled(false);
        when(db.provisionGate()).thenReturn(Map.of("state", "IDLE"));
        CregisDepositService provider = new CregisDepositService(enabled,
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class));
        assertThat(provider.exceptions()).containsEntry("mode", "PROVIDER")
                .containsEntry("depositEnabled", true).containsEntry("depositCreditEnabled", false);
    }

    @Test
    void openingDepositPageCannotCreateAnAddressWhenPoolIsEmpty() {
        CregisProperties props = properties();
        props.setDepositEnabled(true);
        props.setDepositPilotUserIds("42");
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway gateway = mock(CregisGateway.class);
        when(router.provider()).thenReturn(gateway);
        CregisDepositService service = new CregisDepositService(props, router, mock(BscDepositProof.class),
                new CregisSigner(), new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class));
        assertThatThrownBy(() -> service.address(42))
                .hasMessage("CREGIS_ADDRESS_POOL_EMPTY");
        verify(db, never()).claimProvisionGate();
        verify(router, never()).provider();
        verify(gateway, never()).createAddress(any(), any(), any(), any());
    }

    @Test
    void alreadyBoundAddressRemainsReadableWithoutProviderWrite() {
        CregisProperties props = properties();
        props.setDepositEnabled(true);
        props.setDepositPilotUserIds("42");
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.addressForUser(42, CregisConstants.BSC_CHAIN_ID)).thenReturn(List.of(Map.of(
                "projectId", 88L, "state", "READY", "address", ADDRESS)));
        when(db.provisionGate()).thenReturn(Map.of("state", "IDLE"));
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisDepositService service = new CregisDepositService(props, router, mock(BscDepositProof.class),
                new CregisSigner(), new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class));
        assertThat(service.address(42)).containsEntry("address", ADDRESS).containsEntry("enabled", true);
        verify(router, never()).provider();
    }

    @Test
    void poolAssignmentAnchorsAtCurrentHeadBeyondUnfinalizedPreAllocationTransfers() {
        CregisProperties props = properties();
        props.setDepositEnabled(true);
        props.setDepositPilotUserIds("42");
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway provider = mock(CregisGateway.class);
        BscDepositProof chain = mock(BscDepositProof.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(db.allocatedAddressCount(88, CregisConstants.BSC_CHAIN_ID)).thenReturn(1);
        when(chain.head()).thenReturn(new BscDepositProof.Head(120, BLOCK));
        when(chain.blockHash(120)).thenReturn(BLOCK);
        when(db.cursor()).thenReturn(107L);
        when(db.lockCursor()).thenReturn(107L);
        when(db.lockProvisionGate()).thenReturn(Map.of("state", "IDLE"));
        when(db.lockUnassignedAddress(88, CregisConstants.BSC_CHAIN_ID))
                .thenReturn(Map.of("id", 1L, "address", ADDRESS));
        when(router.provider()).thenReturn(provider);
        when(provider.addressBelongs(CregisConstants.BSC_CHAIN_ID, ADDRESS)).thenReturn(true);
        when(provider.zeroAddressBalance(CregisConstants.USDT_BEP20_CURRENCY, ADDRESS)).thenReturn(true);
        when(chain.zeroUsdtBalance(ADDRESS)).thenReturn(true);
        when(db.assignPoolAddress(1, 42, 120, BLOCK)).thenReturn(1);
        CregisDepositService service = new CregisDepositService(props, router, chain,
                new CregisSigner(), new ObjectMapper(), db, manager,
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class));
        assertThat(service.address(42)).containsEntry("address", ADDRESS);
        verify(db).assignPoolAddress(1, 42, 120, BLOCK);
        verify(db, never()).assignPoolAddress(1, 42, 106, BLOCK);
    }

    @Test
    void lateSignedProviderFailureFreezesAccountAndReservesCoverableBalance() throws Exception {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(db.lockEvent(88, 77)).thenReturn(List.of(Map.of(
                "id", 12L, "userId", 42L, "status", "CREDITED", "netAmount", new BigDecimal("9"))));
        when(db.markCreditedIncident(12, "PROVIDER_CONFLICT_HOLD")).thenReturn(1);
        when(db.lockWallet(42)).thenReturn(Map.of("usdtAvailable", new BigDecimal("5"), "version", 7L));
        when(db.reserveRiskAmount(42, 7, new BigDecimal("5"))).thenReturn(1);
        when(db.insertRiskHoldLedger(eq("CR-77:RISK"), eq(42L), eq(new BigDecimal("5")),
                eq(BigDecimal.ZERO), any())).thenReturn(1);
        when(db.insertIncident(12, 88, 77, 42, "PROVIDER_CONFLICT_HOLD", new BigDecimal("5")))
                .thenReturn(1);
        FinanceWithdrawalControlFacade withdrawalControl = mock(FinanceWithdrawalControlFacade.class);
        CregisDepositService service = new CregisDepositService(properties(), mock(CregisGatewayRouter.class),
                mock(BscDepositProof.class), new CregisSigner(), new ObjectMapper(), db, manager,
                mock(TreasuryLedgerRepository.class), withdrawalControl);
        String raw = signedCallback("2");
        assertThat(service.receive(raw)).isEqualTo("success");
        when(db.pendingDeliveries()).thenReturn(List.of(Map.of("id", 1L, "rawJson", raw)));
        service.reconcile();
        verify(db).freezeUser(42, "CR-77", "PROVIDER_CONFLICT_HOLD");
        verify(db).revokeUserSessions(42);
        verify(withdrawalControl).freezePendingWithdrawalsForUser(42L, "PROVIDER_CONFLICT_HOLD", "cregis-reconciler");
        verify(db).reserveRiskAmount(42, 7, new BigDecimal("5"));
        verify(db).finishDelivery(1, "PROVIDER_CONFLICT_HOLD");
        verify(db, never()).creditWallet(any(), anyLong(), anyLong());
    }

    private static CregisProperties properties() {
        CregisProperties props = new CregisProperties();
        props.setMode(CregisProperties.Mode.PROVIDER);
        props.setProjectId(88);
        props.setApiKey(KEY);
        props.setDepositCreditEnabled(true);
        return props;
    }

    private static String signedCallback() throws Exception {
        return signedCallback("1");
    }

    private static String signedCallback(String status) throws Exception {
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("pid", 88);
        callback.put("cid", 77);
        callback.put("chain_id", CregisConstants.BSC_CHAIN_ID);
        callback.put("token_id", CregisConstants.USDT_BEP20_TOKEN_ID);
        callback.put("currency", CregisConstants.USDT_BEP20_CURRENCY);
        callback.put("address", ADDRESS);
        callback.put("amount", "10");
        callback.put("status", status);
        callback.put("txid", TXID);
        callback.put("block_height", "101");
        callback.put("nonce", "ABC123");
        callback.put("timestamp", Instant.now().toEpochMilli());
        callback.put("sign", new CregisSigner().sign(KEY, callback));
        return new ObjectMapper().writeValueAsString(callback);
    }
}
