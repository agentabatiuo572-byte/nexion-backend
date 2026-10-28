package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.domain.SupportFundAllocation;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import org.mockito.ArgumentCaptor;

class SupportWithdrawalFundSourceServiceTest {
    private static final BigDecimal GROSS=new BigDecimal("100"), UVT=new BigDecimal("5");
    @Test void providerConfirmationUsesOneWholeSecondBeforeMysqlRoundingAndFundVerification() {
        var fixture=new Fixture("CONFIRM",BigDecimal.ZERO);
        var clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-13T12:47:34.900Z"),java.time.ZoneOffset.UTC);
        var expected=LocalDateTime.now(clock).withNano(0);
        when(fixture.mapper.lockCashCustomer(7L)).thenReturn(7L);
        when(fixture.mapper.insertPayoutLedger(anyString(),anyString(),anyLong(),anyString(),anyString(),anyString(),any(),any(),anyString(),any())).thenReturn(1);
        when(fixture.mapper.terminalOrder(anyString(),anyLong(),anyString(),any(),any(),any())).thenAnswer(i->{
            LocalDateTime written=i.getArgument(5);
            // MySQL DATETIME(0) rounds fractional seconds without TIME_TRUNCATE_FRACTIONAL.
            fixture.order.put("completedAt",written.withNano(0).plusSeconds(written.getNano()>=500_000_000?1:0));
            return 1;
        });
        when(fixture.mapper.settlePending(eq(7L),eq(GROSS),any())).thenReturn(1);
        var row=new WithdrawalPayoutMapper.PayoutRow("WD1",7L,"USDT-BEP20","0x"+"c".repeat(40),GROSS,new BigDecimal("90"),
                BigDecimal.ZERO,"SENT",expected,1L,"WD1","provider",0);
        var finalizer=new WithdrawalPayoutFinalizer(fixture.mapper,mock(ffdd.opsconsole.shared.audit.AuditLogService.class),
                mock(ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade.class),clock,fixture.service, mock(ffdd.opsconsole.shared.outbox.EventOutboxService.class));
        assertThat(finalizer.confirmProviderChain(row,"0x"+"a".repeat(64),new ffdd.opsconsole.finance.cregis.BscDepositProof.Proof(1,100,"0x"+"b".repeat(64),15),15)).isTrue();
        verify(fixture.mapper).terminalOrder("WD1",1L,"CONFIRMED","0x"+"a".repeat(64),null,expected);
        verify(fixture.mapper).settlePending(7L,GROSS,expected);
        assertThat(fixture.order.get("completedAt")).isEqualTo(expected);
        assertThat(fixture.receipt().phase()).isEqualTo("CONFIRM");
    }
    @Test void confirmationStillRejectsAReadbackFromAnotherSecond() {
        var fixture=new Fixture("CONFIRM",BigDecimal.ZERO);
        fixture.order.put("completedAt",Fixture.AT.plusSeconds(1));
        assertThatThrownBy(()->fixture.service.confirmed(fixture.service.begin(7),"WD1",Fixture.AT))
                .hasMessage("WITHDRAWAL_FUND_SUCCESS_SOURCE_MISMATCH");
        verify(fixture.fund,never()).bind(any(),any());
    }
    @Test void jdbcBooleanFlagsBindEveryProductionWithdrawalPhase() {
        for(String phase:List.of("RESERVE","CONFIRM","RETURN")) {
            var fixture=new Fixture(phase,UVT);
            for(String key:List.of("sandbox","isDeleted")) {
                fixture.before.put(key,false);fixture.after.put(key,false);
                String cap=Character.toUpperCase(key.charAt(0))+key.substring(1);
                fixture.raw.put("old"+cap,false);fixture.raw.put("new"+cap,false);
            }
            fixture.order.put("userSandbox",false);fixture.order.put("userDeleted",false);
            var rows=ledgers();rows.add(ledger("WD1:NEX:OFFSET","WITHDRAW_FEE_OFFSET","UVT",UVT));
            rows.forEach(row->row.put("isDeleted",false));when(fixture.mapper.fundReservationLedgers("WD1")).thenReturn(rows);
            var before=fixture.service.begin(7);
            if("CONFIRM".equals(phase))fixture.service.confirmed(before,"WD1",Fixture.AT);
            else if("RETURN".equals(phase))fixture.service.returned(before,"WD1");
            else fixture.service.reserved(before,"WD1");
            assertThat(fixture.receipt().phase()).isEqualTo(phase);
        }
    }
    @Test void jdbcBooleanSandboxSkipsFundStatisticsAndDeletedWalletStillFails() {
        var sandbox=new Fixture("RESERVE",BigDecimal.ZERO);sandbox.before.put("sandbox",true);
        sandbox.service.reserved(sandbox.service.begin(7),"WD1");verifyNoInteractions(sandbox.fund);
        var deleted=new Fixture("RESERVE",BigDecimal.ZERO);deleted.before.put("isDeleted",true);
        assertThatThrownBy(()->deleted.service.begin(7)).hasMessage("WITHDRAWAL_FUND_WALLET_IDENTITY_MISMATCH");
    }
    @Test void laterCustomerStateCannotBlockAnExistingRealCashReturn() {
        var fixture=new Fixture("RETURN",BigDecimal.ZERO);
        fixture.order.put("userDeleted",true);fixture.order.put("userSandbox",true);
        fixture.service.returned(fixture.service.begin(7),"WD1");
        assertThat(fixture.receipt().phase()).isEqualTo("RETURN");
        assertThat(fixture.receipt().sourceSnapshotJson()).contains("\"userDeleted\":true","\"userSandbox\":true");
    }
    @Test void allOutcomeEventsUseTheActualAllocationContract() {
        for(String phase:List.of("RESERVE","CONFIRM","RETURN"))
            SupportWithdrawalFundSourceService.events(7,"WD1",phase,GROSS,UVT).forEach(SupportFundAllocation::validate);
    }
    @Test void grossIncludesTheFeeAndNexRemainsIndependent() {
        var events=SupportWithdrawalFundSourceService.events(7,"WD1","RESERVE",GROSS,UVT);
        assertThat(events).hasSize(2);assertThat(events.get(0).amount()).isEqualByComparingTo("100");
        assertThat(events.get(1).action()).isEqualTo(Action.FEE);
        assertThat(events.get(1).from().currency()).isEqualTo("UVT");
        assertThat(events.get(1).amount()).isEqualByComparingTo("5");
    }
    @Test void returnRestoresSourcesWithoutCreatingANewContribution() {
        var allocation=new SupportFundAllocation();var usdt=new Account(7,"USDT","available");var nex=new Account(7,"UVT","available");
        allocation.apply(new Event("d1",Action.DEPOSIT,null,usdt,null,GROSS,null));
        allocation.apply(new Event("d2",Action.DEPOSIT,null,nex,null,UVT,null));
        allocation.applyBatch(SupportWithdrawalFundSourceService.events(7,"WD1","RESERVE",GROSS,UVT));
        allocation.applyBatch(SupportWithdrawalFundSourceService.events(7,"WD1","RETURN",GROSS,UVT));
        assertThat(allocation.balance(usdt)).isEqualByComparingTo(GROSS);
        assertThat(allocation.balance(nex)).isEqualByComparingTo(UVT);
        assertThat(allocation.total("USDT")).isEqualByComparingTo(GROSS);
    }
    @Test void committedAttemptAndExactLedgerComponentsProveThisOrder() {
        assertThat(SupportWithdrawalFundSourceService.reservation(order(),ledgers())).isTrue();
    }
    @Test void anotherOrdersPendingDoesNotProveThisOrder() {
        var order=order();order.remove("attemptStatus");order.put("pendingWithdraw",GROSS);
        assertThat(SupportWithdrawalFundSourceService.reservation(order,ledgers())).isFalse();
    }
    @Test void unrelatedOrDuplicateLedgerNeverProvesAReservation() {
        var ledgers=ledgers();ledgers.get(0).put("bizNo","WD2:USDT:PRINCIPAL");
        assertThat(SupportWithdrawalFundSourceService.reservation(order(),ledgers)).isFalse();
        var duplicate=ledgers();duplicate.add(new HashMap<>(duplicate.get(0)));
        assertThat(SupportWithdrawalFundSourceService.reservation(order(),duplicate)).isFalse();
    }
    @Test void componentAmountsAndCustomerMustMatch() {
        var wrong=ledgers();wrong.get(0).put("amount",new BigDecimal("91"));
        assertThat(SupportWithdrawalFundSourceService.reservation(order(),wrong)).isFalse();
        wrong=ledgers();wrong.get(0).put("userId",8L);
        assertThat(SupportWithdrawalFundSourceService.reservation(order(),wrong)).isFalse();
    }
    @Test void nexOffsetIsNotAddedToUsdtGross() {
        var order=order();order.put("nexBurned",UVT);var rows=ledgers();
        rows.add(ledger("WD1:NEX:OFFSET","WITHDRAW_FEE_OFFSET","UVT",UVT));
        assertThat(SupportWithdrawalFundSourceService.reservation(order,rows)).isTrue();
    }
    @Test void missingReserveStaysUnknownOnReplay() {
        var allocation=new SupportFundAllocation();
        assertThatThrownBy(()->allocation.applyBatch(SupportWithdrawalFundSourceService.events(7,"WD1","CONFIRM",GROSS,BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void bankReserveUsesItsRealQuoteWithoutAnAppAttempt() throws Exception {
        var fixture=new Fixture("RESERVE",BigDecimal.ZERO);fixture.order.remove("attemptStatus");fixture.order.put("chain","BANK-VND");
        when(fixture.mapper.fundReservationLedgers("WD1")).thenReturn(List.of(
                ledger("WD1:USDT:PRINCIPAL","WITHDRAW_NET_PRINCIPAL","USDT",new BigDecimal("90")),
                ledger("WD1:USDT:BANK_FEE","WITHDRAW_BANK_FEE","USDT",new BigDecimal("10"))));
        when(fixture.mapper.fundBankReservation("WD1")).thenReturn(Map.of("userId",7L,"withdrawalNo","WD1","quoteNo","BQ1",
                "amount",GROSS,"fee",new BigDecimal("10"),"net",new BigDecimal("90")));
        fixture.service.reserved(fixture.service.begin(7),"WD1");
        var receipt=fixture.receipt();
        assertThat(fixture.json.readTree(receipt.sourceSnapshotJson()).path("bankReservation").path("quoteNo").asText()).isEqualTo("BQ1");
        assertThat(fixture.json.readTree(receipt.orderedEventsJson())).hasSize(1);
    }
    @Test void missingOldFundReceiptDoesNotBlockRealCashConfirmation() throws Exception {
        var fixture=new Fixture("CONFIRM",BigDecimal.ZERO);fixture.order.remove("attemptStatus");
        fixture.service.confirmed(fixture.service.begin(7),"WD1",Fixture.AT);
        var proof=fixture.json.readTree(fixture.receipt().sourceProofJson());
        assertThat(proof.path("originalFundEvidence").asText()).isEqualTo("UNKNOWN");
        assertThat(proof.path("originalReserveOperation").isNull()).isTrue();
        assertThat(proof.path("cashTrace")).hasSize(1);
    }
    @Test void reserveAndReturnBindAllIndependentCurrencyEventsToTheirOneActualRevision() throws Exception {
        for(String phase:List.of("RESERVE","RETURN")) {
            var fixture=new Fixture(phase,UVT);fixture.returnedOrReserved(phase);
            var receipt=fixture.receipt();var events=fixture.json.readTree(receipt.orderedEventsJson());
            assertThat(events).hasSize(2);
            var proof=fixture.json.readTree(receipt.sourceProofJson());
            assertThat(proof.path("cashTrace")).hasSize(1);
            assertThat(proof.path("cashTrace").get(0).path("eventIds")).hasSize(2);
            assertThat(proof.path("cashTrace").get(0).path("journal").path("newVersion").asLong()).isEqualTo(2);
        }
    }
    @Test void eachRawRevisionMustMatchAllFourBuckets() {
        var fixture=new Fixture("CONFIRM",BigDecimal.ZERO);fixture.raw.put("newCregisRiskHeld",BigDecimal.ONE);
        assertThatThrownBy(()->fixture.service.confirmed(fixture.service.begin(7),"WD1",Fixture.AT))
                .hasMessage("WITHDRAWAL_FUND_CASH_TRACE_MISMATCH");
        verify(fixture.fund,never()).bind(any(),any());
    }
    @Test void multipleCashRevisionsCannotBeHiddenBehindTheSameNetDelta() {
        var fixture=new Fixture("RESERVE",BigDecimal.ZERO);fixture.after.put("revision",3L);
        assertThatThrownBy(()->fixture.service.reserved(fixture.service.begin(7),"WD1"))
                .hasMessage("WITHDRAWAL_FUND_CASH_TRACE_MISMATCH");
        verify(fixture.fund,never()).bind(any(),any());
    }
    @Test void walletIdentityVersionAndCustomerAreNotInferredFromBalance() {
        for(String field:List.of("newVersion","newId","newUserId")) {
            var fixture=new Fixture("CONFIRM",BigDecimal.ZERO);fixture.raw.put(field,99L);
            assertThatThrownBy(()->fixture.service.confirmed(fixture.service.begin(7),"WD1",Fixture.AT))
                    .hasMessage("WITHDRAWAL_FUND_CASH_TRACE_MISMATCH");
        }
    }
    @Test void oldSubmissionAuthorityWithMatchingActualLedgersCanClearCashWithoutNewFundHistory() throws Exception {
        var fixture=new Fixture("CONFIRM",BigDecimal.ZERO);fixture.order.remove("attemptStatus");
        String detail=fixture.json.writeValueAsString(Map.of("withdrawal_id","WD1","chain","USDT-TRC20",
                "amount_usdt",GROSS,"net_receive",new BigDecimal("90"),"actual_fee",new BigDecimal("10"),"nex_burned",BigDecimal.ZERO));
        when(fixture.mapper.fundSubmissionAudits("WD1")).thenReturn(List.of(Map.of("userId",7L,"actorId",7L,
                "actorType","USER","result","SUCCESS","detailJson",detail)));
        assertThat(fixture.service.hasCashReservation("WD1",7,GROSS,BigDecimal.ZERO)).isTrue();
        verify(fixture.journal,never()).operation(anyString());
    }
    @Test void forgedSubmissionAmountDoesNotProveAnOldReservation() throws Exception {
        var fixture=new Fixture("CONFIRM",BigDecimal.ZERO);fixture.order.remove("attemptStatus");
        when(fixture.mapper.fundSubmissionAudits("WD1")).thenReturn(List.of(Map.of("userId",7L,"actorId",7L,
                "actorType","USER","result","SUCCESS","detailJson","{\"amount_usdt\":200}")));
        assertThat(fixture.service.hasCashReservation("WD1",7,GROSS,BigDecimal.ZERO)).isFalse();
    }
    static class Fixture {
        static final LocalDateTime AT=LocalDateTime.of(2026,10,11,0,0);
        final ObjectMapper json=new ObjectMapper();final SupportFundJournalFacade fund=mock(SupportFundJournalFacade.class);
        final SupportFundJournalMapper journal=mock(SupportFundJournalMapper.class);final WithdrawalPayoutMapper mapper=mock(WithdrawalPayoutMapper.class);
        final SupportWithdrawalFundSourceService service=new SupportWithdrawalFundSourceService(fund,journal,mapper,json);
        final Map<String,Object> order=order(),before=new HashMap<>(),after=new HashMap<>(),raw=new HashMap<>();
        Fixture(String phase,BigDecimal nex) {
            when(fund.begin(any())).thenReturn(mock(SupportFundJournalFacade.Before.class));
            before.putAll(Map.of("id",70L,"userId",7L,"version",1L,"revision",1L,"incarnation","inc1","sandbox",0,"isDeleted",0));
            before.putAll(Map.of("usdtAvailable",new BigDecimal("900"),"nexAvailable",new BigDecimal("20"),"pendingWithdraw",GROSS,"cregisRiskHeld",BigDecimal.ZERO));
            after.putAll(before);after.put("version",2L);after.put("revision",2L);
            after.put("usdtAvailable",new BigDecimal("900").add("RESERVE".equals(phase)?GROSS.negate():"RETURN".equals(phase)?GROSS:BigDecimal.ZERO));
            after.put("nexAvailable",new BigDecimal("20").add("RESERVE".equals(phase)?nex.negate():"RETURN".equals(phase)?nex:BigDecimal.ZERO));
            after.put("pendingWithdraw",GROSS.add("RESERVE".equals(phase)?GROSS:GROSS.negate()));
            raw.put("observation","CHANGE");raw.put("incarnation","inc1");raw.put("revision",2L);
            for(String field:List.of("id","userId","version","sandbox","isDeleted","usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld")) {
                String cap=Character.toUpperCase(field.charAt(0))+field.substring(1);raw.put("old"+cap,before.get(field));raw.put("new"+cap,after.get(field));
            }
            order.put("status","CONFIRM".equals(phase)?"CONFIRMED":"RETURN".equals(phase)?"FAILED":"REVIEW_PENDING");
            order.put("completedAt",AT);order.put("nexBurned",nex);order.put("userSandbox",0);order.put("userDeleted",0);
            when(journal.lockWallet(7)).thenReturn(List.of(before),List.of(after));
            when(journal.journal("inc1",1,2)).thenReturn(List.of(raw));when(mapper.fundOrder("WD1")).thenReturn(order);
            var rows=ledgers();if(nex.signum()>0)rows.add(ledger("WD1:NEX:OFFSET","WITHDRAW_FEE_OFFSET","UVT",nex));
            when(mapper.fundReservationLedgers("WD1")).thenReturn(rows);
            when(fund.bind(any(),any())).thenAnswer(i->{var input=i.getArgument(1,SupportFundJournalFacade.ReceiptInput.class);
                return new SupportFundJournalFacade.BoundReceipt(1,input.operationKey(),"a".repeat(64),1);});
        }
        SupportFundJournalFacade.ReceiptInput receipt() {
            var captured=ArgumentCaptor.forClass(SupportFundJournalFacade.ReceiptInput.class);verify(fund).bind(any(),captured.capture());return captured.getValue();
        }
        void returnedOrReserved(String phase) {var token=service.begin(7);if("RETURN".equals(phase))service.returned(token,"WD1");else service.reserved(token,"WD1");}
    }
    static Map<String,Object> order() {
        var map=new HashMap<String,Object>();map.put("withdrawalNo","WD1");map.put("userId",7L);map.put("asset","USDT");
        map.put("attemptUserId",7L);map.put("attemptKey","key1");map.put("idempotencyKey","key1");
        map.put("attemptStatus","COMMITTED");map.put("attemptWithdrawalNo","WD1");
        map.put("amount",GROSS);map.put("netReceive",new BigDecimal("90"));map.put("actualFee",new BigDecimal("10"));
        map.put("networkFee",new BigDecimal("15"));map.put("nexBurned",BigDecimal.ZERO);map.put("chain","USDT-TRC20");return map;
    }
    static ArrayList<Map<String,Object>> ledgers() {
        return new ArrayList<>(List.of(ledger("WD1:USDT:PRINCIPAL","WITHDRAW_NET_PRINCIPAL","USDT",new BigDecimal("90")),
                ledger("WD1:USDT:NETWORK_FEE","WITHDRAW_NETWORK_FEE","USDT",new BigDecimal("10"))));
    }
    static Map<String,Object> ledger(String no,String type,String asset,BigDecimal amount) {
        return new HashMap<>(Map.of("bizNo",no,"bizType",type,"asset",asset,"amount",amount,
                "userId",7L,"direction","OUT","status","POSTED","isDeleted",0));
    }
}
