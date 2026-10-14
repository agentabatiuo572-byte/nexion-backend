package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;

class SupportPaymentFactServiceTest {
    private final SupportPaymentFactMapper mapper = mock(SupportPaymentFactMapper.class);
    private final SupportPaymentFactService service = new SupportPaymentFactService(mapper,mock(ffdd.opsconsole.market.facade.SupportGenesisReadFacade.class));
    private final LocalDateTime at = LocalDateTime.of(2026,10,7,12,0,0);
    @BeforeEach void emptySources() {
        when(mapper.deposits(any())).thenReturn(List.of()); when(mapper.cards(any())).thenReturn(List.of());
        when(mapper.vietqr(any())).thenReturn(List.of()); when(mapper.hdpay(any())).thenReturn(List.of());
        when(mapper.orders(any())).thenReturn(List.of()); when(mapper.trials(any())).thenReturn(List.of());
        when(mapper.refunds(any())).thenReturn(List.of()); when(mapper.freeTrials(any())).thenReturn(List.of());
        when(mapper.unmatched(any())).thenReturn(List.of());
    }
    @Test void requiresExplicitCustomerScope() {
        assertThatThrownBy(() -> service.read(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.read(Arrays.asList(1L,null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.read(List.of(-1L))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyDirectReadAndThreeArgumentCapturedHistoryCannotCertifyEvenValidObservedPayment() {
        var valid=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");when(mapper.cards(any())).thenReturn(List.of(valid));
        for(var result:List.of(service.read(List.of(7L)),service.readWithCapturedHistory(List.of(7L),captured(valid,Source.CARD_TOPUP)))) {
            assertThat(result.facts()).hasSize(1);assertThat(result.issues()).isEmpty();
            assertThat(result.firstHistory()).singleElement().satisfies(h->{assertThat(h.customerId()).isEqualTo(7);assertThat(h.status()).isEqualTo(Status.UNKNOWN);assertThat(h.reasons()).isNotEmpty();});
        }
        var legacyIssue=new Issue(Source.CARD_TOPUP,"unlocated-proof","INVALID_PERSISTED_SOURCE_PROOF");
        assertThat(legacyIssue.customerId()).isNull();
        var unlocated=new SupportPaymentFactService.CapturedFinancialHistory(captured(valid,Source.CARD_TOPUP).candidates(),List.of(legacyIssue),Set.of());
        assertThat(service.readWithCapturedHistory(List.of(7L),unlocated).firstHistory()).singleElement().satisfies(h->assertThat(h.status()).isEqualTo(Status.UNKNOWN));
    }
    @Test void verifiedNewHistoryUsesItsOwnCausalProofWithoutAttestingLifetime() {
        var order=row(Source.WALLET_ORDER,Kind.DEVICE_PURCHASE,201,"order-1","80");
        order.put("orderNo","order-1");order.put("succeededAt",at.plusSeconds(1));
        order.put("sourceConfirmationAt",at.plusSeconds(1));
        when(mapper.orders(any())).thenReturn(List.of(order));
        assertThat(service.read(List.of(7L)).facts()).isEmpty();
        var result=service.readWithCapturedHistory(List.of(7L),captured(order,Source.WALLET_ORDER));
        assertThat(result.facts()).hasSize(1);
        assertThat(result.issues()).isEmpty();
        assertThat(result.coverage()).allMatch(c -> c.historyStatus()==Status.UNKNOWN && c.refundStatus()==
            (Set.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.VIETQR,Source.HDPAY).contains(c.source())?Status.READY:Status.UNKNOWN)
            && c.historicalEnvironmentStatus()==Status.UNKNOWN && c.supportedFrom()==null);
    }
    @Test void verifiedNewHistoryCannotOverrideConflictingLegacyIdentity() {
        var saved=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");
        var changed=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","11");
        when(mapper.cards(any())).thenReturn(List.of(changed));
        var result=service.readWithCapturedHistory(List.of(7L),captured(saved,Source.CARD_TOPUP));
        assertThat(result.facts()).isEmpty();
        assertThat(result.issues()).extracting(Issue::reason).contains("CONFLICTING_CAPTURED_SOURCE_PROJECTION");
    }
    @Test void sourceAndLedgerMismatchCannotBorrowNewTimingPermission() {
        var saved=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");
        var changed=new HashMap<>(saved);changed.put("ledgerAmount",new BigDecimal("9"));
        when(mapper.cards(any())).thenReturn(List.of(changed));
        var result=service.readWithCapturedHistory(List.of(7L),captured(saved,Source.CARD_TOPUP));
        assertThat(result.facts()).isEmpty();
        assertThat(result.issues()).extracting(Issue::reason).contains("SETTLEMENT_MISMATCH");
    }
    @Test void verifiedImmutableHistoryKeepsRefundLineageAndTotalCapInOnePipeline() {
        var order=row(Source.WALLET_ORDER,Kind.DEVICE_PURCHASE,201,"order-1","80");order.put("orderNo","order-1");
        var refund=row(Source.ORDER_REFUND,Kind.DEVICE_PURCHASE_REFUND,301,"E4-REFUND-order-1","81");refund.put("orderNo","order-1");
        var saved=new SupportPaymentFactService.CapturedFinancialHistory(List.of(
            new SupportPaymentFactService.VerifiedCandidate(SupportPaymentFactService.fact(order,Source.WALLET_ORDER),"order-1"),
            new SupportPaymentFactService.VerifiedCandidate(SupportPaymentFactService.fact(refund,Source.ORDER_REFUND),"E4-REFUND-order-1")),List.of(),Set.of());
        var result=service.readWithCapturedHistory(List.of(7L),saved);
        assertThat(result.facts()).hasSize(1).allMatch(f -> f.kind()==Kind.DEVICE_PURCHASE);
        assertThat(result.issues()).extracting(Issue::reason).contains("REFUND_EXCEEDS_ORIGINAL_AMOUNT");
    }
    @Test void verifiedSourceMirrorsCannotDoubleCountSameLogicalPayment() {
        var viet=row(Source.VIETQR,Kind.DEPOSIT,101,"D1-VIETQR-rec-1","10");
        var hosted=row(Source.HDPAY,Kind.DEPOSIT,102,"intent-1","10");
        var saved=new SupportPaymentFactService.CapturedFinancialHistory(List.of(
            new SupportPaymentFactService.VerifiedCandidate(SupportPaymentFactService.fact(viet,Source.VIETQR),"intent-1"),
            new SupportPaymentFactService.VerifiedCandidate(SupportPaymentFactService.fact(hosted,Source.HDPAY),"intent-1")),List.of(),Set.of());
        var result=service.readWithCapturedHistory(List.of(7L),saved);
        assertThat(result.facts()).isEmpty();
        assertThat(result.issues()).extracting(Issue::reason).contains("DUPLICATE_PAYMENT_SOURCE");
    }
    @Test void malformedCapturedProofCannotHideAValidLegacyObservation() {
        var valid=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");when(mapper.cards(any())).thenReturn(List.of(valid));
        var saved=new SupportPaymentFactService.CapturedFinancialHistory(List.of(),
            List.of(new Issue(Source.CARD_TOPUP,"proof-1","INVALID_PERSISTED_SOURCE_PROOF")),Set.of());
        var result=service.readWithCapturedHistory(List.of(7L),saved);
        assertThat(result.facts()).hasSize(1);
        assertThat(result.coverage()).filteredOn(c -> c.source()==Source.CARD_TOPUP)
            .extracting(Coverage::observedStatus).containsExactly(Status.UNKNOWN);
    }
    @Test void conflictedCapturedIdentityRejectsItsLegacyProjectionToo() {
        var valid=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");when(mapper.cards(any())).thenReturn(List.of(valid));
        var saved=new SupportPaymentFactService.CapturedFinancialHistory(List.of(),
            List.of(new Issue(Source.CARD_TOPUP,"proof-1","CAPTURED_SOURCE_PROOF_MISMATCH")),Set.of("DEPOSIT:101"));
        assertThat(service.readWithCapturedHistory(List.of(7L),saved).facts()).isEmpty();
    }
    @Test void capturedHistoryCannotReadAnUnrequestedCustomer() {
        var foreign=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");foreign.put("customerId",8L);
        assertThatThrownBy(() -> service.readWithCapturedHistory(List.of(7L),captured(foreign,Source.CARD_TOPUP)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Captured customer outside explicit scope");
    }
    @ParameterizedTest
    @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER","ORDER_REFUND","GENESIS_PRIMARY","GENESIS_SECONDARY"},mode=EnumSource.Mode.EXCLUDE)
    void verifiedFinancialRootResolvesOnlyItsOwnUnmatchedLedger(Source source) {
        Kind kind=Set.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.VIETQR,Source.HDPAY).contains(source)
            ?Kind.DEPOSIT:Kind.DEVICE_PURCHASE;
        var valid=row(source,kind,101,"payment-1","10");
        if(kind==Kind.DEVICE_PURCHASE)valid.put("orderNo","order-1");
        var unmatched=new HashMap<>(valid);unmatched.put("source","UNMATCHED_LEDGER");
        unmatched.put("sourceId","nx_wallet_ledger:101");
        when(mapper.unmatched(any())).thenReturn(List.of(unmatched));
        var result=service.readWithCapturedHistory(List.of(7L),captured(valid,source));
        assertThat(result.facts()).hasSize(1);
        assertThat(result.issues()).isEmpty();
        assertThat(result.coverage()).allMatch(c -> c.historyStatus()==Status.UNKNOWN && c.refundStatus()==
            (Set.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.VIETQR,Source.HDPAY).contains(c.source())?Status.READY:Status.UNKNOWN));
    }
    @Test void independentlyValidLegacyRootAlsoResolvesItsMatchingLedger() {
        var valid=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");
        var unmatched=new HashMap<>(valid);unmatched.put("source","UNMATCHED_LEDGER");
        when(mapper.cards(any())).thenReturn(List.of(valid));when(mapper.unmatched(any())).thenReturn(List.of(unmatched));
        var result=service.read(List.of(7L));
        assertThat(result.facts()).hasSize(1);assertThat(result.issues()).isEmpty();
    }
    @ParameterizedTest
    @ValueSource(strings={"ledgerId","missingLedgerId","customerId","kind","businessId","currency","amount"})
    void anUnmatchedLedgerWithAnyDifferentFinancialIdentityRemainsUnknown(String changed) {
        var valid=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");
        var unmatched=new HashMap<>(valid);unmatched.put("source","UNMATCHED_LEDGER");
        switch(changed) {
            case "ledgerId" -> unmatched.put(changed,102L);
            case "missingLedgerId" -> unmatched.remove("ledgerId");
            case "customerId" -> unmatched.put(changed,8L);
            case "kind" -> unmatched.put(changed,"DEVICE_PURCHASE");
            case "businessId" -> unmatched.put(changed,"other-payment");
            case "currency" -> unmatched.put(changed,"NEX");
            case "amount" -> unmatched.put(changed,new BigDecimal("10.000001"));
            default -> throw new IllegalArgumentException(changed);
        }
        when(mapper.unmatched(any())).thenReturn(List.of(unmatched));
        if(changed.equals("customerId")) {
            assertThatThrownBy(()->service.readWithCapturedHistory(List.of(7L),captured(valid,Source.CARD_TOPUP)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Source customer outside explicit scope");
            return;
        }
        var result=service.readWithCapturedHistory(List.of(7L),captured(valid,Source.CARD_TOPUP));
        assertThat(result.facts()).hasSize(1);
        assertThat(result.issues()).extracting(Issue::reason).containsExactly("MISSING_AUTHORITATIVE_SOURCE");
    }
    @Test void aRejectedCapturedFactCannotHideAnUnmatchedLedgerOrAnotherRealLedger() {
        var valid=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");
        var unmatched=new HashMap<>(valid);unmatched.put("source","UNMATCHED_LEDGER");unmatched.put("sourceId","nx_wallet_ledger:101");
        var other=new HashMap<>(unmatched);other.put("ledgerId",102L);other.put("sourceId","nx_wallet_ledger:102");
        when(mapper.unmatched(any())).thenReturn(List.of(unmatched,other));
        var rejected=new SupportPaymentFactService.CapturedFinancialHistory(captured(valid,Source.CARD_TOPUP).candidates(),
            List.of(new Issue(Source.CARD_TOPUP,"proof-1","CAPTURED_SOURCE_PROOF_MISMATCH")),Set.of("DEPOSIT:101"));
        var result=service.readWithCapturedHistory(List.of(7L),rejected);
        assertThat(result.facts()).isEmpty();
        assertThat(result.issues()).filteredOn(i -> i.source()==Source.UNMATCHED_LEDGER)
            .extracting(Issue::sourceId).containsExactly("nx_wallet_ledger:101","nx_wallet_ledger:102");
        var accepted=service.readWithCapturedHistory(List.of(7L),captured(valid,Source.CARD_TOPUP));
        assertThat(accepted.facts()).hasSize(1);
        assertThat(accepted.issues()).extracting(Issue::sourceId).containsExactly("nx_wallet_ledger:102");
    }
    @Test void aLaterDuplicatePaymentRejectionCannotHideItsMissingSource() {
        var first=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");
        var second=row(Source.CARD_TOPUP,Kind.DEPOSIT,102,"card-1","10");
        var unmatched=new HashMap<>(first);unmatched.put("source","UNMATCHED_LEDGER");
        when(mapper.cards(any())).thenReturn(List.of(second));when(mapper.unmatched(any())).thenReturn(List.of(unmatched));
        var result=service.readWithCapturedHistory(List.of(7L),captured(first,Source.CARD_TOPUP));
        assertThat(result.facts()).isEmpty();
        assertThat(result.issues()).extracting(Issue::reason).contains("DUPLICATE_PAYMENT_SOURCE","MISSING_AUTHORITATIVE_SOURCE");
    }
    private SupportPaymentFactService.CapturedFinancialHistory captured(Map<String,Object> row,Source source) {
        return new SupportPaymentFactService.CapturedFinancialHistory(
            List.of(new SupportPaymentFactService.VerifiedCandidate(SupportPaymentFactService.fact(row,source),row.get("duplicateKey").toString())),
            List.of(),Set.of());
    }
    @Test void usesActualSourceTimeExactDecimalsAndKeepsProviderTimeSeparate() {
        var row = row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","100.123456");
        row.put("providerPaidAt",at.minusDays(1));
        when(mapper.cards(any())).thenReturn(List.of(row));
        var result = service.read(List.of(7L));
        assertThat(result.facts()).hasSize(1);
        var fact=result.facts().get(0);
        assertThat(fact.factId()).isEqualTo("DEPOSIT:101");
        assertThat(fact.succeededAt()).isEqualTo(at);
        assertThat(fact.providerPaidAt()).isEqualTo(at.minusDays(1));
        assertThat(fact.amount()).isEqualByComparingTo("100.123456");
        assertThat(result.businessZone()).isEqualTo("Asia/Shanghai");
        assertThat(fact.historicalEnvironmentStatus()).isEqualTo(Status.UNKNOWN);
    }
    @Test void repeatedSourceProjectionsAndCallbacksDoNotMultiplyFacts() {
        var first=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");
        var replay=new HashMap<>(first); replay.put("sourceId","payment:second-projection");
        when(mapper.cards(any())).thenReturn(List.of(first,replay,first));
        var snapshot=service.read(List.of(7L));
        assertThat(snapshot.facts()).hasSize(1);
        assertThat(snapshot.facts().get(0).sourceIds()).hasSize(2);
    }
    @Test void genuineSecondSettlementForSameLogicalPaymentIsUnknown() {
        when(mapper.cards(any())).thenReturn(List.of(row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10"),
            row(Source.CARD_TOPUP,Kind.DEPOSIT,102,"card-1","10")));
        var snapshot=service.read(List.of(7L));
        assertThat(snapshot.facts()).isEmpty();
        assertThat(snapshot.issues()).extracting(Issue::reason).contains("DUPLICATE_PAYMENT_SOURCE");
    }
    @Test void actualTrialChargeIsPurchaseAndFreeClaimOrZeroVoucherDoesNotCount() {
        var paid=row(Source.TRIAL_CONVERT,Kind.DEVICE_PURCHASE,201,"claim-1:CHARGE","80");
        paid.put("orderNo","trial-order");paid.put("orderType","TRIAL_CONVERT");
        var zero=row(Source.WALLET_ORDER,Kind.DEVICE_PURCHASE,0,"voucher","0");
        when(mapper.trials(any())).thenReturn(List.of(paid)); when(mapper.orders(any())).thenReturn(List.of(zero));
        when(mapper.freeTrials(any())).thenReturn(List.of(Map.of("source","FREE_TRIAL","sourceId","claim:free")));
        var snapshot=service.read(List.of(7L));
        assertThat(snapshot.facts()).hasSize(1);
        assertThat(snapshot.facts().get(0).kind()).isEqualTo(Kind.DEVICE_PURCHASE);
        assertThat(snapshot.facts().get(0).factId()).isEqualTo("PURCHASE:trial-order");
        assertThat(snapshot.issues()).isEmpty();
        assertThat(snapshot.coverage()).filteredOn(c -> c.source()==Source.FREE_TRIAL)
            .extracting(Coverage::excludedFreeOrNonProductionRows).containsExactly(1L);
    }
    @Test void duplicateLegacyTrialOrdersCannotReuseOneCharge() {
        var first=row(Source.TRIAL_CONVERT,Kind.DEVICE_PURCHASE,201,"claim-1:CHARGE","80");first.put("orderNo","legacy-a");
        var second=new HashMap<>(first);second.put("orderNo","legacy-b");second.put("sourceId","order:b");
        when(mapper.trials(any())).thenReturn(List.of(first,second));
        var snapshot=service.read(List.of(7L));
        assertThat(snapshot.facts()).isEmpty();
        assertThat(snapshot.issues()).extracting(Issue::reason).contains("DUPLICATE_SETTLEMENT_LEDGER");
    }
    @Test void missingTimeLinkCurrencyOrSuccessfulStatusDoesNotBecomeFact() {
        var noTime=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"a","10");noTime.remove("succeededAt");
        var badCurrency=row(Source.CARD_TOPUP,Kind.DEPOSIT,102,"b","10");badCurrency.put("ledgerCurrency","NEX");
        var badLink=row(Source.CARD_TOPUP,Kind.DEPOSIT,103,"c","10");badLink.put("sourceLinked",0);
        var badState=row(Source.CARD_TOPUP,Kind.DEPOSIT,104,"d","10");badState.put("ledgerStatus","PENDING");
        var noLedgerTime=row(Source.CARD_TOPUP,Kind.DEPOSIT,105,"e","10");noLedgerTime.remove("ledgerRecordedAt");
        var drift=row(Source.CARD_TOPUP,Kind.DEPOSIT,106,"f","10");drift.put("ledgerRecordedAt",at.minusDays(2));
        var noConfirmation=row(Source.WALLET_ORDER,Kind.DEVICE_PURCHASE,107,"g","10");noConfirmation.remove("sourceConfirmationAt");
        var claimDrift=row(Source.TRIAL_CONVERT,Kind.DEVICE_PURCHASE,108,"h:CHARGE","10");claimDrift.put("sourceConfirmationAt",at.minusDays(3));
        when(mapper.cards(any())).thenReturn(List.of(noTime,badCurrency,badLink,badState,noLedgerTime,drift));
        when(mapper.orders(any())).thenReturn(List.of(noConfirmation));when(mapper.trials(any())).thenReturn(List.of(claimDrift));
        var snapshot=service.read(List.of(7L));
        assertThat(snapshot.facts()).isEmpty();
        assertThat(snapshot.issues()).extracting(Issue::reason).containsExactlyInAnyOrder(
            "MISSING_SUCCESS_TIME","SETTLEMENT_MISMATCH","BROKEN_SOURCE_LINK","UNSUCCESSFUL_SETTLEMENT",
            "MISSING_SETTLEMENT_TIME","CONFLICTING_SUCCESS_TIME","MISSING_SOURCE_CONFIRMATION_TIME","CONFLICTING_SUCCESS_TIME");
    }
    @Test void latePartialRefundsKeepOriginalAndDeduplicateReplay() {
        var order=row(Source.WALLET_ORDER,Kind.DEVICE_PURCHASE,201,"order-1","80");order.put("orderNo","order-1");
        var refund=row(Source.ORDER_REFUND,Kind.DEVICE_PURCHASE_REFUND,301,"E4-REFUND-order-1","30");
        refund.put("orderNo","order-1");refund.put("succeededAt",at.plusDays(3));refund.put("ledgerRecordedAt",at.plusDays(3));
        when(mapper.orders(any())).thenReturn(List.of(order)); when(mapper.refunds(any())).thenReturn(List.of(refund,refund));
        var snapshot=service.read(List.of(7L));
        assertThat(snapshot.facts()).hasSize(2);
        assertThat(snapshot.facts()).filteredOn(f -> f.kind()==Kind.DEVICE_PURCHASE_REFUND)
            .extracting(Fact::originalFactId).containsExactly("PURCHASE:order-1");
        assertThat(snapshot.facts()).filteredOn(f -> f.kind()==Kind.DEVICE_PURCHASE).extracting(Fact::amount)
            .containsExactly(new BigDecimal("80"));
    }
    @Test void refundBeforeOriginalPaymentIsUnknown() {
        var order=row(Source.WALLET_ORDER,Kind.DEVICE_PURCHASE,201,"order-1","80");order.put("orderNo","order-1");
        var refund=row(Source.ORDER_REFUND,Kind.DEVICE_PURCHASE_REFUND,301,"E4-REFUND-order-1","30");
        refund.put("orderNo","order-1");refund.put("succeededAt",at.minusSeconds(1));refund.put("ledgerRecordedAt",at.minusSeconds(1));
        when(mapper.orders(any())).thenReturn(List.of(order));when(mapper.refunds(any())).thenReturn(List.of(refund));
        var snapshot=service.read(List.of(7L));
        assertThat(snapshot.facts()).hasSize(1).allMatch(f -> f.kind()==Kind.DEVICE_PURCHASE);
        assertThat(snapshot.issues()).containsExactly(new Issue(Source.ORDER_REFUND,"ORDER_REFUND:301","REFUND_PREDATES_ORIGINAL_PAYMENT",7L));
        assertThat(snapshot.coverage()).filteredOn(c -> c.source()==Source.ORDER_REFUND)
            .extracting(Coverage::observedStatus).containsExactly(Status.UNKNOWN);
    }
    @Test void refundInSameRecordedSecondRetainsLowerPrecisionLedgerTime() {
        var order=row(Source.WALLET_ORDER,Kind.DEVICE_PURCHASE,201,"order-1","80");order.put("orderNo","order-1");
        order.put("succeededAt",at.withNano(600_000_000));order.put("sourceConfirmationAt",at.withNano(600_000_000));
        var refund=row(Source.ORDER_REFUND,Kind.DEVICE_PURCHASE_REFUND,301,"E4-REFUND-order-1","30");refund.put("orderNo","order-1");
        when(mapper.orders(any())).thenReturn(List.of(order));when(mapper.refunds(any())).thenReturn(List.of(refund));
        var snapshot=service.read(List.of(7L));
        assertThat(snapshot.facts()).hasSize(2);assertThat(snapshot.issues()).isEmpty();
        assertThat(snapshot.facts()).filteredOn(f -> f.kind()==Kind.DEVICE_PURCHASE_REFUND)
            .extracting(Fact::succeededAt).containsExactly(at);
    }
    @Test void refundWithoutOriginalOrAboveOriginalOrWrongCurrencyIsUnknown() {
        var order=row(Source.WALLET_ORDER,Kind.DEVICE_PURCHASE,201,"order-1","80");order.put("orderNo","order-1");
        var tooMuch=row(Source.ORDER_REFUND,Kind.DEVICE_PURCHASE_REFUND,301,"E4-REFUND-order-1","81");tooMuch.put("orderNo","order-1");
        var missing=row(Source.ORDER_REFUND,Kind.DEVICE_PURCHASE_REFUND,302,"E4-REFUND-missing","10");missing.put("orderNo","missing");
        var wrong=row(Source.ORDER_REFUND,Kind.DEVICE_PURCHASE_REFUND,303,"E4-REFUND-order-2","10");wrong.put("orderNo","order-1");wrong.put("currency","NEX");wrong.put("ledgerCurrency","NEX");
        when(mapper.orders(any())).thenReturn(List.of(order));when(mapper.refunds(any())).thenReturn(List.of(tooMuch,missing,wrong));
        var snapshot=service.read(List.of(7L));
        assertThat(snapshot.facts()).hasSize(1);
        assertThat(snapshot.issues()).extracting(Issue::reason).contains("UNPROVEN_ORIGINAL_PAYMENT","REFUND_EXCEEDS_ORIGINAL_AMOUNT");
    }
    @Test void nonRefundableDepositPolicyDoesNotCertifyPaymentHistory() {
        when(mapper.cards(any())).thenReturn(List.of(row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10")));
        var snapshot=service.read(List.of(7L));
        assertThat(snapshot.coverage()).filteredOn(c -> c.source()==Source.CARD_TOPUP)
            .allSatisfy(c -> {assertThat(c.refundStatus()).isEqualTo(Status.READY);assertThat(c.historyStatus()).isEqualTo(Status.UNKNOWN);
                assertThat(c.supportedFrom()).isNull();assertThat(c.reasons()).contains("SUCCESSFUL_DEPOSITS_NON_REFUNDABLE").doesNotContain("FINAL_DEPOSIT_REFUND_SOURCE_UNAVAILABLE");});
        assertThat(snapshot.facts()).noneMatch(f -> f.kind()==Kind.DEVICE_PURCHASE_REFUND);
    }
    @Test void failedSourceIsVisibleAndDoesNotExposePrivateExceptionOrInventZero() {
        when(mapper.hdpay(any())).thenThrow(new DataAccessResourceFailureException("private credentials"));
        var snapshot=service.read(List.of(7L));
        assertThat(snapshot.issues()).containsExactly(new Issue(Source.HDPAY,null,"SOURCE_READ_FAILED"));
        assertThat(snapshot.coverage()).filteredOn(c -> c.source()==Source.HDPAY)
            .extracting(Coverage::observedStatus).containsExactly(Status.UNKNOWN);
        assertThat(snapshot.toString()).doesNotContain("private credentials");
    }
    @Test void knownNonProductionPaymentIsExcludedRatherThanCalledProduction() {
        var row=row(Source.WALLET_ORDER,Kind.DEVICE_PURCHASE,101,"test","10");row.put("excludedEnvironment",1);
        when(mapper.orders(any())).thenReturn(List.of(row));
        assertThat(service.read(List.of(7L)).facts()).isEmpty();
    }
    private Map<String,Object> row(Source source,Kind kind,long ledger,String business,String amount) {
        var r=new HashMap<String,Object>();r.put("source",source.name());r.put("kind",kind.name());r.put("sourceId",source+":"+ledger);
        r.put("customerId",7L);r.put("ledgerCustomerId",7L);r.put("ledgerId",ledger);r.put("businessId",business);
        r.put("duplicateKey",business);r.put("ledgerBusinessId",business);r.put("currency","USDT");r.put("ledgerCurrency","USDT");
        r.put("amount",new BigDecimal(amount));r.put("ledgerAmount",new BigDecimal(amount));r.put("succeededAt",at);
        r.put("ledgerRecordedAt",at);r.put("sourceConfirmationAt",at);
        r.put("sourceLinked",1);r.put("ledgerDeleted",0);r.put("fractionalSecondDigits",0);r.put("successTimeField","nx_wallet_ledger.created_at");
        r.put("ledgerDirection",kind==Kind.DEVICE_PURCHASE?"OUT":"IN");r.put("ledgerStatus",source==Source.TRIAL_CONVERT?"POSTED":"SUCCESS");
        r.put("ledgerType",switch(source){case CARD_TOPUP->"CARD_TOPUP";case ORDER_REFUND->"ORDER_REFUND";
            case TRIAL_CONVERT->"TRIAL_CHARGE";case TRADE_IN->"TRADE_IN_PURCHASE";case CAPACITY_KEEP->"DEVICE_PURCHASE";
            case VIETQR,HDPAY->"VIETQR_DEPOSIT";case DEPOSIT_ORDER->"CHAIN_TOPUP";default->"ORDER_PURCHASE";});
        return r;
    }
    private SupportPaymentFactService.CapturedFinancialHistory proven(List<Map<String,Object>> rows,List<Map<String,Object>> origins) {
        var candidates=rows.stream().map(r -> new SupportPaymentFactService.VerifiedCandidate(
            SupportPaymentFactService.fact(r,Source.valueOf(r.get("source").toString())),r.get("businessId").toString())).toList();
        return new SupportPaymentFactService.CapturedFinancialHistory(candidates,List.of(),Set.of(),Map.of(7L,List.of()),
            Map.of(7L,at.minusDays(1)),candidates.stream().map(c->c.fact().factId()).collect(java.util.stream.Collectors.toSet()),origins);
    }
    private CustomerCoverage scoped(Snapshot snapshot,long customer,Source source) {
        return snapshot.customerCoverage().stream().filter(c->c.customerId()==customer && c.source()==source).findFirst().orElseThrow();
    }
    @Test void knownZeroBelongsToTheProvenCustomerAndNeverErasesALegacyCustomersGap() {
        var result=service.readWithCapturedHistory(List.of(7L,8L),proven(List.of(),List.of()));
        assertThat(scoped(result,7,Source.CARD_TOPUP).historyStatus()).isEqualTo(Status.READY);
        assertThat(scoped(result,7,Source.CARD_TOPUP).historicalEnvironmentStatus()).isEqualTo(Status.READY);
        assertThat(scoped(result,8,Source.CARD_TOPUP).historyStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(result.coverage()).filteredOn(c->c.source()==Source.CARD_TOPUP).allMatch(c->c.historyStatus()==Status.UNKNOWN);
    }
    @Test void everyObservedPaymentNeedsItsOwnNewProofEvenWhenBirthIsReal() {
        var card=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");when(mapper.cards(any())).thenReturn(List.of(card));
        var result=service.readWithCapturedHistory(List.of(7L),proven(List.of(),List.of()));
        assertThat(result.facts()).hasSize(1);
        assertThat(scoped(result,7,Source.CARD_TOPUP).reasons()).contains("PAYMENT_NEW_SUCCESS_NOT_PROVEN");
        assertThat(scoped(result,7,Source.WALLET_ORDER).historyStatus()).isEqualTo(Status.READY);
    }
    @Test void conflictingCaptureCannotVanishAndLeaveAProvenZero() {
        var card=row(Source.CARD_TOPUP,Kind.DEPOSIT,101,"card-1","10");var bad=new HashMap<>(card);bad.put("ledgerAmount",new BigDecimal("9"));
        when(mapper.cards(any())).thenReturn(List.of(bad));
        var result=service.readWithCapturedHistory(List.of(7L),proven(List.of(card),List.of()));
        assertThat(result.facts()).isEmpty();assertThat(scoped(result,7,Source.CARD_TOPUP).reasons()).contains("CAPTURED_PAYMENT_NOT_RECONCILED");
    }
    @Test void firstHistoryDoesNotProveRefundActorAndOriginalStatusHistory() {
        var order=row(Source.WALLET_ORDER,Kind.DEVICE_PURCHASE,201,"order-1","80");order.put("orderNo","order-1");
        var refund=row(Source.ORDER_REFUND,Kind.DEVICE_PURCHASE_REFUND,301,"E4-REFUND-order-1","80");refund.put("orderNo","order-1");
        when(mapper.orders(any())).thenReturn(List.of(order));when(mapper.refunds(any())).thenReturn(List.of(refund));
        var result=service.readWithCapturedHistory(List.of(7L),proven(List.of(order,refund),List.of()));
        assertThat(result.firstHistory()).containsExactly(new FirstHistory(7,Status.READY,List.of()));
        assertThat(scoped(result,7,Source.ORDER_REFUND).historyStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(scoped(result,7,Source.WALLET_ORDER).refundStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(result.facts()).hasSize(2);
    }
    @Test void onlySuccessfulFullBackendOriginalRefundCanCertifyNetAndDoesNotRemoveFirstPayment() {
        var order=row(Source.WALLET_ORDER,Kind.DEVICE_PURCHASE,201,"order-1","80");order.put("orderNo","order-1");
        var refund=row(Source.ORDER_REFUND,Kind.DEVICE_PURCHASE_REFUND,301,"E4-REFUND-order-1","80");refund.put("orderNo","order-1");
        when(mapper.orders(any())).thenReturn(List.of(order));when(mapper.refunds(any())).thenReturn(List.of(refund));
        var origin=Map.<String,Object>of("customerId",7L,"orderNo","order-1","amount",new BigDecimal("80"),"ledgerId",301L,"ledgerAt",at,"proofCount",1);
        var result=service.readWithCapturedHistory(List.of(7L),proven(List.of(order,refund),List.of(origin)));
        assertThat(scoped(result,7,Source.ORDER_REFUND).historyStatus()).isEqualTo(Status.READY);
        assertThat(result.facts()).filteredOn(f->f.kind()==Kind.DEVICE_PURCHASE).hasSize(1);
        assertThat(result.firstHistory()).containsExactly(new FirstHistory(7,Status.READY,List.of()));
        for(String changed:List.of("actorProof","amount","ledger","time")) {
            var bad=new HashMap<>(origin);
            switch(changed) {case "actorProof"->bad.put("proofCount",0);case "amount"->bad.put("amount",new BigDecimal("79"));
                case "ledger"->bad.put("ledgerId",302L);case "time"->bad.put("ledgerAt",at.plusSeconds(1));}
            var rejected=service.readWithCapturedHistory(List.of(7L),proven(List.of(order,refund),List.of(bad)));
            assertThat(scoped(rejected,7,Source.ORDER_REFUND).historyStatus()).as(changed).isEqualTo(Status.UNKNOWN);
        }
    }
    @Test void backendSuccessWithoutItsLedgerCannotBeHiddenByAnEmptyRefundFactList() {
        var origin=Map.<String,Object>of("customerId",7L,"orderNo","order-1","amount",new BigDecimal("80"),"proofCount",0);
        var result=service.readWithCapturedHistory(List.of(7L),proven(List.of(),List.of(origin)));
        assertThat(scoped(result,7,Source.ORDER_REFUND).reasons()).contains("BACKEND_REFUND_NOT_RECONCILED");
        assertThat(result.firstHistory()).containsExactly(new FirstHistory(7,Status.READY,List.of()));
    }
    @Test void refundReadFailureDoesNotDowngradeDepositButDepositReadFailureDoesNotDowngradePurchase() {
        when(mapper.refunds(any())).thenThrow(new DataAccessResourceFailureException("private"));
        var result=service.readWithCapturedHistory(List.of(7L),proven(List.of(),List.of()));
        assertThat(scoped(result,7,Source.CARD_TOPUP).historyStatus()).isEqualTo(Status.READY);
        assertThat(scoped(result,7,Source.CARD_TOPUP).historicalEnvironmentStatus()).isEqualTo(Status.READY);
        assertThat(scoped(result,7,Source.ORDER_REFUND).historyStatus()).isEqualTo(Status.UNKNOWN);
        doReturn(List.of()).when(mapper).refunds(any());when(mapper.cards(any())).thenThrow(new DataAccessResourceFailureException("private"));
        var depositFailure=service.readWithCapturedHistory(List.of(7L),proven(List.of(),List.of()));
        assertThat(scoped(depositFailure,7,Source.CARD_TOPUP).historyStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(scoped(depositFailure,7,Source.WALLET_ORDER).historyStatus()).isEqualTo(Status.READY);
        assertThat(scoped(depositFailure,7,Source.ORDER_REFUND).historyStatus()).isEqualTo(Status.READY);
    }
    @Test void unmatchedDepositCannotTurnDepositIntoZeroAndDoesNotPollutePurchaseOnlyCoverage() {
        when(mapper.unmatched(any())).thenReturn(List.of(Map.of("customerId",7L,"source","UNMATCHED_LEDGER","sourceId","nx_wallet_ledger:9","kind","DEPOSIT")));
        var result=service.readWithCapturedHistory(List.of(7L),proven(List.of(),List.of()));
        assertThat(scoped(result,7,Source.CARD_TOPUP).historyStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(scoped(result,7,Source.WALLET_ORDER).historyStatus()).isEqualTo(Status.READY);
        assertThat(result.firstHistory()).singleElement().satisfies(h->assertThat(h.status()).isEqualTo(Status.UNKNOWN));
    }
}
