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
                .thenReturn(List.of(Map.of("userId", 42L, "allocationBlock", 100L)));
        when(db.lockEvent(88, 77)).thenReturn(List.of());
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
                new CregisSigner(), new ObjectMapper(), db, manager, treasury);
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
                mock(TreasuryLedgerRepository.class));
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
                mock(TreasuryLedgerRepository.class));
        assertThat(service.deposits(42)).isEmpty();
        verify(db, never()).deposits(anyLong(), anyLong());
    }

    @Test
    void permanentAddressCountStopsTheFiftyFirstProvisionBeforeProviderWrite() {
        CregisProperties props = properties();
        props.setDepositEnabled(true);
        props.setDepositPilotUserIds("42");
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway gateway = mock(CregisGateway.class);
        BscDepositProof chain = mock(BscDepositProof.class);
        when(router.provider()).thenReturn(gateway);
        when(gateway.projectCoins()).thenReturn(List.of(new CregisGateway.Coin("USDT", "USDT",
                CregisConstants.BSC_CHAIN_ID, CregisConstants.USDT_BEP20_TOKEN_ID, false, true)));
        when(chain.head()).thenReturn(new BscDepositProof.Head(100, BLOCK));
        when(db.claimProvisionGate()).thenReturn(1);
        when(db.allocatedAddressCount(88, CregisConstants.BSC_CHAIN_ID)).thenReturn(50);
        CregisDepositService service = new CregisDepositService(props, router, chain,
                new CregisSigner(), new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class));
        assertThatThrownBy(() -> service.address(42))
                .hasMessage("CREGIS_PILOT_ADDRESS_LIMIT_REACHED");
        verify(db).releaseProvisionGate();
        verify(gateway, never()).createAddress(any(), any(), any(), any());
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
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("pid", 88);
        callback.put("cid", 77);
        callback.put("chain_id", CregisConstants.BSC_CHAIN_ID);
        callback.put("token_id", CregisConstants.USDT_BEP20_TOKEN_ID);
        callback.put("currency", CregisConstants.USDT_BEP20_CURRENCY);
        callback.put("address", ADDRESS);
        callback.put("amount", "10");
        callback.put("status", "1");
        callback.put("txid", TXID);
        callback.put("block_height", "101");
        callback.put("nonce", "ABC123");
        callback.put("timestamp", Instant.now().toEpochMilli());
        callback.put("sign", new CregisSigner().sign(KEY, callback));
        return new ObjectMapper().writeValueAsString(callback);
    }
}
