package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade.Prepared;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CregisDepositServiceTest {
    private final SupportPaymentAttributionFacade capture = paymentAttribution();
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String ADDRESS = "0x1111111111111111111111111111111111111111";
    private static final String TXID = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String BLOCK = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void expiredReviewProofCannotCreditAfterWaitingForLocks() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisDepositService service = new CregisDepositService(properties(),
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        var proof = new CregisDepositService.ReviewProof(1, 77, 42, TXID, ADDRESS,
                BigDecimal.TEN, 101, BLOCK, 0, 15, 100, BLOCK, Instant.now().minusSeconds(6));
        assertThatThrownBy(() -> service.creditReviewedHold(77, proof))
                .hasMessage("CREGIS_REVIEW_PROOF_EXPIRED");
        verify(db, never()).lockEvent(88, 77);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "CAPTURE", "ZERO", "THROW"})
    void creditsOnlySignedProviderAndChainConfirmedNetAmountWithFinanceVoucher(String failure) throws Exception {
        CregisProperties props = properties();
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        riskReady(db);
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
        when(db.lockProvisionGate()).thenReturn(Map.of("state", "IDLE", "assignEnabled", 1, "creditEnabled", 1));
        when(db.lockActiveUser(42)).thenReturn(42L);
        when(db.insertEvent(eq(42L), eq(88L), eq(77L), eq(TXID), eq(0), eq(ADDRESS),
                argThat(n -> n.compareTo(BigDecimal.TEN) == 0), eq(BigDecimal.ONE), eq(new BigDecimal("9")),
                eq(101L), eq(BLOCK), eq(15), eq("CREDITED"))).thenReturn(1);
        when(db.lockWallet(42)).thenReturn(Map.of("usdtAvailable", new BigDecimal("5"), "version", 7L));
        when(db.creditWallet(new BigDecimal("9"), 42, 7)).thenReturn(1);

        when(db.ledgerId("CR-77")).thenReturn(11L);
        when(db.eventId(88, 77)).thenReturn(12L);
        when(db.linkLedger(11, 12)).thenReturn(1);
        when(db.insertDepositOrder(42, "CR-77", TXID, 0, new BigDecimal("9"), 15, 11)).thenReturn(1);

        CregisDepositService service = new CregisDepositService(props, router, chain,
                new CregisSigner(), new ObjectMapper(), db, manager, treasury,
                mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        String raw = signedCallback();
        assertThat(service.receive(raw)).isEqualTo("success");
        when(db.pendingDeliveries()).thenReturn(List.of(Map.of("id", 1L, "rawJson", raw)));
        if ("CAPTURE".equals(failure)) {
            org.mockito.Mockito.doThrow(new IllegalStateException("CAPTURE_WRITE_FAILED"))
                    .when(capture).record(any(Prepared.class));
        }
        if("ZERO".equals(failure) || "THROW".equals(failure)) {
            when(capture.insertLedger(any(Prepared.class),any(),any(),any()))
                    .thenAnswer(invocation -> { if("THROW".equals(failure))throw new IllegalStateException("LEDGER_INSERT_FAILED"); return 0; });
        }
        service.reconcile();
        if (!"NONE".equals(failure)) {
            verify(manager).rollback(any());
            verify(db).markDeliveryRetry(eq(1L), org.mockito.ArgumentMatchers.contains("CAPTURE".equals(failure)?"CAPTURE_WRITE_FAILED":"ZERO".equals(failure)?"CREGIS_LEDGER_INSERT_FAILED":"LEDGER_INSERT_FAILED"));
            if(!"CAPTURE".equals(failure))verify(capture,never()).record(any());
            return;
        }
        org.mockito.Mockito.verify(db,org.mockito.Mockito.never()).insertLedger(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        var captureOrder = org.mockito.Mockito.inOrder(db, capture);
        captureOrder.verify(db).lockActiveUser(42L);
        captureOrder.verify(capture).prepare(42L, Source.DEPOSIT_ORDER, "CR-77", "88");
        captureOrder.verify(db).insertEvent(eq(42L), eq(88L), eq(77L), eq(TXID), eq(0), eq(ADDRESS),
                argThat(n -> n.compareTo(BigDecimal.TEN) == 0), eq(BigDecimal.ONE), eq(new BigDecimal("9")),
                eq(101L), eq(BLOCK), eq(15), eq("CREDITED"));
        captureOrder.verify(db).lockWallet(42L);
        captureOrder.verify(capture).beforeCash(any(Prepared.class));
        captureOrder.verify(db).creditWallet(new BigDecimal("9"),42L,7L);
        captureOrder.verify(capture).insertLedger(org.mockito.ArgumentMatchers.any(Prepared.class),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        captureOrder.verify(db).insertDepositOrder(42, "CR-77", TXID, 0, new BigDecimal("9"), 15, 11);
        captureOrder.verify(capture).record(any(Prepared.class));
        verify(capture).record(any(Prepared.class));
        verify(db).creditWallet(new BigDecimal("9"), 42, 7);
        verify(db).insertDepositOrder(42, "CR-77", TXID, 0, new BigDecimal("9"), 15, 11);
        verify(treasury).recordTopupReserve("CR-77", new BigDecimal("9"), "CREGIS:77");
        verify(db).finishDelivery(1, "CREDITED");
    }

    @Test
    void rejectsUnsignedCallbackAfterPreservingDeliveryEvidence() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisDepositService service = new CregisDepositService(properties(),
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        assertThat(service.receive("{\"pid\":88,\"cid\":77}")).isEqualTo("rejected");
        verify(db).insertDelivery(any(), any(), any(), any(), any(), any(), anyInt(),
                org.mockito.ArgumentMatchers.eq("INVALID_SIGNATURE"), any(), anyInt(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"IDENTICAL", "EQUIVALENT", "CID", "TXID", "AMOUNT", "ADDRESS", "STATUS", "BLOCK", "SIGNATURE", "MALFORMED"})
    void pendingEquivalentRedeliveryCreditsOnceButConflictingEvidenceStillHolds(String variant) throws Exception {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        riskReady(db);
        var router = mock(CregisGatewayRouter.class);
        var provider = mock(CregisGateway.class);
        var chain = mock(BscDepositProof.class);
        var treasury = mock(TreasuryLedgerRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(router.provider()).thenReturn(provider);
        when(provider.depositsByTxid(TXID)).thenReturn(List.of(new CregisGateway.DepositTrade(
                77, CregisConstants.BSC_CHAIN_ID, CregisConstants.USDT_BEP20_TOKEN_ID,
                ADDRESS, BigDecimal.TEN, TXID, 1)));
        when(chain.verify(eq(TXID), eq(ADDRESS), any(BigDecimal.class), eq(101L)))
                .thenReturn(Optional.of(new BscDepositProof.Proof(0, 101, BLOCK, 15)));
        when(chain.blockHash(100)).thenReturn(BLOCK);
        when(db.addressOwner(88, CregisConstants.BSC_CHAIN_ID, ADDRESS)).thenReturn(List.of(Map.of(
                "userId", 42L, "allocationBlock", 100L, "allocationHash", BLOCK)));
        String first = signedCallback();
        var json = new ObjectMapper();
        Map<String, Object> redelivery = json.readValue(first, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        redelivery.put("nonce", "DEF456");
        redelivery.put("amount", "10.000000");
        switch (variant) {
            case "CID" -> redelivery.put("cid", 78);
            case "TXID" -> redelivery.put("txid", "0x" + "c".repeat(64));
            case "AMOUNT" -> redelivery.put("amount", "11");
            case "ADDRESS" -> redelivery.put("address", "0x" + "2".repeat(40));
            case "STATUS" -> redelivery.put("status", "2");
            case "BLOCK" -> redelivery.put("block_height", "102");
        }
        redelivery.put("sign", new CregisSigner().sign(KEY, redelivery));
        if ("SIGNATURE".equals(variant)) redelivery.put("sign", "invalid");
        String second = "IDENTICAL".equals(variant) ? first
                : "MALFORMED".equals(variant) ? "{" : json.writeValueAsString(redelivery);
        boolean equivalent = "IDENTICAL".equals(variant) || "EQUIVALENT".equals(variant);
        // Both arrivals are pending before the first scheduled pass. The old global count
        // treated the legitimate second arrival as a reason to create REVIEW_HOLD.
        when(db.pendingAcceptedDeliveryCount(anyLong())).thenReturn(1);
        when(db.pendingAcceptedDeliveryEvidence(anyLong())).thenReturn(List.of(Map.of("rawJson", second)));
        when(db.pendingDeliveries()).thenReturn(equivalent
                ? List.of(Map.of("id", 1L, "rawJson", first), Map.of("id", 2L, "rawJson", second))
                : List.of(Map.of("id", 1L, "rawJson", first)));
        var event = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        when(db.lockEvent(88, 77)).thenAnswer(ignored -> event.get() == null ? List.of() : List.of(event.get()));
        when(db.insertEvent(anyLong(), anyLong(), anyLong(), any(), anyInt(), any(), any(), any(), any(),
                anyLong(), any(), anyInt(), any())).thenAnswer(invocation -> {
                    event.set(Map.of("userId", 42L, "txid", TXID, "address", ADDRESS,
                            "logIndex", 0, "grossAmount", BigDecimal.TEN, "status", invocation.getArgument(12)));
                    return 1;
                });
        when(db.lockActiveUser(42L)).thenReturn(42L);
        when(db.lockWallet(42L)).thenReturn(Map.of("usdtAvailable", BigDecimal.ZERO, "version", 1L));
        when(db.creditWallet(new BigDecimal("9"), 42L, 1L)).thenReturn(1);
        when(db.ledgerId("CR-77")).thenReturn(11L);
        when(db.eventId(88L, 77L)).thenReturn(12L);
        when(db.linkLedger(11L, 12L)).thenReturn(1);
        when(db.insertDepositOrder(42L, "CR-77", TXID, 0, new BigDecimal("9"), 15, 11L)).thenReturn(1);
        var service = new CregisDepositService(properties(), router, chain, new CregisSigner(), json, db,
                manager, treasury, mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));

        service.reconcile();
        if (equivalent) {
            service.reconcile(); // A later pass may see either receipt again after an interrupted ACK.
            assertThat(event.get()).containsEntry("status", "CREDITED");
            verify(db).creditWallet(new BigDecimal("9"), 42L, 1L);
            verify(capture).insertLedger(any(Prepared.class), eq(new BigDecimal("9")), eq(new BigDecimal("9")), any());
            verify(db).insertDepositOrder(42L, "CR-77", TXID, 0, new BigDecimal("9"), 15, 11L);
            verify(treasury).recordTopupReserve("CR-77", new BigDecimal("9"), "CREGIS:77");
        } else {
            assertThat(event.get()).containsEntry("status", "REVIEW_HOLD");
            verify(db, never()).creditWallet(any(), anyLong(), anyLong());
            verifyNoInteractions(capture, treasury);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void independentPendingSuccessesCreditEachOnceForSameOrDifferentUsers(boolean differentUsers) throws Exception {
        var db = mock(CregisDepositMapper.class);
        riskReady(db);
        var router = mock(CregisGatewayRouter.class);
        var provider = mock(CregisGateway.class);
        var chain = mock(BscDepositProof.class);
        var treasury = mock(TreasuryLedgerRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(router.provider()).thenReturn(provider);
        String secondTx = "0x" + "c".repeat(64);
        String secondAddress = differentUsers ? "0x" + "2".repeat(40) : ADDRESS;
        long secondUser = differentUsers ? 43L : 42L;
        var json = new ObjectMapper();
        String first = signedCallback();
        Map<String, Object> secondPayload = json.readValue(first, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        secondPayload.put("cid", 78);
        secondPayload.put("txid", secondTx);
        secondPayload.put("address", secondAddress);
        secondPayload.put("amount", "20");
        secondPayload.put("block_height", "102");
        secondPayload.put("nonce", "DEF456");
        secondPayload.put("sign", new CregisSigner().sign(KEY, secondPayload));
        String second = json.writeValueAsString(secondPayload);
        when(db.pendingDeliveries()).thenReturn(List.of(Map.of("id", 1L, "rawJson", first), Map.of("id", 2L, "rawJson", second)));
        when(db.pendingAcceptedDeliveryCount(anyLong())).thenReturn(1);
        when(db.pendingAcceptedDeliveryEvidence(1L)).thenReturn(List.of(Map.of("rawJson", second)));
        when(db.pendingAcceptedDeliveryEvidence(2L)).thenReturn(List.of(Map.of("rawJson", first)));
        when(provider.depositsByTxid(TXID)).thenReturn(List.of(new CregisGateway.DepositTrade(
                77, CregisConstants.BSC_CHAIN_ID, CregisConstants.USDT_BEP20_TOKEN_ID, ADDRESS, BigDecimal.TEN, TXID, 1)));
        when(provider.depositsByTxid(secondTx)).thenReturn(List.of(new CregisGateway.DepositTrade(
                78, CregisConstants.BSC_CHAIN_ID, CregisConstants.USDT_BEP20_TOKEN_ID, secondAddress, new BigDecimal("20"), secondTx, 1)));
        when(chain.verify(eq(TXID), eq(ADDRESS), any(BigDecimal.class), eq(101L)))
                .thenReturn(Optional.of(new BscDepositProof.Proof(0, 101, BLOCK, 15)));
        when(chain.verify(eq(secondTx), eq(secondAddress), any(BigDecimal.class), eq(102L)))
                .thenReturn(Optional.of(new BscDepositProof.Proof(0, 102, "0x" + "d".repeat(64), 15)));
        when(chain.blockHash(100)).thenReturn(BLOCK);
        when(db.addressOwner(eq(88L), eq(CregisConstants.BSC_CHAIN_ID), any())).thenAnswer(invocation -> List.of(Map.of(
                "userId", ADDRESS.equals(invocation.getArgument(2)) ? 42L : secondUser,
                "allocationBlock", 100L, "allocationHash", BLOCK)));
        var events = new java.util.HashMap<Long, Map<String, Object>>();
        when(db.lockEvent(eq(88L), anyLong())).thenAnswer(invocation -> {
            var event = events.get(invocation.<Long>getArgument(1));
            return event == null ? List.of() : List.of(event);
        });
        when(db.insertEvent(anyLong(), anyLong(), anyLong(), any(), anyInt(), any(), any(), any(), any(),
                anyLong(), any(), anyInt(), any())).thenAnswer(invocation -> {
                    events.put(invocation.getArgument(2), Map.of("userId", invocation.getArgument(0),
                            "txid", invocation.getArgument(3), "address", invocation.getArgument(5),
                            "logIndex", invocation.getArgument(4), "grossAmount", invocation.getArgument(6),
                            "status", invocation.getArgument(12)));
                    return 1;
                });
        var balances = new java.util.HashMap<Long, BigDecimal>();
        var versions = new java.util.HashMap<Long, Long>();
        when(db.lockActiveUser(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        when(db.lockWallet(anyLong())).thenAnswer(invocation -> Map.of(
                "usdtAvailable", balances.getOrDefault(invocation.<Long>getArgument(0), BigDecimal.ZERO),
                "version", versions.getOrDefault(invocation.<Long>getArgument(0), 1L)));
        when(db.creditWallet(any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            long userId = invocation.getArgument(1);
            assertThat(invocation.<Long>getArgument(2)).isEqualTo(versions.getOrDefault(userId, 1L));
            balances.merge(userId, invocation.getArgument(0), BigDecimal::add);
            versions.merge(userId, 2L, (previous, ignored) -> previous + 1);
            return 1;
        });
        when(db.ledgerId("CR-77")).thenReturn(11L);
        when(db.ledgerId("CR-78")).thenReturn(21L);
        when(db.eventId(88L, 77L)).thenReturn(12L);
        when(db.eventId(88L, 78L)).thenReturn(22L);
        when(db.linkLedger(anyLong(), anyLong())).thenReturn(1);
        when(db.insertDepositOrder(anyLong(), any(), any(), anyInt(), any(), anyInt(), anyLong())).thenReturn(1);
        var service = new CregisDepositService(properties(), router, chain, new CregisSigner(), json, db,
                manager, treasury, mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));

        service.reconcile();
        service.reconcile();

        assertThat(events).hasSize(2);
        assertThat(events.get(77L)).containsEntry("status", "CREDITED");
        assertThat(events.get(78L)).containsEntry("status", "CREDITED");
        assertThat(balances.get(42L)).isEqualByComparingTo(differentUsers ? "9" : "28");
        if (differentUsers) assertThat(balances.get(43L)).isEqualByComparingTo("19");
        verify(db, org.mockito.Mockito.times(2)).creditWallet(any(), anyLong(), anyLong());
        verify(capture).insertLedger(any(Prepared.class), eq(new BigDecimal("9")), eq(new BigDecimal("9")), any());
        verify(capture).insertLedger(any(Prepared.class), eq(new BigDecimal("19")),
                eq(new BigDecimal(differentUsers ? "19" : "28")), any());
        verify(db).insertDepositOrder(42L, "CR-77", TXID, 0, new BigDecimal("9"), 15, 11L);
        verify(db).insertDepositOrder(secondUser, "CR-78", secondTx, 0, new BigDecimal("19"), 15, 21L);
        verify(treasury).recordTopupReserve("CR-77", new BigDecimal("9"), "CREGIS:77");
        verify(treasury).recordTopupReserve("CR-78", new BigDecimal("19"), "CREGIS:78");
    }

    @Test
    void rejectsSignedCallbackFromUnlistedPeerWithEvidence() throws Exception {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisDepositService service = new CregisDepositService(properties(),
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        assertThat(service.receive(signedCallback(), "192.0.2.5")).isEqualTo("rejected");
        verify(db).insertDelivery(any(), any(), any(), any(), any(), any(), eq(0),
                eq("INVALID_SOURCE_IP"), eq("192.0.2.5"), eq(1), eq(1), eq(0));
        verify(db, never()).lockProvisionGate();
    }

    @Test
    void verifiedDepositWhileCreditPausedBecomesReviewHoldWithoutWalletCredit() throws Exception {
        CregisProperties props = properties();
        props.setDepositCreditEnabled(false);
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.unresolvedExposure(88)).thenReturn(BigDecimal.ZERO);
        when(db.lockProvisionGate()).thenReturn(Map.of("state", "BLOCKED", "creditEnabled", 0));
        when(db.addressOwner(88, CregisConstants.BSC_CHAIN_ID, ADDRESS)).thenReturn(List.of(Map.of(
                "userId", 42L, "allocationBlock", 100L, "allocationHash", BLOCK)));
        when(db.insertEvent(eq(42L), eq(88L), eq(77L), eq(TXID), eq(0), eq(ADDRESS),
                argThat(n -> n.compareTo(BigDecimal.TEN) == 0), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
                eq(101L), eq(BLOCK), eq(15), eq("REVIEW_HOLD"))).thenReturn(1);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway provider = mock(CregisGateway.class);
        when(router.provider()).thenReturn(provider);
        when(provider.depositsByTxid(TXID)).thenReturn(List.of(new CregisGateway.DepositTrade(
                77, CregisConstants.BSC_CHAIN_ID, CregisConstants.USDT_BEP20_TOKEN_ID,
                ADDRESS, BigDecimal.TEN, TXID, 1)));
        BscDepositProof chain = mock(BscDepositProof.class);
        when(chain.verify(eq(TXID), eq(ADDRESS), argThat(n -> n.compareTo(BigDecimal.TEN) == 0), eq(101L)))
                .thenReturn(Optional.of(new BscDepositProof.Proof(0, 101, BLOCK, 15)));
        when(chain.blockHash(100)).thenReturn(BLOCK);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        CregisDepositService service = new CregisDepositService(props, router, chain,
                new CregisSigner(), new ObjectMapper(), db, manager,
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        String raw = signedCallback();
        assertThat(service.receive(raw)).isEqualTo("success");
        when(db.pendingDeliveries()).thenReturn(List.of(Map.of("id", 1L, "rawJson", raw)));
        service.reconcile();
        verify(db).finishDelivery(1, "REVIEW_HOLD");
        verify(db, never()).creditWallet(any(), anyLong(), anyLong());
            org.mockito.Mockito.verifyNoInteractions(capture);
    }

    @Test
    void disabledModeListsNoDepositsWithoutRequiringTheOptionalMigration() {
        CregisProperties disabled = new CregisProperties();
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisDepositService service = new CregisDepositService(disabled,
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        assertThat(service.deposits(42)).isEmpty();
        verify(db, never()).deposits(anyLong(), anyLong());
    }

    @Test
    void adminDepositHistoryUsesProjectScopedCursorAndKeepsHeldEvents() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.adminDeposits(88, 0, 3)).thenReturn(List.of(
                Map.of("id", "9", "cid", "9223372036854775807",
                        "grossAmount", "10000000000.000001", "status", "REVIEW_HOLD"),
                Map.of("id", "8", "status", "CREDITED"),
                Map.of("id", "7", "status", "DUST_HOLD")));
        CregisDepositService service = new CregisDepositService(properties(),
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        Map<String, Object> page = service.adminDeposits(0, 2);
        assertThat((List<?>) page.get("items")).hasSize(2);
        Map<?, ?> first = (Map<?, ?>) ((List<?>) page.get("items")).get(0);
        assertThat(first.get("cid")).isEqualTo("9223372036854775807");
        assertThat(first.get("grossAmount")).isEqualTo("10000000000.000001");
        assertThat(page).containsEntry("available", true).containsEntry("hasMore", true)
                .containsEntry("nextBeforeId", "8");
        verify(db).adminDeposits(88, 0, 3);
        assertThatThrownBy(() -> service.adminDeposits(-1, 20)).hasMessage("CREGIS_DEPOSIT_PAGE_INVALID");
        assertThatThrownBy(() -> service.adminDeposits(0, 51)).hasMessage("CREGIS_DEPOSIT_PAGE_INVALID");
    }

    @Test
    void disabledAdminDepositHistoryDoesNotReadOptionalTables() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisDepositService service = new CregisDepositService(new CregisProperties(),
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        assertThat(service.adminDeposits(0, 20)).containsEntry("available", false)
                .containsEntry("items", List.of())
                .containsEntry("hasMore", false);
        verify(db, never()).adminDeposits(anyLong(), anyLong(), anyInt());
    }

    @Test
    void exceptionsReportPayInSwitchesWithoutReadingDatabaseWhenDisabled() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisDepositService disabled = new CregisDepositService(new CregisProperties(),
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        assertThat(disabled.exceptions()).containsEntry("mode", "DISABLED")
                .containsEntry("depositEnabled", false).containsEntry("depositCreditEnabled", false)
                .containsEntry("addressPool", Map.of("available", false, "total", 0, "unassigned", 0,
                        "assigned", 0, "verifying", 0, "unknown", 0, "limit", 0));
        verify(db, never()).provisionGate();
        verify(db, never()).addressPool(anyLong(), any());

        CregisProperties enabled = properties();
        enabled.setDepositEnabled(true);
        enabled.setDepositCreditEnabled(false);
        when(db.provisionGate()).thenReturn(Map.of("state", "IDLE", "assignEnabled", 1));
        when(db.addressPool(88, CregisConstants.BSC_CHAIN_ID)).thenReturn(Map.of(
                "total", 3L, "unassigned", 2L, "assigned", 1L, "verifying", 0L, "unknown", 0L));
        CregisDepositService provider = new CregisDepositService(enabled,
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        assertThat(provider.exceptions()).containsEntry("mode", "PROVIDER")
                .containsEntry("depositEnabled", true).containsEntry("depositCreditEnabled", false)
                .containsEntry("addressPool", Map.of("available", true, "total", 3L, "unassigned", 2L,
                        "assigned", 1L, "verifying", 0L, "unknown", 0L, "limit", 0, "reserveTarget", 5, "automatic", false));
        verify(db).addressPool(88, CregisConstants.BSC_CHAIN_ID);
    }

    @Test
    void openingDepositPageCannotCreateAnAddressWhenPoolIsEmpty() {
        CregisProperties props = properties();
        props.setDepositEnabled(true);
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        riskReady(db);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway gateway = mock(CregisGateway.class);
        when(router.provider()).thenReturn(gateway);
        CregisDepositService service = new CregisDepositService(props, router, mock(BscDepositProof.class),
                new CregisSigner(), new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        assertThat(service.address(42)).containsEntry("reason", "CREGIS_ADDRESS_PREPARING");
        verify(db, never()).claimProvisionGate();
        verify(router, never()).provider();
        verify(gateway, never()).createAddress(any(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"42, true", "2503, true", "3778, false", "9000000, true"})
    void anyAuthenticatedAccountCanReadItsBoundAddressWithoutAWhitelist(long userId, boolean creditEnabled) {
        CregisProperties props = properties();
        props.setDepositEnabled(true);
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        riskReady(db);
        when(db.addressForUser(userId, CregisConstants.BSC_CHAIN_ID)).thenReturn(List.of(Map.of(
                "projectId", 88L, "state", "READY", "address", ADDRESS)));
        when(db.provisionGate()).thenReturn(Map.of("state", "IDLE", "assignEnabled", 1,
                "creditEnabled", creditEnabled ? 1 : 0));
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisDepositService service = new CregisDepositService(props, router, mock(BscDepositProof.class),
                new CregisSigner(), new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        assertThat(service.address(userId)).isEqualTo(Map.of("enabled", true, "creditEnabled", creditEnabled,
                "network", "BEP20", "address", ADDRESS, "confirmations", 15,
                "feeUsdt", 1, "minDepositUsdt", 10));
        verify(router, never()).provider();
        verify(db, never()).creditWallet(any(), anyLong(), anyLong());
    }

    @ParameterizedTest
    @CsvSource({"PROVIDER, true, false", "PROVIDER, false, true",
            "DISABLED, true, true", "LOCAL_SANDBOX, true, true"})
    void disabledGlobalChannelBlocksAllAccountsWithoutAddressOrFundsActions(
            CregisProperties.Mode mode, boolean depositEnabled, boolean creditEnabled) {
        CregisProperties props = properties();
        props.setMode(mode);
        props.setDepositEnabled(depositEnabled);
        props.setDepositCreditEnabled(creditEnabled);
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.provisionGate()).thenReturn(Map.of("state", "IDLE", "assignEnabled", 1));
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        BscDepositProof chain = mock(BscDepositProof.class);
        CregisDepositService service = new CregisDepositService(props, router, chain,
                new CregisSigner(), new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        assertThat(service.address(42)).isEqualTo(Map.of(
                "enabled", false, "creditEnabled", false, "network", "BEP20", "reason", "CREGIS_DEPOSIT_PAUSED"));
        verifyNoInteractions(router, chain);
        verifyNoInteractions(db);
        verify(db, never()).addressForUser(anyLong(), any());
        verify(db, never()).creditWallet(any(), anyLong(), anyLong());
    }

    @ParameterizedTest
    @CsvSource({"IDLE, 0", "BLOCKED, 1"})
    void pausedAssignmentReturnsUnavailableWithoutTryingToCreateAnAddress(String gateState, int assignEnabled) {
        CregisProperties props = properties();
        props.setDepositEnabled(true);
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        riskReady(db);
        when(db.provisionGate()).thenReturn(Map.of("state", gateState, "assignEnabled", assignEnabled, "creditEnabled", 1));
        BscDepositProof chain = mock(BscDepositProof.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisDepositService service = new CregisDepositService(props, router, chain,
                new CregisSigner(), new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));

        assertThat(service.address(42)).containsEntry("enabled", false)
                .containsEntry("creditEnabled", false)
                .containsEntry("reason", "CREGIS_DEPOSIT_PAUSED");
        verify(chain, never()).head();
        verify(router, never()).provider();
        verify(db, never()).allocatedAddressCount(anyLong(), any());
    }

    @ParameterizedTest
    @CsvSource({"IDLE, 1, 1, true", "IDLE, 1, 0, false", "BLOCKED, 1, 1, false",
            "MISSING, 1, 1, false", "IDLE, 0, 1, false"})
    void poolAssignmentAnchorsAtCurrentHeadAndReportsCurrentCreditGate(
            String finalState, int finalAssignEnabled, int finalCreditEnabled, boolean expectedCreditEnabled) {
        CregisProperties props = properties();
        props.setDepositEnabled(true);
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        riskReady(db);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway provider = mock(CregisGateway.class);
        BscDepositProof chain = mock(BscDepositProof.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(db.availableAddressCount(88, CregisConstants.BSC_CHAIN_ID)).thenReturn(1);
        when(chain.head()).thenReturn(new BscDepositProof.Head(120, BLOCK));
        when(chain.blockHash(120)).thenReturn(BLOCK);
        when(db.cursor()).thenReturn(107L);
        when(db.lockCursor()).thenReturn(107L);
        when(db.lockProvisionGate()).thenReturn(Map.of("state", "IDLE", "assignEnabled", 1, "creditEnabled", 1));
        Map<String, Object> currentGate = "MISSING".equals(finalState) ? null
                : Map.of("state", finalState, "assignEnabled", finalAssignEnabled, "creditEnabled", finalCreditEnabled);
        when(db.provisionGate()).thenReturn(
                Map.of("state", "IDLE", "assignEnabled", 1, "creditEnabled", 1), currentGate);
        when(db.lockUnassignedAddress(88, CregisConstants.BSC_CHAIN_ID))
                .thenReturn(Map.of("id", 1L, "address", ADDRESS));
        when(router.provider()).thenReturn(provider);
        when(provider.addressBelongs(CregisConstants.BSC_CHAIN_ID, ADDRESS)).thenReturn(true);
        when(provider.zeroAddressBalance(CregisConstants.USDT_BEP20_CURRENCY, ADDRESS)).thenReturn(true);
        when(chain.zeroUsdtBalance(ADDRESS)).thenReturn(true);
        when(db.assignPoolAddress(1, 42, 120, BLOCK)).thenReturn(1);
        CregisDepositService service = new CregisDepositService(props, router, chain,
                new CregisSigner(), new ObjectMapper(), db, manager,
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        assertThat(service.address(42)).isEqualTo(Map.of("enabled", true, "creditEnabled", expectedCreditEnabled,
                "network", "BEP20", "address", ADDRESS, "confirmations", 15,
                "feeUsdt", 1, "minDepositUsdt", 10));
        verify(db).assignPoolAddress(1, 42, 120, BLOCK);
        verify(db, never()).assignPoolAddress(1, 42, 106, BLOCK);
        verify(db, never()).creditWallet(any(), anyLong(), anyLong());
        verify(provider, never()).createAddress(any(), any(), any(), any());
    }

    @Test
    void lateSignedProviderFailureFreezesAccountAndReservesCoverableBalance() throws Exception {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        riskReady(db);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(db.lockEvent(88, 77)).thenReturn(List.of(Map.of(
                "id", 12L, "userId", 42L, "status", "CREDITED", "netAmount", new BigDecimal("9"))));
        when(db.markCreditedIncident(12, "PROVIDER_CONFLICT_HOLD")).thenReturn(1);
        when(db.lockWallet(42)).thenReturn(Map.of("usdtAvailable", new BigDecimal("5"), "version", 7L));
        when(db.reserveRiskAmount(42, 7, new BigDecimal("5"))).thenReturn(1);
        when(db.latestIncidentId(12,"PROVIDER_CONFLICT_HOLD")).thenReturn(101L);
        when(db.insertRiskHoldLedger(eq("CR-77:RISK:101"), eq(42L), eq(new BigDecimal("5")),
                eq(BigDecimal.ZERO), any())).thenReturn(1);
        when(db.insertIncident(12, 88, 77, 42, "PROVIDER_CONFLICT_HOLD", new BigDecimal("5")))
                .thenReturn(1);
        FinanceWithdrawalControlFacade withdrawalControl = mock(FinanceWithdrawalControlFacade.class);
        CregisDepositService service = new CregisDepositService(properties(), mock(CregisGatewayRouter.class),
                mock(BscDepositProof.class), new CregisSigner(), new ObjectMapper(), db, manager,
                mock(TreasuryLedgerRepository.class), withdrawalControl, capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
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

    @Test
    void exposureAtExactlyFiveHundredTripsAllThreeSwitches() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        riskReady(db);
        when(db.unresolvedExposure(88)).thenReturn(new BigDecimal("500.000000"));
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        CregisDepositService service = new CregisDepositService(properties(),
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, manager, mock(TreasuryLedgerRepository.class),
                mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        service.tripIfNeeded();
        verify(db).tripAll("CREGIS_UNRESOLVED_EXPOSURE_500");
        verify(db).insertRiskAlert(eq(88L), eq("unresolved-exposure-500"), eq("P0"),
                eq("UNRESOLVED_EXPOSURE"), any());
    }

    @Test
    void secondReorgEpochUsesItsOwnIncidentLedgerAndDuplicateInSameEpochDoesNothing() {
        CregisDepositMapper db=mock(CregisDepositMapper.class);
        Map<String,Object> event=new java.util.HashMap<>(Map.of("id",12L,"userId",42L,"status","CREDITED","netAmount",new BigDecimal("9")));
        when(db.markCreditedIncident(12,"REORG_INVESTIGATING")).thenAnswer(i->{event.put("status","REORG_INVESTIGATING");return 1;});
        when(db.lockWallet(42)).thenReturn(Map.of("usdtAvailable",new BigDecimal("5"),"version",7L));
        when(db.reserveRiskAmount(42,7,new BigDecimal("5"))).thenReturn(1);
        when(db.insertIncident(12,88,77,42,"REORG_INVESTIGATING",new BigDecimal("5"))).thenReturn(1);
        when(db.latestIncidentId(12,"REORG_INVESTIGATING")).thenReturn(101L,102L);
        java.util.Set<String> ledgerKeys=new java.util.HashSet<>();
        when(db.insertRiskHoldLedger(anyString(),eq(42L),eq(new BigDecimal("5")),eq(BigDecimal.ZERO),anyString()))
                .thenAnswer(i->ledgerKeys.add(i.getArgument(0))?1:0);
        CregisDepositService service=new CregisDepositService(properties(),mock(CregisGatewayRouter.class),mock(BscDepositProof.class),
                new CregisSigner(),new ObjectMapper(),db,mock(PlatformTransactionManager.class),mock(TreasuryLedgerRepository.class),
                mock(FinanceWithdrawalControlFacade.class),capture,mock(CregisPayoutControl.class),mock(CregisExceptionSourceService.class),mock(CregisReorgResolutionService.class));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"freezeLockedCreditedEvent",event,77L,"REORG_INVESTIGATING");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"freezeLockedCreditedEvent",event,77L,"REORG_INVESTIGATING");
        // An approved canonical restore makes the same financial event CREDITED again.
        event.put("status","CREDITED");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"freezeLockedCreditedEvent",event,77L,"REORG_INVESTIGATING");
        assertThat(ledgerKeys).containsExactlyInAnyOrder("CR-77:RISK:101","CR-77:RISK:102");
        verify(db,times(2)).insertIncident(12,88,77,42,"REORG_INVESTIGATING",new BigDecimal("5"));
        verify(db,times(2)).reserveRiskAmount(42,7,new BigDecimal("5"));
    }

    @ParameterizedTest
    @CsvSource({"IDLE, 0", "BLOCKED, 1", "MISSING, 1"})
    void unavailableDatabaseAssignmentGateHidesExistingAddressAndCredit(String state, int assignEnabled) {
        CregisProperties props = properties();
        props.setDepositEnabled(true);
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        riskReady(db);
        when(db.addressForUser(42, CregisConstants.BSC_CHAIN_ID)).thenReturn(List.of(Map.of(
                "projectId", 88L, "state", "READY", "address", ADDRESS)));
        when(db.provisionGate()).thenReturn("MISSING".equals(state) ? null
                : Map.of("state", state, "assignEnabled", assignEnabled, "creditEnabled", 1));
        CregisDepositService service = new CregisDepositService(props,
                mock(CregisGatewayRouter.class), mock(BscDepositProof.class), new CregisSigner(),
                new ObjectMapper(), db, mock(PlatformTransactionManager.class),
                mock(TreasuryLedgerRepository.class), mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        assertThat(service.address(42)).isEqualTo(Map.of("enabled", false, "creditEnabled", false,
                "network", "BEP20", "reason", "CREGIS_DEPOSIT_PAUSED"));
        verify(db, never()).creditWallet(any(), anyLong(), anyLong());
    }

    private static CregisProperties properties() {
        CregisProperties props = new CregisProperties();
        props.setMode(CregisProperties.Mode.PROVIDER);
        props.setProjectId(88);
        props.setApiKey(KEY);
        props.setDepositCreditEnabled(true);
        props.setCallbackSourceIps("127.0.0.1");
        return props;
    }

    private static void riskReady(CregisDepositMapper db) {
        when(db.unresolvedExposure(88)).thenReturn(BigDecimal.ZERO);
        when(db.provisionGate()).thenReturn(Map.of("state", "IDLE", "assignEnabled", 1));
        when(db.lockProvisionGate()).thenReturn(Map.of("state", "IDLE",
                "assignEnabled", 1, "creditEnabled", 1));
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


    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reviewedHoldCapturesBeforeReleasingSuccessAndPropagatesCaptureFailure(boolean failCapture) {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        riskReady(db);
        when(db.lockEvent(88, 77)).thenReturn(List.of(Map.ofEntries(
                Map.entry("id", 12L), Map.entry("userId", 42L), Map.entry("status", "REVIEW_HOLD"),
                Map.entry("txid", TXID), Map.entry("address", ADDRESS), Map.entry("grossAmount", BigDecimal.TEN),
                Map.entry("blockNumber", 101L), Map.entry("blockHash", BLOCK), Map.entry("logIndex", 0))));
        when(db.lockActiveUser(42L)).thenReturn(42L);
        when(db.addressOwner(88, CregisConstants.BSC_CHAIN_ID, ADDRESS)).thenReturn(List.of(Map.of(
                "userId", 42L, "allocationBlock", 100L, "allocationHash", BLOCK)));
        when(db.releaseReviewedEvent(12L, BigDecimal.ONE, new BigDecimal("9"), 15)).thenReturn(1);
        when(db.lockWallet(42L)).thenReturn(Map.of("usdtAvailable", new BigDecimal("5"), "version", 7L));
        when(db.creditWallet(new BigDecimal("9"), 42L, 7L)).thenReturn(1);

        when(db.ledgerId("CR-77")).thenReturn(11L);
        when(db.linkLedger(11L, 12L)).thenReturn(1);
        when(db.insertDepositOrder(42L, "CR-77", TXID, 0, new BigDecimal("9"), 15, 11L)).thenReturn(1);
        var service = new CregisDepositService(properties(), mock(CregisGatewayRouter.class),
                mock(BscDepositProof.class), new CregisSigner(), new ObjectMapper(), db,
                mock(PlatformTransactionManager.class), mock(TreasuryLedgerRepository.class),
                mock(FinanceWithdrawalControlFacade.class), capture, mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        var proof = new CregisDepositService.ReviewProof(12L, 77L, 42L, TXID, ADDRESS,
                BigDecimal.TEN, 101L, BLOCK, 0, 15, 100L, BLOCK, Instant.now());
        if (failCapture) {
            org.mockito.Mockito.doThrow(new IllegalStateException("CAPTURE_WRITE_FAILED"))
                    .when(capture).record(any(Prepared.class));
            assertThatThrownBy(() -> service.creditReviewedHold(77L, proof)).hasMessage("CAPTURE_WRITE_FAILED");
            return;
        }
        service.creditReviewedHold(77L, proof);
        var sequence = org.mockito.Mockito.inOrder(capture, db);
        sequence.verify(capture).prepare(42L, Source.DEPOSIT_ORDER, "CR-77", "88");
        sequence.verify(db).releaseReviewedEvent(12L, BigDecimal.ONE, new BigDecimal("9"), 15);
        sequence.verify(db).lockWallet(42L);
        sequence.verify(capture).beforeCash(any(Prepared.class));
        sequence.verify(db).creditWallet(new BigDecimal("9"),42L,7L);
        sequence.verify(capture).insertLedger(org.mockito.ArgumentMatchers.any(Prepared.class),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        sequence.verify(db).insertDepositOrder(42L, "CR-77", TXID, 0, new BigDecimal("9"), 15, 11L);
        sequence.verify(capture).record(any(Prepared.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void canonicalLedgerZeroOrFailureStopsBeforeRecord(boolean thrown) {
        org.mockito.Mockito.when(capture.insertLedger(org.mockito.ArgumentMatchers.any(Prepared.class),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> { if(thrown)throw new IllegalStateException("LEDGER_INSERT_FAILED"); return 0; });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reviewedHoldCapturesBeforeReleasingSuccessAndPropagatesCaptureFailure(false))
                .hasMessage(thrown?"LEDGER_INSERT_FAILED":"CREGIS_LEDGER_INSERT_FAILED");
        org.mockito.Mockito.verify(capture,org.mockito.Mockito.never()).record(org.mockito.ArgumentMatchers.any());
    }

    private static SupportPaymentAttributionFacade paymentAttribution() {
        var capture = org.mockito.Mockito.mock(SupportPaymentAttributionFacade.class);
        org.mockito.Mockito.when(capture.prepare(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(org.mockito.Mockito.mock(
                        Prepared.class));
        org.mockito.Mockito.when(capture.prepare(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(org.mockito.Mockito.mock(
                        Prepared.class));
        org.mockito.Mockito.when(capture.insertLedger(org.mockito.ArgumentMatchers.any(Prepared.class),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        return capture;
    }
}
