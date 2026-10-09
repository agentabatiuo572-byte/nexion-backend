package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionRow;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Fact;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Kind;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Snapshot;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Trusted finance façade fixtures test aggregation, not new source-success or coverage attestations. */
class SupportAnalyticsServiceTest {
    private static final LocalDateTime AT=LocalDateTime.of(2026,10,9,12,0);
    private static final ReadScope PERSONAL=new ReadScope(7L,ReadMode.PERSONAL,null,null);
    private final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    private final FinanceSupportPaymentFactsFacade finance=mock(FinanceSupportPaymentFactsFacade.class);
    private final SupportAnalyticsMapper mapper=mock(SupportAnalyticsMapper.class);
    private final SupportAnalyticsService service=new SupportAnalyticsService(ownership,finance,mapper);

    @BeforeEach void scope() {
        when(ownership.queryScope(ReadMode.PERSONAL,null,null)).thenReturn(PERSONAL);
        when(ownership.defaultQueryScope(null,null)).thenReturn(PERSONAL);
        when(mapper.currentCustomers(any())).thenReturn(List.of(current(1)));
        when(mapper.eventCandidates(any())).thenReturn(List.of());
        when(mapper.attributions(any(),any())).thenReturn(List.of());
    }
    @Test void typedFinanceHistoryDistinguishesConfirmedNoneAndUnknownWithoutTurningSubsetIntoTotal() {
        when(mapper.currentCustomers(any())).thenReturn(List.of(current(1),current(2),current(3)));
        var deposit=fact(1,101,Kind.DEPOSIT,"USDT","10.123456",AT,null);
        var unproven=fact(3,301,Kind.DEVICE_PURCHASE,"USDT","20.654321",AT,null);
        when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(deposit,unproven),List.of(
            new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.READY,List.of()),
            new SupportPaymentFacts.FirstHistory(2,SupportPaymentFacts.Status.READY,List.of()),
            new SupportPaymentFacts.FirstHistory(3,SupportPaymentFacts.Status.UNKNOWN,List.of("PAYMENT_NEW_SUCCESS_NOT_PROVEN")))));
        var result=service.summarize(history("USDT"));
        assertThat(result.currentCustomers()).extracting(c->c.first().state()).containsExactly(FirstState.CONFIRMED,FirstState.NONE,FirstState.UNKNOWN);
        assertThat(result.currentCustomers().get(1).first().observedCandidate()).isNull();
        assertThat(result.financialSummary().firstCandidates()).isEqualTo(new Count(2L,1L,Status.PARTIAL));
        var first=result.financialSummary().firstSources().get(0);
        assertThat(first.deposits().confirmedAmount()).isEqualByComparingTo("10.123456");assertThat(first.deposits().confirmedEvents()).isEqualTo(1);
        assertThat(first.purchases().observedAmount()).isEqualByComparingTo("20.654321");assertThat(first.purchases().confirmedEvents()).isZero();
        assertThat(result.financialSummary().currencies().get(0).net().confirmedAmount()).isNull();
    }
    @Test void completeNoPaymentIsNoneAndMissingFirstHistoryIsUnknownEvenWithEmptyIssues() {
        when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(),List.of(new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.READY,List.of()))));
        var none=service.summarize(history("USDT"));
        assertThat(none.currentCustomers().get(0).first().state()).isEqualTo(FirstState.NONE);
        assertThat(none.financialSummary().firstCandidates()).isEqualTo(new Count(0L,0L,Status.AVAILABLE));
        when(finance.readHistory(any())).thenReturn(snapshot(List.of()));
        var unknown=service.summarize(history("USDT"));assertThat(unknown.currentCustomers().get(0).first().state()).isEqualTo(FirstState.UNKNOWN);
        assertThat(unknown.financialSummary().firstCandidates().confirmedValue()).isNull();
    }
    @Test void provenFirstUsesFullHistoryBeforeCurrencyOrPeriodAndStableTieBeforeSummingSourceAmounts() {
        var earliest=fact(1,100,Kind.DEVICE_PURCHASE,"NEX","7.123456",AT.minusDays(1),null);
        var later=fact(1,101,Kind.DEPOSIT,"USDT","10.654321",AT,null);
        var proof=List.of(new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.READY,List.of()));
        when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(later,earliest),proof));events(List.of(earliest,later));
        var filtered=service.summarize(period(ReadMode.PERSONAL,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(filtered.currentCustomers().get(0).first().state()).isEqualTo(FirstState.CONFIRMED);
        assertThat(filtered.currentCustomers().get(0).first().observedCandidate().currency()).isEqualTo("NEX");
        assertThat(filtered.financialSummary().firstCandidates().confirmedValue()).isZero();
        when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(later,earliest),proof),withFirstHistory(List.of(earliest,later),proof));
        var first=service.summarize(history(null));var reversed=service.summarize(history(null));
        assertThat(reversed.financialSummary().firstSources()).isEqualTo(first.financialSummary().firstSources());
        assertThat(first.financialSummary().firstSources()).filteredOn(c->c.currency().equals("NEX")).singleElement().satisfies(c->{assertThat(c.purchases().confirmedAmount()).isEqualByComparingTo("7.123456");assertThat(c.deposits().confirmedEvents()).isZero();});
        assertThat(first.financialSummary().firstSources()).filteredOn(c->c.currency().equals("USDT")).singleElement().satisfies(c->assertThat(c.deposits().confirmedEvents()).isZero());
        var purchase=fact(1,30,Kind.DEVICE_PURCHASE,"USDT","30",AT,null);var d2=fact(1,2,Kind.DEPOSIT,"USDT","2",AT,null);var d11=fact(1,11,Kind.DEPOSIT,"USDT","11",AT,null);
        when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(purchase,d2,d11),proof),withFirstHistory(List.of(d11,d2,purchase),proof));
        var stable=service.summarize(history(null));var swapped=service.summarize(history(null));
        assertThat(stable.currentCustomers().get(0).first().observedCandidate().amount()).isEqualByComparingTo("11");
        assertThat(swapped.currentCustomers().get(0).first()).isEqualTo(stable.currentCustomers().get(0).first());
        assertThat(stable.financialSummary().firstCandidates().confirmedValue()).isEqualTo(1);
    }
    @Test void firstSourceTotalsKeepExactDepositAndPurchaseAmountsSeparateAndIgnoreLaterPayments() {
        when(mapper.currentCustomers(any())).thenReturn(List.of(current(1),current(2)));
        var deposit=fact(1,10,Kind.DEPOSIT,"USDT","1.123456",AT.minusDays(1),null);
        var later=fact(1,11,Kind.DEVICE_PURCHASE,"USDT","50.987654",AT,null);
        var purchase=fact(2,20,Kind.DEVICE_PURCHASE,"USDT","2.654321",AT.minusDays(1),null);
        when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(later,purchase,deposit),List.of(
            new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.READY,List.of()),new SupportPaymentFacts.FirstHistory(2,SupportPaymentFacts.Status.READY,List.of()))));
        var result=service.summarize(history("USDT"));var first=result.financialSummary().firstSources().get(0);
        assertThat(first.deposits().confirmedAmount()).isEqualByComparingTo("1.123456");assertThat(first.purchases().confirmedAmount()).isEqualByComparingTo("2.654321");
        assertThat(first.deposits().confirmedEvents()).isEqualTo(1);assertThat(first.purchases().confirmedEvents()).isEqualTo(1);
        assertThat(result.financialSummary().firstCandidates().confirmedValue()).isEqualTo(2);
        assertThat(result.financialSummary().currencies().get(0).purchases().observedAmount()).isEqualByComparingTo("53.641975");
    }
    @Test void reconciledEarlierBackfillChangesTheConfirmedFirstWithoutCreatingAnotherCustomer() {
        var later=fact(1,101,Kind.DEPOSIT,"USDT","10.123456",AT,null);var earlier=fact(1,100,Kind.DEVICE_PURCHASE,"USDT","2.654321",AT.minusDays(1),null);
        var proven=List.of(new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.READY,List.of()));
        when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(later),proven),withFirstHistory(List.of(later,earlier),proven));
        var before=service.summarize(history("USDT"));var after=service.summarize(history("USDT"));
        assertThat(before.financialSummary().firstCandidates().confirmedValue()).isEqualTo(1);assertThat(after.financialSummary().firstCandidates().confirmedValue()).isEqualTo(1);
        assertThat(before.currentCustomers().get(0).first().observedCandidate().kind()).isEqualTo("DEPOSIT");assertThat(after.currentCustomers().get(0).first().observedCandidate().kind()).isEqualTo("DEVICE_PURCHASE");
        assertThat(after.financialSummary().firstSources().get(0).deposits().confirmedAmount()).isEqualByComparingTo("0");
        assertThat(after.financialSummary().firstSources().get(0).purchases().confirmedAmount()).isEqualByComparingTo("2.654321");
    }
    @Test void platformFirstRemainsConfirmedWithUnknownAttributionButPersonalPeriodCannotClaimIt() {
        var fact=fact(1,10,Kind.DEPOSIT,"USDT","10.123456",AT,null);
        when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(fact),List.of(new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.READY,List.of()))));
        when(mapper.eventCandidates(any())).thenReturn(List.of(new SupportAnalyticsMapper.EventCandidate(fact.factId(),1L)));
        var personal=service.summarize(period(ReadMode.PERSONAL,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(personal.currentCustomers().get(0).first().state()).isEqualTo(FirstState.CONFIRMED);
        assertThat(personal.currentCustomers().get(0).first().observedCandidate().attribution().agent()).isEqualTo(AttributionStatus.UNKNOWN);
        assertThat(personal.financialSummary().firstCandidates().confirmedValue()).isZero();
        var all=new ReadScope(8L,ReadMode.ALL,null,null);when(ownership.queryScope(ReadMode.ALL,null,null)).thenReturn(all);
        when(mapper.legacyProductionCustomers(all)).thenReturn(List.of(1L));
        var platform=service.summarize(period(ReadMode.ALL,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(platform.financialSummary().firstCandidates().confirmedValue()).isEqualTo(1);
        assertThat(platform.financialSummary().firstSources().get(0).deposits().confirmedAmount()).isEqualByComparingTo("10.123456");
        assertThat(platform.financialSummary().currencies().get(0).net().confirmedAmount()).isNull();
    }
    @Test void malformedOrForeignFirstHistoryCannotGrantReadiness() {
        for(var proofs:List.of(List.of(new SupportPaymentFacts.FirstHistory(8,SupportPaymentFacts.Status.READY,List.of())),
                List.of(new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.READY,List.of()),new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.READY,List.of())),
                List.of(new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.READY,List.of("invalid"))))) {
            when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(),proofs));
            assertThatThrownBy(()->service.summarize(history("USDT"))).hasMessage("SUPPORT_ANALYTICS_FIRST_HISTORY_INVALID");
        }
    }
    @ParameterizedTest
    @ValueSource(strings={"NONE","OTHER_CURRENCY","OUTSIDE_PERIOD"})
    void localSourceFailureCannotEraseTheKnownZeroFirstSubsetOfAnotherCompleteCustomer(String scenario) {
        when(mapper.currentCustomers(any())).thenReturn(List.of(current(1),current(2)));
        List<Fact> facts=scenario.equals("NONE")?List.of():List.of(fact(2,20,Kind.DEPOSIT,
            scenario.equals("OTHER_CURRENCY")?"NEX":"USDT","2.123456",AT.minusDays(1),null));
        var base=snapshot(facts);
        when(finance.readHistory(any())).thenReturn(new Snapshot(base.facts(),List.of(new SupportPaymentFacts.Issue(Source.CARD_TOPUP,null,"SOURCE_READ_FAILED",1L)),base.coverage(),base.businessZone(),base.evaluatedAt(),List.of(
            new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.UNKNOWN,List.of("SOURCE_READ_FAILED")),new SupportPaymentFacts.FirstHistory(2,SupportPaymentFacts.Status.READY,List.of()))));
        events(facts);
        Query query=scenario.equals("OUTSIDE_PERIOD")?period(ReadMode.PERSONAL,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)):history("USDT");
        var result=service.summarize(query);
        assertThat(result.financialSummary().firstCandidates()).isEqualTo(new Count(0L,0L,Status.PARTIAL));
        assertThat(result.financialSummary().firstSources()).singleElement().satisfies(c->{
            assertThat(c.currency()).isEqualTo("USDT");assertThat(c.deposits().confirmedAmount()).isEqualByComparingTo("0");assertThat(c.purchases().confirmedAmount()).isEqualByComparingTo("0");
            assertThat(c.deposits().confirmedEvents()).isZero();assertThat(c.purchases().confirmedEvents()).isZero();assertThat(c.deposits().status()).isEqualTo(Status.PARTIAL);
        });
        assertThat(result.currentCustomers().get(0).first().state()).isEqualTo(FirstState.UNKNOWN);
        assertThat(result.currentCustomers().get(1).first().state()).isEqualTo(scenario.equals("NONE")?FirstState.NONE:FirstState.CONFIRMED);
    }
    private static Snapshot withFirstHistory(List<Fact> facts,List<SupportPaymentFacts.FirstHistory> firstHistory) {
        // Only aggregation tests consume this explicit trusted facade result; source proofs are tested in the real finance pipeline.
        var base=snapshot(facts);return new Snapshot(base.facts(),base.issues(),base.coverage(),base.businessZone(),base.evaluatedAt(),firstHistory);
    }

    @Test void fullHistoryBeyondThirtyRowsAndALateEarlierFactDetermineTheSingleObservedCandidate() {
        var later=new ArrayList<Fact>();
        for(long id=1;id<=40;id++)later.add(fact(1,id,Kind.DEPOSIT,"USDT","1.000001",AT.plusDays(id),null));
        Collections.reverse(later);
        Fact earlier=fact(1,100,Kind.DEVICE_PURCHASE,"USDT","2.000003",AT.minusDays(1),null);
        var expanded=new ArrayList<>(later);expanded.add(earlier);
        when(finance.readHistory(List.of(1L))).thenReturn(snapshot(later),snapshot(expanded));
        Result before=service.summarize(history(null));
        assertThat(before.currentCustomers().get(0).first().observedCandidate().kind()).isEqualTo("DEPOSIT");
        Result after=service.summarize(history(null));
        assertThat(after.currentCustomers().get(0).first().observedCandidate().kind()).isEqualTo("DEVICE_PURCHASE");
        assertThat(after.currentCustomers().get(0).first().observedCandidate().succeededAt()).isEqualTo(AT.minusDays(1));
        assertThat(after.financialSummary().currencies().get(0).deposits().observedAmount()).isEqualByComparingTo("40.000040");
        assertThat(after.financialSummary().currencies().get(0).purchases().observedAmount()).isEqualByComparingTo("2.000003");
        assertUnknown(after);
        verify(finance,times(2)).readHistory(List.of(1L));
    }

    @Test void equalTimePrefersDepositAndThenStableCanonicalIdentityRegardlessOfInputOrder() {
        Fact purchase=fact(1,30,Kind.DEVICE_PURCHASE,"USDT","30",AT,null);
        Fact d2=fact(1,2,Kind.DEPOSIT,"USDT","2",AT,null);
        Fact d11=fact(1,11,Kind.DEPOSIT,"USDT","11",AT,null);
        when(finance.readHistory(any())).thenReturn(snapshot(List.of(purchase,d2,d11)),snapshot(List.of(d11,d2,purchase)));
        FirstCandidate first=service.summarize(history(null)).currentCustomers().get(0).first().observedCandidate();
        assertThat(first.kind()).isEqualTo("DEPOSIT");assertThat(first.amount()).isEqualByComparingTo("11");
        assertThat(service.summarize(history(null)).currentCustomers().get(0).first().observedCandidate()).isEqualTo(first);
    }

    @Test void multiCurrencyFirstIsChosenBeforeWindowAndCurrencyAndAmountsNeverMix() {
        Fact nex=fact(1,10,Kind.DEPOSIT,"NEX","12.123456",AT.minusDays(10),null);
        Fact usdt=fact(1,11,Kind.DEPOSIT,"USDT","20.000001",AT,null);
        when(finance.readHistory(any())).thenReturn(snapshot(List.of(usdt,nex)));
        events(List.of(nex,usdt));
        Result period=service.summarize(period(ReadMode.PERSONAL,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(period.financialSummary().firstCandidates().observedValue()).isZero();
        assertThat(period.currentCustomers().get(0).first().observedCandidate().currency()).isEqualTo("NEX");
        assertThat(period.financialSummary().currencies()).hasSize(1);
        assertThat(period.financialSummary().currencies().get(0).deposits().observedAmount()).isEqualByComparingTo("20.000001");
        Result all=service.summarize(history(null));
        assertThat(all.financialSummary().currencies()).extracting(CurrencyTotals::currency).containsExactly("NEX","USDT");
        assertUnknown(all);
    }

    @Test void refundIsSeparateAndNeverReopensFirstOrChangesGrossSuccessAmounts() {
        Fact purchase=fact(1,10,Kind.DEVICE_PURCHASE,"USDT","10.123456",AT.minusDays(2),null);
        Fact refund=fact(1,11,Kind.DEVICE_PURCHASE_REFUND,"USDT","1.000001",AT.minusDays(1),purchase.orderNo());
        Fact deposit=fact(1,12,Kind.DEPOSIT,"USDT","5.000001",AT,null);
        when(finance.readHistory(any())).thenReturn(snapshot(List.of(refund,deposit,purchase,purchase)));
        Result result=service.summarize(history(null));
        var money=result.financialSummary().currencies().get(0);
        assertThat(money.deposits().observedAmount()).isEqualByComparingTo("5.000001");
        assertThat(money.purchases().observedAmount()).isEqualByComparingTo("10.123456");
        assertThat(money.purchases().observedEvents()).isEqualTo(1);
        assertThat(money.purchaseRefunds().observedAmount()).isEqualByComparingTo("1.000001");
        assertThat(money.net().observedAmount()).isNull();assertThat(money.net().confirmedAmount()).isNull();
        assertThat(result.currentCustomers().get(0).first().observedCandidate().succeededAt()).isEqualTo(purchase.succeededAt());
        assertThat(result.financialSummary().firstCandidates().observedValue()).isEqualTo(1);
        assertUnknown(result);
    }

    @Test void queryZoneCannotReinterpretRawShanghaiTimesAndEndBoundaryIsExclusive() {
        Fact atStart=fact(1,1,Kind.DEPOSIT,"USDT","1",AT.withHour(8),null);
        Fact atEnd=fact(1,2,Kind.DEPOSIT,"USDT","2",AT.withHour(9),null);
        when(finance.readHistory(any())).thenReturn(snapshot(List.of(atStart,atEnd)));
        events(List.of(atStart,atEnd));
        Result result=service.summarize(period(ReadMode.PERSONAL,null,"UTC",AT.withHour(0),AT.withHour(1)));
        assertThat(result.financialSummary().currencies().get(0).deposits().observedAmount()).isEqualByComparingTo("1");
        assertThat(result.currentCustomers().get(0).first().observedCandidate().succeededAt()).isEqualTo(AT.withHour(0));
        assertThat(result.currentScope().total()).isEqualTo(1);
    }

    @Test void transferredCustomerContributesOnlyToAuthorizedHistoricalAggregateWithoutAnyIdentityOutput() {
        Fact visible=fact(1,1,Kind.DEPOSIT,"USDT","2",AT,null);
        Fact transferred=fact(9009009,22,Kind.DEPOSIT,"USDT","3",AT,null);
        when(finance.readHistory(List.of(1L,9009009L))).thenReturn(snapshot(List.of(visible,transferred)));
        events(List.of(visible,transferred));
        Result result=service.summarize(period(ReadMode.PERSONAL,null,"Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(result.currentCustomers()).extracting(Customer::customerId).containsExactly(1L);
        assertThat(result.restrictedSummary().customers().observedValue()).isEqualTo(1);
        assertThat(result.financialSummary().currencies().get(0).deposits().observedAmount()).isEqualByComparingTo("5");
        assertThat(result.toString()).doesNotContain("9009009","DEPOSIT:22","private-business-22","private-order");
        verify(mapper).eventCandidates(PERSONAL);
    }

    @ParameterizedTest
    @ValueSource(strings={"AMOUNT","TIME","ZONE","CURRENCY","ORDER","MODE","SCHEMA"})
    void mismatchedSavedTupleNeverBecomesManagedGroupMoneyButDoesNotEraseFinancialProfile(String violation) {
        ReadScope managed=new ReadScope(8L,ReadMode.MANAGED,100L,null);
        when(ownership.queryScope(ReadMode.MANAGED,100L,null)).thenReturn(managed);
        Fact fact=fact(1,1,Kind.DEVICE_PURCHASE,"USDT","2.123456",AT,null);
        when(finance.readHistory(any())).thenReturn(snapshot(List.of(fact)));
        when(mapper.eventCandidates(any())).thenReturn(List.of(new SupportAnalyticsMapper.EventCandidate(fact.factId(),1L)));
        when(mapper.attributions(any(),any())).thenReturn(List.of(corrupt(attribution(fact),violation)));
        Result result=service.summarize(new Query(ReadMode.MANAGED,100L,null,Basis.PERIOD_EVENT,AT.minusHours(1),AT.plusHours(1),"Asia/Shanghai","USDT"));
        assertThat(result.currentCustomers().get(0).first().observedCandidate().amount()).isEqualByComparingTo("2.123456");
        assertThat(result.currentCustomers().get(0).first().observedCandidate().attribution().group()).isEqualTo(AttributionStatus.UNKNOWN);
        assertThat(result.financialSummary().currencies().get(0).purchases().observedAmount()).isNull();
        assertThat(result.financialSummary().status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.reasons()).contains("EVENT_ATTRIBUTION_UNVERIFIED");
    }

    @Test void oldSourceAndRejectedNewProofKeepUnknownAttributionAndCannotEnterKnownGroupSummary() {
        Fact fact=fact(1,1,Kind.DEPOSIT,"USDT","2",AT,null);
        ReadScope managed=new ReadScope(8L,ReadMode.MANAGED,null,null);
        when(ownership.queryScope(ReadMode.MANAGED,null,null)).thenReturn(managed);
        events(List.of(fact));
        var bad=new SupportPaymentFacts.Issue(Source.CARD_TOPUP,null,"INVALID_PERSISTED_SOURCE_PROOF");
        Snapshot base=snapshot(List.of(fact));
        when(finance.readHistory(any())).thenReturn(new Snapshot(base.facts(),List.of(bad),base.coverage(),base.businessZone(),base.evaluatedAt()));
        Result result=service.summarize(period(ReadMode.MANAGED,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(result.currentCustomers().get(0).first().observedCandidate().attribution().group()).isEqualTo(AttributionStatus.UNKNOWN);
        assertThat(result.financialSummary().currencies().get(0).deposits().observedAmount()).isNull();
        when(finance.readHistory(any())).thenReturn(base);
        AttributionRow r=attribution(fact);
        doReturn(List.of(new AttributionRow(r.factId(),r.customerId(),r.kind(),r.source(),r.ledgerId(),r.sourceBusinessId(),
            r.orderNo(),r.orderType(),r.originalFactId(),r.currency(),r.amount(),r.succeededAt(),r.sourceBusinessZone(),r.successTimeField(),r.fractionalSecondDigits(),
            "OLD_SOURCE",r.captureSchemaVersion(),null,null,null,"UNKNOWN","UNKNOWN","UNKNOWN"))).when(mapper).attributions(any(),any());
        result=service.summarize(period(ReadMode.MANAGED,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(result.financialSummary().currencies().get(0).deposits().observedAmount()).isNull();
        assertThat(result.financialSummary().status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.financialSummary().firstCandidates().confirmedValue()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"CAPTURED_SOURCE_PROOF_MISMATCH,GLOBAL","CAPTURED_SOURCE_PROOF_MISMATCH,FACT",
        "SOURCE_READ_FAILED,GLOBAL","SOURCE_READ_FAILED,FACT","CONFLICTING_CAPTURED_SOURCE_PROJECTION,SOURCE_ROW",
        "MISSING_SETTLEMENT_LEDGER,FACT","MISSING_AUTHORITATIVE_SOURCE,FACT","MISSING_INTENT_IDENTITY,FACT",
        "MISSING_CARD_SETTLEMENT,FACT","MISSING_SOURCE_CONFIRMATION_TIME,FACT"})
    void rejectedCaptureOrSourceReadCannotSupplyKnownAttributionWhileIndependentMoneyAndCandidateSurvive(String reason,String identity) {
        Fact fact=fact(1,1,Kind.DEPOSIT,"USDT","2.123456",AT,null);
        var managed=new ReadScope(8L,ReadMode.MANAGED,null,null);
        var all=new ReadScope(8L,ReadMode.ALL,null,null);
        when(ownership.queryScope(ReadMode.MANAGED,null,null)).thenReturn(managed);
        when(ownership.queryScope(ReadMode.ALL,null,null)).thenReturn(all);
        when(mapper.legacyProductionCustomers(all)).thenReturn(List.of(1L));
        events(List.of(fact));
        String sourceId=switch(identity) {case "GLOBAL" -> null;case "FACT" -> fact.factId();default -> fact.sourceIds().get(0);};
        Snapshot base=snapshot(List.of(fact));
        when(finance.readHistory(any())).thenReturn(new Snapshot(base.facts(),
            List.of(new SupportPaymentFacts.Issue(fact.source(),sourceId,reason)),base.coverage(),base.businessZone(),base.evaluatedAt()));
        Result managedResult=service.summarize(period(ReadMode.MANAGED,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        FirstSelection first=managedResult.currentCustomers().get(0).first();
        assertThat(first.observedCandidate().amount()).isEqualByComparingTo("2.123456");
        assertThat(first.observedCandidate().attribution()).isEqualTo(new Attribution(AttributionStatus.UNKNOWN,AttributionStatus.UNKNOWN,AttributionStatus.UNKNOWN));
        assertThat(managedResult.financialSummary().currencies().get(0).deposits().observedAmount()).isNull();
        if("SOURCE_READ_FAILED".equals(reason))
            assertThat(managedResult.financialSummary().firstCandidates()).isEqualTo(new Count(null,null,Status.FAILED));
        else assertThat(managedResult.financialSummary().firstCandidates()).isEqualTo(new Count(0L,null,Status.UNKNOWN));
        assertThat(managedResult.reasons()).contains("EVENT_ATTRIBUTION_UNVERIFIED","PERIOD_EVENT_COVERAGE_UNVERIFIED");
        Result history=service.summarize(history("USDT"));
        assertThat(history.financialSummary().currencies().get(0).deposits().observedAmount()).isEqualByComparingTo("2.123456");
        Result platform=service.summarize(period(ReadMode.ALL,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(platform.financialSummary().currencies().get(0).deposits().observedAmount()).isEqualByComparingTo("2.123456");
        assertThat(platform.financialSummary().attribution()).filteredOn(p -> p.status()==AttributionStatus.KNOWN)
            .allSatisfy(p -> assertThat(p.observedEvents()).isZero());
        assertThat(platform.financialSummary().attribution()).filteredOn(p -> p.status()==AttributionStatus.UNKNOWN)
            .allSatisfy(p -> assertThat(p.observedEvents()).isEqualTo(1));
        assertUnknown(managedResult);assertUnknown(history);assertUnknown(platform);
    }

    @ParameterizedTest
    @CsvSource({"CAPTURED_SOURCE_PROOF_MISMATCH,OTHER_FACT","CAPTURED_SOURCE_PROOF_MISMATCH,OTHER_SOURCE_ROW",
        "CAPTURED_SOURCE_PROOF_MISMATCH,OTHER_SOURCE","SOURCE_READ_FAILED,OTHER_SOURCE",
        "SOURCE_READ_FAILED,OTHER_FACT","CONFLICTING_CAPTURED_SOURCE_PROJECTION,OTHER_SOURCE_ROW",
        "MISSING_SETTLEMENT_LEDGER,OTHER_FACT","MISSING_AUTHORITATIVE_SOURCE,OTHER_FACT","MISSING_INTENT_IDENTITY,OTHER_FACT",
        "MISSING_CARD_SETTLEMENT,OTHER_FACT","MISSING_SOURCE_CONFIRMATION_TIME,OTHER_FACT"})
    void unrelatedFactOrSourceIssuesDoNotPolluteValidSavedAttribution(String reason,String identity) {
        Fact fact=fact(1,1,Kind.DEPOSIT,"USDT","2.123456",AT,null);
        var managed=new ReadScope(8L,ReadMode.MANAGED,null,null);
        when(ownership.queryScope(ReadMode.MANAGED,null,null)).thenReturn(managed);
        events(List.of(fact));
        Source source=identity.equals("OTHER_SOURCE")?Source.WALLET_ORDER:fact.source();
        String sourceId=switch(identity) {case "OTHER_SOURCE" -> null;case "OTHER_SOURCE_ROW" -> "private-source-999";default -> "DEPOSIT:999";};
        Snapshot base=snapshot(List.of(fact));
        when(finance.readHistory(any())).thenReturn(new Snapshot(base.facts(),List.of(new SupportPaymentFacts.Issue(source,sourceId,reason)),
            base.coverage(),base.businessZone(),base.evaluatedAt()));
        Result result=service.summarize(period(ReadMode.MANAGED,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(result.currentCustomers().get(0).first().observedCandidate().attribution().group()).isEqualTo(AttributionStatus.KNOWN);
        assertThat(result.financialSummary().currencies().get(0).deposits().observedAmount()).isEqualByComparingTo("2.123456");
        assertThat(result.financialSummary().firstCandidates().observedValue()).isEqualTo(1);
        assertThat(result.reasons()).doesNotContain("EVENT_ATTRIBUTION_UNVERIFIED","PERIOD_EVENT_COVERAGE_UNVERIFIED");
        assertUnknown(result);
    }

    @ParameterizedTest
    @ValueSource(strings={"SETTLEMENT_MISMATCH","SETTLEMENT_TYPE_MISMATCH","UNSUCCESSFUL_SETTLEMENT",
        "CONFLICTING_CAPTURED_SOURCE_PROJECTION","BROKEN_CREGIS_EVENT_LINK","BROKEN_INTENT_IDENTITY","BROKEN_SOURCE_LINK",
        "NON_POSITIVE_AMOUNT","MISSING_SOURCE_ID","MISSING_SUCCESS_TIME","MISSING_SETTLEMENT_TIME","MISSING_ORIGINAL_ORDER"})
    void strictFinancialContradictionDeniesSavedAttributionOnlyForItsActualCustomer(String reason) {
        Fact fact=fact(1,101,Kind.DEPOSIT,"USDT","2.123456",AT,null);
        events(List.of(fact));Snapshot base=snapshot(List.of(fact));
        when(finance.readHistory(any())).thenReturn(new Snapshot(base.facts(),
            List.of(new SupportPaymentFacts.Issue(fact.source(),fact.factId(),reason,1L)),base.coverage(),base.businessZone(),base.evaluatedAt()));
        var query=period(ReadMode.PERSONAL,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1));
        var rejected=service.summarize(query);
        assertThat(rejected.currentCustomers().get(0).first().state()).isEqualTo(FirstState.UNKNOWN);
        assertThat(rejected.currentCustomers().get(0).first().observedCandidate().attribution())
            .isEqualTo(new Attribution(AttributionStatus.UNKNOWN,AttributionStatus.UNKNOWN,AttributionStatus.UNKNOWN));
        assertThat(rejected.financialSummary().currencies().get(0).deposits().observedAmount()).isNull();
        assertThat(rejected.financialSummary().firstCandidates().observedValue()).isZero();
        assertThat(rejected.reasons()).contains("EVENT_ATTRIBUTION_UNVERIFIED","PERIOD_EVENT_COVERAGE_UNVERIFIED");

        // A different customer's issue may claim the same canonical ID, but cannot revoke this customer's proof.
        when(finance.readHistory(any())).thenReturn(new Snapshot(base.facts(),
            List.of(new SupportPaymentFacts.Issue(fact.source(),fact.factId(),reason,2L)),base.coverage(),base.businessZone(),base.evaluatedAt()));
        var unaffected=service.summarize(query);
        assertThat(unaffected.currentCustomers().get(0).first().observedCandidate().attribution().agent()).isEqualTo(AttributionStatus.KNOWN);
        assertThat(unaffected.financialSummary().currencies().get(0).deposits().observedAmount()).isEqualByComparingTo("2.123456");
        assertThat(unaffected.financialSummary().firstCandidates().observedValue()).isEqualTo(1);
        assertThat(unaffected.reasons()).doesNotContain("EVENT_ATTRIBUTION_UNVERIFIED","PERIOD_EVENT_COVERAGE_UNVERIFIED");
        assertUnknown(rejected);assertUnknown(unaffected);
    }

    @Test void managedNoGroupsIsAnEmptyCurrentScopeAndNeverFallsBackToAll() {
        var managed=new ReadScope(8L,ReadMode.MANAGED,null,null);
        when(ownership.defaultQueryScope(null,null)).thenReturn(managed);
        when(mapper.currentCustomers(managed)).thenReturn(List.of());
        Result result=service.summarize(new Query(null,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT"));
        assertThat(result.currentScope().mode()).isEqualTo(ReadMode.MANAGED);
        assertThat(result.currentScope().total()).isZero();assertThat(result.currentCustomers()).isEmpty();
        assertThat(result.financialSummary().currencies().get(0).deposits().observedAmount()).isNull();
        assertThat(result.financialSummary().firstCandidates().confirmedValue()).isNull();
        verifyNoInteractions(finance);verify(mapper,never()).legacyProductionCustomers(any());
    }

    @Test void realOwnershipServiceRejectsForgedAllModeBeforeAnyStatisticsQuery() {
        var binding=mock(SupportBindingMapper.class);var groups=mock(SupportGroupMapper.class);
        var actualOwnership=new SupportOwnershipService(binding,groups);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("7","",List.of()));
        try {
            when(binding.roles(7L)).thenReturn(List.of("SUPPORT"));
            var protectedService=new SupportAnalyticsService(actualOwnership,finance,mapper);
            assertThatThrownBy(() -> protectedService.summarize(new Query(ReadMode.ALL,null,null,Basis.CURRENT_ASSET,null,null,"Asia/Shanghai",null)))
                .isInstanceOf(BizException.class).hasMessage("SUPPORT_SCOPE_FORBIDDEN");
            verifyNoInteractions(mapper,finance);
        } finally {SecurityContextHolder.clearContext();}
    }

    @Test void platformLegacyScopeIsIndependentOfCurrentBoundRowsAndUnknownOwnershipIsNotFilledFromToday() {
        var all=new ReadScope(8L,ReadMode.ALL,null,null);
        when(ownership.queryScope(ReadMode.ALL,null,null)).thenReturn(all);
        when(mapper.legacyProductionCustomers(all)).thenReturn(List.of(9009009L));
        Fact legacy=fact(9009009,1,Kind.DEPOSIT,"USDT","4",AT,null);
        when(finance.readHistory(List.of(1L,9009009L))).thenReturn(snapshot(List.of(legacy)));
        Result result=service.summarize(period(ReadMode.ALL,null,"Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(result.financialSummary().currencies().get(0).deposits().observedAmount()).isEqualByComparingTo("4");
        assertThat(result.financialSummary().attribution()).filteredOn(p -> p.layer().equals("GROUP") && p.status()==AttributionStatus.UNKNOWN)
            .singleElement().satisfies(p -> assertThat(p.observedEvents()).isEqualTo(1));
        assertThat(result.toString()).doesNotContain("9009009","private-business");
    }

    @Test void sourceFailureAndEmptyHistoryNeverBecomeConfirmedZeroOrLifetimeUnpaid() {
        var snapshot=snapshot(List.of());
        when(finance.readHistory(any())).thenReturn(new Snapshot(List.of(),List.of(new SupportPaymentFacts.Issue(Source.CARD_TOPUP,null,"SOURCE_READ_FAILED")),
            snapshot.coverage(),snapshot.businessZone(),snapshot.evaluatedAt()));
        Result result=service.summarize(history("USDT"));
        assertThat(result.financialSummary().currencies().get(0).deposits().status()).isEqualTo(Status.FAILED);
        assertThat(result.financialSummary().currencies().get(0).deposits().observedAmount()).isNull();
        assertThat(result.currentCustomers().get(0).first().observedCandidate()).isNull();
        assertUnknown(result);
        when(finance.readHistory(any())).thenReturn(snapshot);
        result=service.summarize(history("USDT"));
        assertThat(result.financialSummary().currencies().get(0).deposits().observedAmount()).isZero();
        assertThat(result.financialSummary().currencies().get(0).deposits().confirmedAmount()).isNull();
        assertThat(result.currentCustomers().get(0).first().reasons()).containsExactly("HISTORY_UNVERIFIED");
    }

    @Test void currentAssetIsOnlyTheSameUniqueClassificationListAndNeverFinancialCumulativeOrBalance() {
        when(mapper.currentCustomers(any())).thenReturn(List.of(current(1),current(1),new SupportAnalyticsMapper.CurrentCustomer(2L,"PENDING","GROUP_QUEUE",0),
            new SupportAnalyticsMapper.CurrentCustomer(3L,"ANOMALY","UNKNOWN",0),new SupportAnalyticsMapper.CurrentCustomer(4L,"BOUND","UNGROUPED",1)));
        Result result=service.summarize(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_ASSET,null,null,"Asia/Shanghai",null));
        assertThat(result.currentScope().total()).isEqualTo(4);
        assertThat(result.currentScope().bound()).isEqualTo(2);
        assertThat(result.currentScope().pending()).isEqualTo(1);
        assertThat(result.currentScope().anomaly()).isEqualTo(1);
        assertThat(result.currentCustomers()).hasSize(4);
        assertThat(result.currentCustomers().get(3).handoverRequired()).isTrue();
        assertThat(result.financialSummary().status()).isEqualTo(Status.UNAVAILABLE);
        verifyNoInteractions(finance);verify(mapper,never()).eventCandidates(any());
    }

    @Test void foreignFinanceScopeAndConflictingCanonicalIdentityFailClosed() {
        when(finance.readHistory(any())).thenReturn(snapshot(List.of(fact(2,1,Kind.DEPOSIT,"USDT","1",AT,null))));
        assertThatThrownBy(() -> service.summarize(history(null))).hasMessage("SUPPORT_ANALYTICS_FINANCIAL_SCOPE_INVALID");
        when(finance.readHistory(any())).thenReturn(snapshot(List.of(fact(1,1,Kind.DEPOSIT,"USDT","1",AT,null),fact(1,1,Kind.DEPOSIT,"USDT","2",AT,null))));
        assertThatThrownBy(() -> service.summarize(history(null))).hasMessage("SUPPORT_ANALYTICS_FINANCIAL_CONFLICT");
    }

    @Test void foreignAttributionAndConflictingEventCustomerAreHardScopeErrorsNotUnknownRows() {
        Fact fact=fact(1,1,Kind.DEPOSIT,"USDT","1",AT,null);
        when(finance.readHistory(any())).thenReturn(snapshot(List.of(fact)));
        AttributionRow r=attribution(fact);
        when(mapper.attributions(any(),any())).thenReturn(List.of(new AttributionRow(r.factId(),9009009L,r.kind(),r.source(),r.ledgerId(),r.sourceBusinessId(),
            r.orderNo(),r.orderType(),r.originalFactId(),r.currency(),r.amount(),r.succeededAt(),r.sourceBusinessZone(),r.successTimeField(),r.fractionalSecondDigits(),
            r.captureMode(),r.captureSchemaVersion(),r.agentAdminId(),r.groupId(),r.ownerAdminId(),r.agentStatus(),r.groupStatus(),r.ownerStatus())));
        assertThatThrownBy(() -> service.summarize(history(null))).hasMessage("SUPPORT_ANALYTICS_ATTRIBUTION_SCOPE_INVALID");
        when(mapper.eventCandidates(any())).thenReturn(List.of(new SupportAnalyticsMapper.EventCandidate(fact.factId(),1L),
            new SupportAnalyticsMapper.EventCandidate(fact.factId(),9009009L)));
        assertThatThrownBy(() -> service.summarize(period(ReadMode.PERSONAL,null,"Asia/Shanghai",AT.minusHours(1),AT.plusHours(1))))
            .hasMessage("SUPPORT_ANALYTICS_EVENT_CONFLICT");
    }

    @Test void failedCurrentProjectionReturnsUnknownCountsAndNoIdentityWhileUnexpectedFinanceExceptionsPropagate() {
        when(mapper.currentCustomers(any())).thenThrow(new DataAccessResourceFailureException("private table"));
        Result result=service.summarize(history("USDT"));
        assertThat(result.currentScope().total()).isNull();assertThat(result.currentScope().status()).isEqualTo(Status.FAILED);
        assertThat(result.currentCustomers()).isEmpty();assertThat(result.toString()).doesNotContain("private table");
        doReturn(List.of(current(1))).when(mapper).currentCustomers(any());
        when(finance.readHistory(any())).thenThrow(new DataAccessResourceFailureException("finance unavailable"));
        assertThatThrownBy(() -> service.summarize(history("USDT"))).isInstanceOf(DataAccessResourceFailureException.class);
    }

    private void events(List<Fact> facts) {
        when(mapper.eventCandidates(any())).thenReturn(facts.stream().map(f -> new SupportAnalyticsMapper.EventCandidate(f.factId(),f.customerId())).toList());
        when(mapper.attributions(any(),any())).thenAnswer(i->{
            Collection<String> requested=i.getArgument(1);
            return facts.stream().filter(f->requested.contains(f.factId())).map(SupportAnalyticsServiceTest::attribution).toList();
        });
    }
    private static SupportAnalyticsMapper.CurrentCustomer current(long id) {return new SupportAnalyticsMapper.CurrentCustomer(id,"BOUND","GROUPED",0);}
    private static Query history(String currency) {return new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai",currency);}
    private static Query period(ReadMode mode,String currency,String zone,LocalDateTime from,LocalDateTime to) {return new Query(mode,null,null,Basis.PERIOD_EVENT,from,to,zone,currency);}
    private static Snapshot snapshot(List<Fact> facts) {
        var unknown=SupportPaymentFacts.Status.UNKNOWN;
        return new Snapshot(facts,List.of(),List.of(new SupportPaymentFacts.Coverage(Source.CARD_TOPUP,SupportPaymentFacts.Status.READY,
            unknown,unknown,unknown,null,List.of("HISTORY_UNVERIFIED"),0,"support-payment-facts-v1")),"Asia/Shanghai",Instant.parse("2026-10-09T05:00:00Z"));
    }
    private static Fact fact(long customer,long ledger,Kind kind,String currency,String amount,LocalDateTime at,String originalOrder) {
        Source source=kind==Kind.DEPOSIT?Source.CARD_TOPUP:kind==Kind.DEVICE_PURCHASE?Source.WALLET_ORDER:Source.ORDER_REFUND;
        String order=kind==Kind.DEPOSIT?null:kind==Kind.DEVICE_PURCHASE?"private-order-"+ledger:originalOrder;
        String id=kind==Kind.DEPOSIT?"DEPOSIT:"+ledger:kind==Kind.DEVICE_PURCHASE?"PURCHASE:"+order:"ORDER_REFUND:"+ledger;
        return new Fact(id,kind,source,List.of("private-source-"+ledger),customer,ledger,"private-business-"+ledger,order,order==null?null:"NEW",
            kind==Kind.DEVICE_PURCHASE_REFUND?"PURCHASE:"+order:null,currency,new BigDecimal(amount),at,
            kind==Kind.DEVICE_PURCHASE?"nx_order.paid_at":"nx_wallet_ledger.created_at",kind==Kind.DEVICE_PURCHASE?6:0,
            null,at,null,"private-version",SupportPaymentFacts.Status.UNKNOWN);
    }
    private static AttributionRow attribution(Fact f) {
        return new AttributionRow(f.factId(),f.customerId(),f.kind().name(),f.source().name(),f.ledgerId(),f.sourceBusinessId(),f.orderNo(),f.orderType(),
            f.originalFactId(),f.currency(),f.amount(),f.succeededAt(),"Asia/Shanghai",f.successTimeField(),f.fractionalSecondDigits(),
            "NEW_SUCCESS","support-payment-attribution-v1",7L,100L,8L,"KNOWN","KNOWN","KNOWN");
    }
    private static AttributionRow corrupt(AttributionRow r,String violation) {
        return new AttributionRow(r.factId(),r.customerId(),r.kind(),r.source(),r.ledgerId(),r.sourceBusinessId(),
            violation.equals("ORDER")?"wrong":r.orderNo(),r.orderType(),r.originalFactId(),violation.equals("CURRENCY")?"NEX":r.currency(),
            violation.equals("AMOUNT")?r.amount().add(BigDecimal.ONE):r.amount(),violation.equals("TIME")?r.succeededAt().plusNanos(1000):r.succeededAt(),
            violation.equals("ZONE")?"UTC":r.sourceBusinessZone(),r.successTimeField(),r.fractionalSecondDigits(),violation.equals("MODE")?"OLD_SOURCE":r.captureMode(),
            violation.equals("SCHEMA")?"future":r.captureSchemaVersion(),r.agentAdminId(),r.groupId(),r.ownerAdminId(),r.agentStatus(),r.groupStatus(),r.ownerStatus());
    }
    private static void assertUnknown(Result result) {
        assertThat(result.currentCustomers()).allSatisfy(c -> assertThat(c.first().status()).isEqualTo(Status.UNKNOWN));
        assertThat(result.financialSummary().firstCandidates().confirmedValue()).isNull();
        assertThat(result.financialSummary().currencies()).allSatisfy(c -> {
            assertThat(c.deposits().confirmedAmount()).isNull();assertThat(c.purchases().confirmedAmount()).isNull();assertThat(c.purchaseRefunds().confirmedAmount()).isNull();
            assertThat(c.net().confirmedAmount()).isNull();
        });
        assertThat(result.coverage()).allSatisfy(c -> {assertThat(c.historyStatus()).isEqualTo("UNKNOWN");assertThat(c.refundStatus()).isEqualTo("UNKNOWN");
            assertThat(c.historicalEnvironmentStatus()).isEqualTo("UNKNOWN");assertThat(c.supportedFrom()).isNull();});
    }

    private final ffdd.opsconsole.team.facade.SupportInvitationReadFacade invitations=mock(ffdd.opsconsole.team.facade.SupportInvitationReadFacade.class);
    private final ffdd.opsconsole.device.facade.SupportDeviceReadFacade devices=mock(ffdd.opsconsole.device.facade.SupportDeviceReadFacade.class);
    private SupportAnalyticsService enriched() {
        when(mapper.currentCustomers(any())).thenReturn(List.of(metricsCurrent(1,7L,100L,"BOUND","GROUPED")));
        when(mapper.activityCoverage(any())).thenReturn(new SupportAnalyticsMapper.ActivityCoverage(AT.minusDays(7),AT,AT));
        when(mapper.activityRules(any())).thenReturn(new SupportAnalyticsMapper.ActivityRules(1L,3));
        when(mapper.activityEvents(any(),any(),any())).thenReturn(List.of());
        when(mapper.scopedGroupRows(any())).thenReturn(List.of());
        when(mapper.serviceAccountRows(any())).thenReturn(List.of());when(mapper.supervisorAccountRows(any())).thenReturn(List.of());
        doAnswer(i->{
            Collection<Long> roots=i.getArgument(0);
            return roots.stream().map(id->new ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Invitation(id,0,List.of(),List.of(),
                ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Completeness.COMPLETE,Set.of())).toList();
        }).when(invitations).readInvitations(any());
        when(devices.readCurrent(any())).thenReturn(new ffdd.opsconsole.device.facade.SupportDeviceReadFacade.Snapshot(List.of(),List.of(),AT));
        return new SupportAnalyticsService(ownership,finance,mapper,invitations,devices);
    }
    @Test void lifetimeAndOverlappingInvitationFactsStaySeparateFromOwnTotalsFirstAndRestrictedIdentities() {
        var complete=enriched();long hidden=9009009L;
        when(mapper.currentCustomers(any())).thenReturn(List.of(metricsCurrent(1,7L,100L,"BOUND","GROUPED"),metricsCurrent(2,7L,100L,"BOUND","GROUPED")));
        var own=new ArrayList<Fact>();for(int i=1;i<=40;i++)own.add(fact(1,i,Kind.DEPOSIT,"USDT","1.000001",AT.minusDays(2),null));
        own.add(fact(1,80,Kind.DEVICE_PURCHASE,"USDT","80.123456",AT,null));own.add(fact(2,81,Kind.DEPOSIT,"NEX","3.654321",AT,null));
        when(finance.readHistory(List.of(1L,2L))).thenReturn(withFirstHistory(own,List.of(new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.READY,List.of()))));
        when(finance.readHistory(List.of(hidden))).thenReturn(snapshot(List.of(fact(hidden,999,Kind.DEPOSIT,"USDT","500.000001",AT.minusDays(9),null))));
        doReturn(List.of(
            new ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Invitation(1,0,List.of(2L),List.of(2L,hidden),ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Completeness.COMPLETE,Set.of()),
            new ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Invitation(2,0,List.of(hidden),List.of(hidden),ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Completeness.COMPLETE,Set.of()))).when(invitations).readInvitations(any());
        var result=complete.summarize(history(null));
        assertThat(result.currentScope().total()).isEqualTo(2);assertThat(result.currentCustomers()).extracting(Customer::customerId).containsExactly(1L,2L);
        var metric=result.currentCustomers().get(0).metrics();assertThat(metric.lifetime()).filteredOn(c->c.currency().equals("USDT")).singleElement().satisfies(c->{
            assertThat(c.deposits().observedAmount()).isEqualByComparingTo("40.000040");assertThat(c.purchases().observedAmount()).isEqualByComparingTo("80.123456");assertThat(c.deposits().confirmedAmount()).isNull();});
        assertThat(metric.invitations().directCustomers()).isEqualTo(new Count(1L,1L,Status.AVAILABLE));assertThat(metric.invitations().descendantCustomers().confirmedValue()).isEqualTo(2);
        assertThat(metric.invitations().descendantDeposits()).filteredOn(c->c.currency().equals("USDT")).singleElement().satisfies(c->assertThat(c.deposits().observedAmount()).isEqualByComparingTo("500.000001"));
        assertThat(result.currentMetrics().ownLifetime()).filteredOn(c->c.currency().equals("USDT")).singleElement().satisfies(c->assertThat(c.deposits().observedAmount()).isEqualByComparingTo("40.000040"));
        assertThat(result.currentCustomers().get(0).first().observedCandidate().succeededAt()).isEqualTo(AT.minusDays(2));
        assertThat(result.restrictedSummary().customers().observedValue()).isZero();assertThat(result.toString()).doesNotContain("9009009","private-source-999","private-order-999","private-business-999");
        verify(finance).readHistory(List.of(1L,2L));verify(finance).readHistory(List.of(hidden));verify(invitations).readInvitations(List.of(1L,2L));verify(devices).readCurrent(List.of(1L,2L));
        verify(finance,times(2)).readHistory(any());verify(mapper,never()).scopedGroupRows(any());verify(mapper,never()).serviceAccountRows(any());verify(mapper,never()).supervisorAccountRows(any());
    }
    @Test void assetReadsPurchaseProofWithoutExposingHistoryAndNeverCallsRawGiftOrTrialFree() {
        var complete=enriched();var purchase=fact(1,80,Kind.DEVICE_PURCHASE,"USDT","80",AT.minusDays(1),null);
        purchase=new Fact(purchase.factId(),purchase.kind(),Source.TRIAL_CONVERT,purchase.sourceIds(),purchase.customerId(),purchase.ledgerId(),purchase.sourceBusinessId(),purchase.orderNo(),"TRIAL_CONVERT",null,purchase.currency(),purchase.amount(),purchase.succeededAt(),purchase.successTimeField(),purchase.fractionalSecondDigits(),null,purchase.ledgerRecordedAt(),null,purchase.sourceVersion(),SupportPaymentFacts.Status.UNKNOWN);
        assertThat(purchase.historicalEnvironmentStatus()).isEqualTo(SupportPaymentFacts.Status.UNKNOWN);
        when(finance.readHistory(any())).thenReturn(snapshot(List.of(purchase)));
        var online=device(101,1,purchase.orderNo(),"TRIAL","PRODUCTION",ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus.ONLINE);
        when(devices.readCurrent(any())).thenReturn(new ffdd.opsconsole.device.facade.SupportDeviceReadFacade.Snapshot(List.of(online,online,
            device(102,1,null,"GIFT","PRODUCTION",ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus.OFFLINE),
            device(103,1,null,"TRIAL","PRODUCTION",ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus.UNKNOWN),
            device(104,1,null,"OTHER","PRODUCTION",ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus.NOT_APPLICABLE),
            device(105,1,null,"GIFT",null,ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus.UNKNOWN),
            device(106,1,null,"GIFT","SANDBOX",ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus.ONLINE)),List.of(),AT));
        var result=complete.summarize(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_ASSET,null,null,"Asia/Shanghai","USDT"));
        var stock=result.currentMetrics().devices();assertThat(stock.held().observedValue()).isEqualTo(4);assertThat(stock.held().confirmedValue()).isNull();assertThat(stock.unknownHolding().confirmedValue()).isEqualTo(1);
        assertThat(stock.partitions()).filteredOn(p->p.dimension().equals("CONNECTION")).extracting(p->p.devices().confirmedValue()).containsExactly(1L,1L,1L,1L);
        assertThat(stock.partitions()).filteredOn(p->p.dimension().equals("ACQUISITION") && p.value().equals("PAID_PURCHASE")).singleElement().satisfies(p->assertThat(p.devices().confirmedValue()).isEqualTo(1));
        assertThat(stock.partitions()).filteredOn(p->p.dimension().equals("ACQUISITION") && p.value().equals("UNKNOWN")).singleElement().satisfies(p->assertThat(p.devices().confirmedValue()).isEqualTo(3));
        assertThat(result.financialSummary().status()).isEqualTo(Status.UNAVAILABLE);assertThat(result.currentCustomers().get(0).metrics().lifetimeStatus()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.currentCustomers().get(0).metrics().lifetime().get(0).purchases().observedAmount()).isNull();assertThat(result.currentMetrics().firstConfirmed().status()).isEqualTo(Status.UNAVAILABLE);
        verify(finance).readHistory(List.of(1L));verify(mapper,never()).eventCandidates(any());verify(mapper,never()).attributions(any(),any());
    }
    @Test void currentPaidPurchaseDoesNotClaimHistoricalEnvironmentOrFirstHistoryCoverage() {
        var complete=enriched();var purchase=fact(1,80,Kind.DEVICE_PURCHASE,"USDT","80",AT.minusDays(1),null);
        assertThat(purchase.historicalEnvironmentStatus()).isEqualTo(SupportPaymentFacts.Status.UNKNOWN);
        when(finance.readHistory(any())).thenReturn(snapshot(List.of(purchase)));
        when(devices.readCurrent(any())).thenReturn(new ffdd.opsconsole.device.facade.SupportDeviceReadFacade.Snapshot(
            List.of(device(101,1,purchase.orderNo(),"ORDER","PRODUCTION",ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus.ONLINE)),List.of(),AT));
        var result=complete.summarize(history("USDT"));
        assertThat(acquisition(result.currentMetrics().devices(),Acquisition.PAID_PURCHASE)).isEqualTo(1);
        assertThat(acquisition(result.currentCustomers().get(0).metrics().devices(),Acquisition.PAID_PURCHASE)).isEqualTo(1);
        assertThat(result.currentCustomers().get(0).first().state()).isEqualTo(FirstState.UNKNOWN);
        assertThat(result.currentMetrics().firstConfirmed().confirmedValue()).isZero();
        assertThat(result.currentMetrics().firstUnknown().confirmedValue()).isEqualTo(1);
        assertThat(result.currentCustomers().get(0).metrics().lifetime().get(0).purchases().confirmedAmount()).isNull();
        assertThat(result.coverage()).extracting(SourceCoverage::historicalEnvironmentStatus).containsOnly("UNKNOWN");
        when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(purchase),List.of(new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.READY,List.of()))));
        var withHistory=complete.summarize(history("USDT"));
        assertThat(withHistory.currentCustomers().get(0).first().state()).isEqualTo(FirstState.CONFIRMED);
        assertThat(acquisition(withHistory.currentMetrics().devices(),Acquisition.PAID_PURCHASE)).isEqualTo(1);
        assertThat(withHistory.coverage()).extracting(SourceCoverage::historicalEnvironmentStatus).containsOnly("UNKNOWN");
        verify(finance,times(2)).readHistory(List.of(1L));verify(devices,times(2)).readCurrent(List.of(1L));
    }
    @ParameterizedTest
    @ValueSource(strings={"OTHER_CUSTOMER","OTHER_ORDER","EMPTY_ORDER","GIFT_NO_PURCHASE","TRIAL_NO_PURCHASE"})
    void acquisitionRequiresTheActualCustomersNonemptyPaidOrderRatherThanRawChannel(String scenario) {
        var complete=enriched();var purchase=fact(scenario.equals("OTHER_CUSTOMER")?2:1,80,Kind.DEVICE_PURCHASE,"USDT","80",AT,null);
        when(mapper.currentCustomers(any())).thenReturn(List.of(metricsCurrent(1,7L,100L,"BOUND","GROUPED"),metricsCurrent(2,7L,100L,"BOUND","GROUPED")));
        boolean noPurchase=scenario.endsWith("NO_PURCHASE");when(finance.readHistory(any())).thenReturn(snapshot(noPurchase?List.of():List.of(purchase)));
        String order=scenario.equals("OTHER_ORDER")?"another-order":scenario.equals("EMPTY_ORDER")?" ":noPurchase?null:purchase.orderNo();
        String channel=scenario.equals("GIFT_NO_PURCHASE")?"GIFT":scenario.equals("TRIAL_NO_PURCHASE")?"TRIAL":"ORDER";
        when(devices.readCurrent(any())).thenReturn(new ffdd.opsconsole.device.facade.SupportDeviceReadFacade.Snapshot(
            List.of(device(101,1,order,channel,"PRODUCTION",ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus.ONLINE)),List.of(),AT));
        var result=complete.summarize(history("USDT"));
        assertThat(acquisition(result.currentMetrics().devices(),Acquisition.PAID_PURCHASE)).isZero();
        assertThat(acquisition(result.currentMetrics().devices(),Acquisition.UNKNOWN)).isEqualTo(1);
        assertThat(acquisition(result.currentCustomers().get(0).metrics().devices(),Acquisition.PAID_PURCHASE)).isZero();
        assertThat(result.currentMetrics().devices().partitions()).filteredOn(p->p.dimension().equals("ACQUISITION")).extracting(DevicePartition::value).containsExactly("PAID_PURCHASE","UNKNOWN");
    }
    @ParameterizedTest
    @ValueSource(strings={"0","-1"})
    void nonpositivePurchaseFactFailsCanonicalValidationBeforeCurrentPaidClassification(String amount) {
        var complete=enriched();var purchase=fact(1,80,Kind.DEVICE_PURCHASE,"USDT",amount,AT,null);
        when(finance.readHistory(any())).thenReturn(snapshot(List.of(purchase)));
        assertThatThrownBy(()->complete.summarize(history("USDT"))).hasMessage("SUPPORT_ANALYTICS_FINANCIAL_INVALID");
        verifyNoInteractions(devices);
    }
    @ParameterizedTest
    @ValueSource(strings={"CAPTURED_SOURCE_PROOF_MISMATCH","INVALID_PERSISTED_SOURCE_PROOF","SETTLEMENT_MISMATCH",
        "CONFLICTING_CAPTURED_SOURCE_PROJECTION","NON_POSITIVE_AMOUNT","CONFLICTING_FACT_PROJECTION","CANONICAL_CUSTOMER_MISMATCH","SOURCE_READ_FAILED"})
    void strictProofRejectionDeniesCurrentPaidIdentityEvenWhenTheOrderAndDeviceMatch(String reason) {
        var complete=enriched();var purchase=fact(1,80,Kind.DEVICE_PURCHASE,"USDT","80",AT,null);var base=snapshot(List.of(purchase));
        when(finance.readHistory(any())).thenReturn(new Snapshot(base.facts(),List.of(new SupportPaymentFacts.Issue(purchase.source(),purchase.factId(),reason,1L)),base.coverage(),base.businessZone(),base.evaluatedAt()));
        when(devices.readCurrent(any())).thenReturn(new ffdd.opsconsole.device.facade.SupportDeviceReadFacade.Snapshot(
            List.of(device(101,1,purchase.orderNo(),"ORDER","PRODUCTION",ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus.ONLINE)),List.of(),AT));
        var result=complete.summarize(history("USDT"));
        assertThat(acquisition(result.currentMetrics().devices(),Acquisition.PAID_PURCHASE)).isZero();
        assertThat(acquisition(result.currentMetrics().devices(),Acquisition.UNKNOWN)).isEqualTo(1);
        assertThat(result.currentCustomers().get(0).first().state()).isEqualTo(FirstState.UNKNOWN);
    }
    @Test void conflictingCanonicalPurchaseRowsFailBeforeCurrentPaidClassification() {
        var complete=enriched();var purchase=fact(1,80,Kind.DEVICE_PURCHASE,"USDT","80",AT,null);
        var contradiction=fact(1,80,Kind.DEVICE_PURCHASE,"USDT","81",AT,null);
        when(finance.readHistory(any())).thenReturn(snapshot(List.of(purchase,contradiction)));
        assertThatThrownBy(()->complete.summarize(history("USDT"))).hasMessage("SUPPORT_ANALYTICS_FINANCIAL_CONFLICT");
        verifyNoInteractions(devices);
    }
    @ParameterizedTest
    @CsvSource({"PRODUCTION,private-test-run","SANDBOX,''","UNKNOWN,''"})
    void paymentCannotCertifyANonproductionOrUnknownDevice(String environment,String runId) {
        var complete=enriched();var purchase=fact(1,80,Kind.DEVICE_PURCHASE,"USDT","80",AT,null);when(finance.readHistory(any())).thenReturn(snapshot(List.of(purchase)));
        var original=device(101,1,purchase.orderNo(),"ORDER",environment,ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus.ONLINE);
        var scoped=new ffdd.opsconsole.device.facade.SupportDeviceReadFacade.DeviceEvidence(original.deviceId(),original.customerId(),original.sourceOrderNo(),original.sourceChannel(),original.deviceType(),original.hashrate(),original.ownershipStatus(),original.lifecycleStatus(),original.activatedAt(),original.deactivatedAt(),original.pendingDeactivate(),original.sourceEnvironment(),runId,original.runtime(),original.connectionStatus());
        when(devices.readCurrent(any())).thenReturn(new ffdd.opsconsole.device.facade.SupportDeviceReadFacade.Snapshot(List.of(scoped),List.of(),AT));
        assertThat(acquisition(complete.summarize(history("USDT")).currentMetrics().devices(),Acquisition.PAID_PURCHASE)).isZero();
    }
    private static Long acquisition(DeviceSummary devices,Acquisition kind) {
        return devices.partitions().stream().filter(p->p.dimension().equals("ACQUISITION") && p.value().equals(kind.name())).findFirst().orElseThrow().devices().confirmedValue();
    }
    @Test void queueActivityUsesStoredWatermarkAndRosterIncludesDisabledGeneralAndDeduplicatesSupervisorMemberUnion() {
        var complete=enriched();var managed=new ReadScope(8L,ReadMode.MANAGED,null,null);when(ownership.queryScope(ReadMode.MANAGED,null,null)).thenReturn(managed);
        when(mapper.currentCustomers(managed)).thenReturn(List.of(metricsCurrent(1,7L,100L,"BOUND","GROUPED"),metricsCurrent(2,null,100L,"PENDING","GROUP_QUEUE"),metricsCurrent(3,8L,200L,"BOUND","GROUPED")));
        when(finance.readHistory(any())).thenReturn(snapshot(List.of()));
        when(mapper.scopedGroupRows(managed)).thenReturn(List.of(new SupportAnalyticsMapper.GroupRow(100L,8L,"ENABLED",1),new SupportAnalyticsMapper.GroupRow(200L,8L,"DISABLED",1)));
        var disabled=roster(7,0,"GENERAL",0,"SERVICE","DISABLED",70L,100L);var member=roster(8,1,"MANAGER",1,"SERVICE","ENABLED",80L,200L);
        when(mapper.serviceAccountRows(managed)).thenReturn(List.of(disabled,disabled,member));when(mapper.supervisorAccountRows(managed)).thenReturn(List.of(roster(8,1,"MANAGER",1,"SUPERVISOR","ENABLED",80L,200L)));
        when(mapper.activityEvents(eq(managed),any(),eq(AT))).thenReturn(List.of(new SupportAnalyticsMapper.ActivityRow(1L,AT.minusDays(3))));
        var result=complete.summarize(new Query(ReadMode.MANAGED,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"UTC","USDT"));
        assertThat(result.currentMetrics().activity().active().confirmedValue()).isEqualTo(1);assertThat(result.currentMetrics().activity().inactive().confirmedValue()).isEqualTo(2);
        assertThat(result.currentCustomers().get(1).metrics().activity().state()).isEqualTo(WindowState.INACTIVE);
        assertThat(result.currentMetrics().activity().partitions()).filteredOn(p->p.category()==Category.PENDING).singleElement().satisfies(p->{assertThat(p.inactive().confirmedValue()).isEqualTo(1);assertThat(p.active().confirmedValue()).isZero();});
        var people=result.personnel();assertThat(people.serviceAccounts().confirmedValue()).isEqualTo(2);assertThat(people.groupMembers().confirmedValue()).isEqualTo(2);
        assertThat(people.supervisors().confirmedValue()).isEqualTo(1);assertThat(people.people().confirmedValue()).isEqualTo(2);assertThat(people.groups().confirmedValue()).isEqualTo(2);
        assertThat(people.partitions()).filteredOn(p->p.dimension().equals("GROUP_MEMBER_ACCOUNT") && p.value().equals("DISABLED")).singleElement().satisfies(p->assertThat(p.accounts().confirmedValue()).isEqualTo(1));
        assertThat(people.accounts()).filteredOn(a->a.accountId()==7).singleElement().satisfies(a->{assertThat(a.serviceAccount()).isTrue();assertThat(a.accountState()).isEqualTo(AccountState.DISABLED);assertThat(a.receptionState()).isEqualTo(AccountState.DISABLED);assertThat(a.handoverRequired()).isTrue();});
        assertThat(result.groups()).filteredOn(g->g.groupId()==100).singleElement().satisfies(g->{assertThat(g.customers().bound()).isEqualTo(1);assertThat(g.customers().pending()).isEqualTo(1);assertThat(g.current().activity().inactive().confirmedValue()).isEqualTo(1);});
        verify(mapper).activityEvents(managed,List.of(1L,2L,3L),AT);verify(mapper,never()).legacyProductionCustomers(any());
        when(mapper.activityCoverage(managed)).thenReturn(new SupportAnalyticsMapper.ActivityCoverage(AT.minusDays(1),AT,AT));
        var partial=complete.summarize(new Query(ReadMode.MANAGED,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"UTC","USDT"));
        assertThat(partial.currentMetrics().activity().active().confirmedValue()).isNull();assertThat(partial.currentMetrics().activity().unknown().confirmedValue()).isEqualTo(2);
        assertThat(partial.currentCustomers().get(0).metrics().activity().state()).isEqualTo(WindowState.ACTIVE);
    }
    @Test void currentGroupOwnHistoryAndSavedPeriodGroupNeverReassignTransferredMoneyOrIdentity() {
        var complete=enriched();long hidden=9009009L;var managed=new ReadScope(8L,ReadMode.MANAGED,null,null);when(ownership.queryScope(ReadMode.MANAGED,null,null)).thenReturn(managed);
        when(mapper.currentCustomers(managed)).thenReturn(List.of(metricsCurrent(1,7L,200L,"BOUND","GROUPED")));
        when(mapper.scopedGroupRows(managed)).thenReturn(List.of(new SupportAnalyticsMapper.GroupRow(100L,8L,"ENABLED",1),new SupportAnalyticsMapper.GroupRow(200L,8L,"ENABLED",1)));
        var currentPayment=fact(1,10,Kind.DEPOSIT,"USDT","100",AT.minusDays(9),null);var movedPayment=fact(hidden,11,Kind.DEPOSIT,"USDT","400",AT,null);
        when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(currentPayment,movedPayment),List.of(new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.READY,List.of()),new SupportPaymentFacts.FirstHistory(hidden,SupportPaymentFacts.Status.READY,List.of()))));
        events(List.of(currentPayment,movedPayment));
        var result=complete.summarize(period(ReadMode.MANAGED,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(result.currentCustomers()).extracting(Customer::customerId).containsExactly(1L);assertThat(result.toString()).doesNotContain("9009009","private-business-11");
        assertThat(result.groups()).filteredOn(g->g.groupId()==100).singleElement().satisfies(g->{assertThat(g.customers().total()).isZero();assertThat(g.period().currencies().get(0).deposits().observedAmount()).isEqualByComparingTo("400");assertThat(g.current().ownLifetime().get(0).deposits().observedAmount()).isNull();});
        assertThat(result.groups()).filteredOn(g->g.groupId()==200).singleElement().satisfies(g->{assertThat(g.customers().total()).isEqualTo(1);assertThat(g.current().ownLifetime().get(0).deposits().observedAmount()).isEqualByComparingTo("100");});
        assertThat(result.restrictedSummary().customers().observedValue()).isEqualTo(1);
    }
    @Test void groupFirstCoverageCannotBorrowAnotherGroupsCompleteHistory() {
        var complete=enriched();var managed=new ReadScope(8L,ReadMode.MANAGED,null,null);when(ownership.queryScope(ReadMode.MANAGED,null,null)).thenReturn(managed);
        when(mapper.currentCustomers(managed)).thenReturn(List.of(metricsCurrent(2,7L,200L,"BOUND","GROUPED")));
        when(mapper.scopedGroupRows(managed)).thenReturn(List.of(new SupportAnalyticsMapper.GroupRow(100L,8L,"ENABLED",1),new SupportAnalyticsMapper.GroupRow(200L,8L,"ENABLED",1)));
        var unknown=fact(9009009,10,Kind.DEPOSIT,"USDT","10",AT,null);var ready=fact(2,20,Kind.DEPOSIT,"USDT","20",AT,null);
        when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(unknown,ready),List.of(
            new SupportPaymentFacts.FirstHistory(9009009,SupportPaymentFacts.Status.UNKNOWN,List.of("HISTORY_UNVERIFIED")),
            new SupportPaymentFacts.FirstHistory(2,SupportPaymentFacts.Status.READY,List.of()))));
        events(List.of(unknown,ready));doReturn(List.of(attributedToGroup(unknown,100),attributedToGroup(ready,200))).when(mapper).attributions(any(),any());
        var result=complete.summarize(period(ReadMode.MANAGED,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(result.groups()).filteredOn(g->g.groupId()==100).singleElement().satisfies(g->{
            assertThat(g.period().firstCandidates()).isEqualTo(new Count(1L,null,Status.UNKNOWN));
            assertThat(g.period().firstSources().get(0).deposits().confirmedAmount()).isNull();
            assertThat(g.period().currencies().get(0).deposits().confirmedAmount()).isNull();
        });
        assertThat(result.groups()).filteredOn(g->g.groupId()==200).singleElement().satisfies(g->{
            assertThat(g.period().firstCandidates().confirmedValue()).isEqualTo(1);
            assertThat(g.period().firstSources().get(0).deposits().confirmedAmount()).isEqualByComparingTo("20");
        });
        assertThat(result.toString()).doesNotContain("9009009","private-source-10","private-business-10");
    }
    @ParameterizedTest
    @CsvSource({"NEX,0","USDT,-2"})
    void groupFirstKnownZeroUsesItsOwnHistoryBeforePeriodAndCurrencyFilters(String firstCurrency,int daysOffset) {
        var complete=enriched();var managed=new ReadScope(8L,ReadMode.MANAGED,null,null);when(ownership.queryScope(ReadMode.MANAGED,null,null)).thenReturn(managed);
        when(mapper.currentCustomers(managed)).thenReturn(List.of(metricsCurrent(2,7L,200L,"BOUND","GROUPED")));
        when(mapper.scopedGroupRows(managed)).thenReturn(List.of(new SupportAnalyticsMapper.GroupRow(200L,8L,"ENABLED",1)));
        var first=fact(2,20,Kind.DEPOSIT,firstCurrency,"20",AT.plusDays(daysOffset),null);
        when(finance.readHistory(any())).thenReturn(withFirstHistory(List.of(first),List.of(new SupportPaymentFacts.FirstHistory(2,SupportPaymentFacts.Status.READY,List.of()))));
        events(List.of(first));doReturn(List.of(attributedToGroup(first,200))).when(mapper).attributions(any(),any());
        var result=complete.summarize(period(ReadMode.MANAGED,"USDT","Asia/Shanghai",AT.minusHours(1),AT.plusHours(1)));
        assertThat(result.groups()).singleElement().satisfies(g->{
            assertThat(g.period().firstCandidates().observedValue()).isZero();assertThat(g.period().firstCandidates().confirmedValue()).isZero();
            assertThat(g.period().firstSources().get(0).deposits().confirmedAmount()).isEqualByComparingTo("0");
            assertThat(g.period().currencies().get(0).deposits().confirmedAmount()).isNull();
        });
    }
    private static AttributionRow attributedToGroup(Fact fact,long group) {
        var r=attribution(fact);
        return new AttributionRow(r.factId(),r.customerId(),r.kind(),r.source(),r.ledgerId(),r.sourceBusinessId(),r.orderNo(),r.orderType(),r.originalFactId(),
            r.currency(),r.amount(),r.succeededAt(),r.sourceBusinessZone(),r.successTimeField(),r.fractionalSecondDigits(),r.captureMode(),r.captureSchemaVersion(),
            r.agentAdminId(),group,r.ownerAdminId(),r.agentStatus(),r.groupStatus(),r.ownerStatus());
    }
    @Test void malformedBatchBoundariesFailClosedWithoutPuttingForeignIdentifiersInErrorText() {
        var complete=enriched();when(finance.readHistory(any())).thenReturn(snapshot(List.of()));
        var invitationScope=complete;
        doReturn(List.of(new ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Invitation(9009009,0,List.of(),List.of(),ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Completeness.COMPLETE,Set.of()))).when(invitations).readInvitations(any());
        assertThatThrownBy(()->invitationScope.summarize(history("USDT"))).hasMessage("SUPPORT_ANALYTICS_INVITATION_SCOPE_INVALID");
        complete=enriched();var deviceScope=complete;
        when(devices.readCurrent(any())).thenReturn(new ffdd.opsconsole.device.facade.SupportDeviceReadFacade.Snapshot(List.of(device(9009009,9009009,null,"GIFT","PRODUCTION",ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus.UNKNOWN)),List.of(),AT));
        assertThatThrownBy(()->deviceScope.summarize(history("USDT"))).hasMessage("SUPPORT_ANALYTICS_DEVICE_SCOPE_INVALID");
        var activityScope=enriched();when(mapper.activityEvents(any(),any(),any())).thenReturn(List.of(new SupportAnalyticsMapper.ActivityRow(9009009L,AT)));
        assertThatThrownBy(()->activityScope.summarize(history("USDT"))).hasMessage("SUPPORT_ANALYTICS_ACTIVITY_SCOPE_INVALID");
    }
    @Test void conflictingQualificationsAndMembersNeverChooseAnArbitraryGroupOrRecoverCategoryFromLegacyProfile() {
        var complete=enriched();var all=new ReadScope(8L,ReadMode.ALL,null,null);when(ownership.queryScope(ReadMode.ALL,null,null)).thenReturn(all);when(finance.readHistory(any())).thenReturn(snapshot(List.of()));
        when(mapper.scopedGroupRows(all)).thenReturn(List.of(new SupportAnalyticsMapper.GroupRow(100L,8L,"ENABLED",1),new SupportAnalyticsMapper.GroupRow(200L,8L,"ENABLED",1)));
        var first=roster(7,0,"GENERAL",0,"SERVICE","DISABLED",70L,100L);var other=roster(7,0,"GENERAL",0,"SERVICE","ENABLED",71L,200L);
        when(mapper.serviceAccountRows(all)).thenReturn(List.of(first,other));
        var result=complete.summarize(new Query(ReadMode.ALL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"UTC","USDT"));
        assertThat(result.personnel().serviceAccounts().observedValue()).isZero();assertThat(result.personnel().serviceAccounts().confirmedValue()).isNull();assertThat(result.personnel().groupMembers().observedValue()).isZero();assertThat(result.personnel().groupMembers().confirmedValue()).isNull();
        assertThat(result.personnel().accounts()).singleElement().satisfies(a->{assertThat(a.groupId()).isNull();assertThat(a.memberState()).isEqualTo(MemberState.UNKNOWN);assertThat(a.serviceQualification()).isEqualTo(QualificationState.UNKNOWN);assertThat(a.serviceAccount()).isFalse();assertThat(a.serviceCategoryStatus()).isEqualTo(Status.UNKNOWN);});
        assertThat(result.personnel().supervisors().status()).isEqualTo(Status.UNAVAILABLE);verify(mapper,never()).supervisorAccountRows(any());
    }
    @Test void allDirectoryCountsDisabledSupervisorAndMemberOverlapOnlyWithExistingPlatformAuthority() {
        var complete=enriched();var all=new ReadScope(8L,ReadMode.ALL,null,null);when(ownership.queryScope(ReadMode.ALL,null,null)).thenReturn(all);when(finance.readHistory(any())).thenReturn(snapshot(List.of()));
        when(mapper.scopedGroupRows(all)).thenReturn(List.of(new SupportAnalyticsMapper.GroupRow(100L,8L,"ENABLED",1)));
        when(mapper.serviceAccountRows(all)).thenReturn(List.of(roster(8,1,"MANAGER",1,"SERVICE","ENABLED",80L,100L)));
        when(mapper.supervisorAccountRows(all)).thenReturn(List.of(roster(8,1,"MANAGER",1,"SUPERVISOR","ENABLED",80L,100L),roster(9,0,"MANAGER",0,"SUPERVISOR","DISABLED",90L,null)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("8","",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a1_read"))));
        try {
            var result=complete.summarize(new Query(ReadMode.ALL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"UTC","USDT"));
            assertThat(result.personnel().serviceAccounts().confirmedValue()).isEqualTo(1);assertThat(result.personnel().supervisors().confirmedValue()).isEqualTo(2);assertThat(result.personnel().people().confirmedValue()).isEqualTo(2);
            assertThat(result.personnel().partitions()).filteredOn(p->p.dimension().equals("SUPERVISOR_ACCOUNT") && p.value().equals("DISABLED")).singleElement().satisfies(p->assertThat(p.accounts().confirmedValue()).isEqualTo(1));
            verify(mapper).supervisorAccountRows(all);
        }finally{SecurityContextHolder.clearContext();}
    }
    @Test void legacyConstructorDoesNotMakeAbsentMetricsAvailableZero() {
        when(finance.readHistory(any())).thenReturn(snapshot(List.of()));var result=service.summarize(history("USDT"));
        assertThat(result.currentMetrics().status()).isEqualTo(Status.UNAVAILABLE);assertThat(result.personnel().serviceAccounts().observedValue()).isNull();
        assertThat(result.currentCustomers().get(0).metrics().devices().held().status()).isEqualTo(Status.UNAVAILABLE);
        verifyNoInteractions(invitations,devices);
    }
    @Test void newConstructorNoGroupManagerHasOnlyItsOwnIdentityAndNoFinancialOrDeviceFallback() {
        var complete=enriched();var managed=new ReadScope(8L,ReadMode.MANAGED,null,null);when(ownership.queryScope(ReadMode.MANAGED,null,null)).thenReturn(managed);
        when(mapper.currentCustomers(managed)).thenReturn(List.of());when(mapper.supervisorAccountRows(managed)).thenReturn(List.of(roster(8,1,"MANAGER",1,"SUPERVISOR","ENABLED",80L,null)));
        var result=complete.summarize(new Query(ReadMode.MANAGED,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"UTC","USDT"));
        assertThat(result.currentScope().total()).isZero();assertThat(result.groups()).isEmpty();assertThat(result.personnel().groups().confirmedValue()).isZero();
        assertThat(result.personnel().groupMembers().confirmedValue()).isZero();assertThat(result.personnel().supervisors().confirmedValue()).isEqualTo(1);assertThat(result.personnel().people().confirmedValue()).isEqualTo(1);
        verifyNoInteractions(finance,devices,invitations);verify(mapper,never()).activityEvents(any(),any(),any());verify(mapper,never()).legacyProductionCustomers(any());
    }
    @Test void missingOrFailedNewSourcesStayUnknownOrFailedAndCannotConfirmZero() {
        var complete=enriched();when(finance.readHistory(any())).thenReturn(snapshot(List.of()));when(mapper.activityCoverage(any())).thenReturn(null);
        when(devices.readCurrent(any())).thenThrow(new DataAccessResourceFailureException("hidden 9009009"));
        var result=complete.summarize(history("USDT"));
        assertThat(result.currentCustomers().get(0).metrics().lifetimeStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(result.currentCustomers().get(0).metrics().lifetime().get(0).deposits().observedAmount()).isNull();
        assertThat(result.currentMetrics().devices().held().status()).isEqualTo(Status.FAILED);assertThat(result.currentMetrics().devices().held().confirmedValue()).isNull();
        assertThat(result.currentMetrics().activity().active().confirmedValue()).isNull();assertThat(result.currentMetrics().activity().unknown().observedValue()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("hidden 9009009");verify(mapper,never()).activityEvents(any(),any(),any());
    }
    @Test void descendantFinancialBoundaryAndReadErrorsAreHardFailuresWithSafeTopLevelMessages() {
        var complete=enriched();when(finance.readHistory(List.of(1L))).thenReturn(snapshot(List.of()));
        doReturn(List.of(new ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Invitation(1,0,List.of(9009009L),List.of(9009009L),ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Completeness.COMPLETE,Set.of()))).when(invitations).readInvitations(any());
        when(finance.readHistory(List.of(9009009L))).thenReturn(snapshot(List.of(fact(9009010,999,Kind.DEPOSIT,"USDT","1",AT,null))));
        assertThatThrownBy(()->complete.summarize(history("USDT"))).hasMessage("SUPPORT_ANALYTICS_FINANCIAL_SCOPE_INVALID");
        when(finance.readHistory(List.of(9009009L))).thenThrow(new DataAccessResourceFailureException("private customer 9009009"));
        assertThatThrownBy(()->complete.summarize(history("USDT"))).hasMessage("SUPPORT_ANALYTICS_DESCENDANT_SOURCE_READ_FAILED");
    }
    @ParameterizedTest
    @CsvSource({"GENERAL,1","GENERAL,0","DEDICATED,1","DEDICATED,0"})
    void removedServiceCannotBeRecreatedByEnabledOrDisabledLegacyProfile(String seat,int profileEnabled) {
        var people=personnelStats(List.of(roster(7,1,seat,profileEnabled,"SERVICE","REMOVED",70L,100L)),List.of());
        assertThat(people.serviceAccounts().confirmedValue()).isZero();assertThat(people.people().confirmedValue()).isZero();
        assertThat(people.groupMembers().confirmedValue()).isZero();
        assertThat(people.accounts()).singleElement().satisfies(a->{assertThat(a.serviceAccount()).isFalse();assertThat(a.serviceQualification()).isEqualTo(QualificationState.REMOVED);assertThat(a.serviceCategoryStatus()).isEqualTo(Status.AVAILABLE);assertThat(a.receptionState()).isEqualTo(AccountState.DISABLED);assertThat(a.handoverRequired()).isTrue();});
    }
    @ParameterizedTest
    @ValueSource(ints={0,1})
    void removedSupervisorCannotBeRecreatedByManagerProfile(int profileEnabled) {
        var people=personnelStats(List.of(),List.of(roster(7,1,"MANAGER",profileEnabled,"SUPERVISOR","REMOVED",70L,100L)));
        assertThat(people.supervisors().confirmedValue()).isZero();assertThat(people.people().confirmedValue()).isZero();
        assertThat(people.groupMembers().confirmedValue()).isZero();
        assertThat(people.accounts()).singleElement().satisfies(a->{assertThat(a.supervisorAccount()).isFalse();assertThat(a.supervisorQualification()).isEqualTo(QualificationState.REMOVED);assertThat(a.supervisorCategoryStatus()).isEqualTo(Status.AVAILABLE);});
    }
    @ParameterizedTest
    @CsvSource({"REMOVED,ENABLED,0,1","REMOVED,DISABLED,0,1","ENABLED,REMOVED,1,0","DISABLED,REMOVED,1,0"})
    void removalOfOneIdentityPreservesTheOtherEnabledOrDisabledCategoryAndCountsPeopleOnce(String serviceState,String supervisorState,long expectedServices,long expectedSupervisors) {
        var people=personnelStats(List.of(roster(7,1,"MANAGER",1,"SERVICE",serviceState,70L,100L)),List.of(roster(7,1,"MANAGER",1,"SUPERVISOR",supervisorState,70L,100L)));
        assertThat(people.serviceAccounts().confirmedValue()).isEqualTo(expectedServices);assertThat(people.supervisors().confirmedValue()).isEqualTo(expectedSupervisors);assertThat(people.people().confirmedValue()).isEqualTo(1);
        assertThat(people.accounts()).singleElement().satisfies(a->{assertThat(a.serviceAccount()).isEqualTo(expectedServices==1);assertThat(a.supervisorAccount()).isEqualTo(expectedSupervisors==1);});
    }
    @ParameterizedTest
    @CsvSource({"SERVICE,GENERAL","SERVICE,DEDICATED","SUPERVISOR,MANAGER"})
    void conflictingCurrentQualificationsNeverUseProfileAsConfirmedCategoryOrReception(String kind,String seat) {
        var enabled=roster(7,1,seat,1,kind,"ENABLED",70L,100L);
        var disabled=qualificationRow(enabled,enabled.qualificationId()+1,"DISABLED");
        var people=personnelStats(kind.equals("SERVICE")?List.of(enabled,disabled):List.of(),kind.equals("SUPERVISOR")?List.of(enabled,disabled):List.of());
        var count=kind.equals("SERVICE")?people.serviceAccounts():people.supervisors();assertThat(count.observedValue()).isZero();assertThat(count.confirmedValue()).isNull();assertThat(count.status()).isEqualTo(Status.PARTIAL);
        assertThat(people.groupMembers().observedValue()).isZero();
        if(kind.equals("SERVICE"))assertThat(people.groupMembers().confirmedValue()).isNull();
        else assertThat(people.groupMembers().confirmedValue()).isZero();
        var dimensions=kind.equals("SERVICE")?Set.of("SERVICE_ACCOUNT","RECEPTION","SERVICE_QUALIFICATION"):Set.of("SUPERVISOR_ACCOUNT","SUPERVISOR_QUALIFICATION");
        assertThat(people.partitions()).filteredOn(p->dimensions.contains(p.dimension())).isNotEmpty().allSatisfy(p->{
            assertThat(p.accounts().observedValue()).isZero();assertThat(p.accounts().confirmedValue()).isNull();assertThat(p.accounts().status()).isEqualTo(Status.PARTIAL);
        });
        assertThat(people.accounts()).singleElement().satisfies(a->{assertThat(a.accountState()).isEqualTo(AccountState.ENABLED);assertThat(a.receptionState()).isEqualTo(AccountState.UNKNOWN);assertThat(kind.equals("SERVICE")?a.serviceAccount():a.supervisorAccount()).isFalse();assertThat(kind.equals("SERVICE")?a.serviceQualification():a.supervisorQualification()).isEqualTo(QualificationState.UNKNOWN);});
    }
    @ParameterizedTest
    @CsvSource({"SERVICE,GENERAL","SERVICE,DEDICATED","SUPERVISOR,MANAGER"})
    void profileCompatibilityAppliesOnlyWhenThatCurrentQualificationIsAbsent(String kind,String seat) {
        var original=roster(7,1,seat,1,kind,"ENABLED",70L,100L);var absent=qualificationRow(original,null,null);
        var people=personnelStats(kind.equals("SERVICE")?List.of(absent):List.of(),kind.equals("SUPERVISOR")?List.of(absent):List.of());
        assertThat(kind.equals("SERVICE")?people.serviceAccounts().confirmedValue():people.supervisors().confirmedValue()).isEqualTo(1);assertThat(people.people().confirmedValue()).isEqualTo(1);
        assertThat(people.accounts()).singleElement().satisfies(a->{assertThat(kind.equals("SERVICE")?a.serviceAccount():a.supervisorAccount()).isTrue();assertThat(kind.equals("SERVICE")?a.serviceQualification():a.supervisorQualification()).isEqualTo(QualificationState.UNKNOWN);assertThat(a.receptionState()).isEqualTo(AccountState.UNKNOWN);});
    }
    @ParameterizedTest
    @CsvSource({"SERVICE,MANAGER","SUPERVISOR,GENERAL","SUPERVISOR,DEDICATED"})
    void conflictingQualificationsCannotBeExcludedByOppositeProfile(String kind,String seat) {
        var enabled=roster(7,1,seat,1,kind,"ENABLED",70L,100L);
        var disabled=qualificationRow(enabled,enabled.qualificationId()+1,"DISABLED");
        var other=roster(7,1,seat,1,kind.equals("SERVICE")?"SUPERVISOR":"SERVICE","ENABLED",70L,100L);
        var people=personnelStats(kind.equals("SERVICE")?List.of(enabled,disabled):List.of(other),kind.equals("SUPERVISOR")?List.of(enabled,disabled):List.of(other));
        var count=kind.equals("SERVICE")?people.serviceAccounts():people.supervisors();
        assertThat(count.observedValue()).isZero();assertThat(count.confirmedValue()).isNull();assertThat(count.status()).isEqualTo(Status.PARTIAL);
        assertThat(kind.equals("SERVICE")?people.supervisors().confirmedValue():people.serviceAccounts().confirmedValue()).isEqualTo(1);
        assertThat(people.people().observedValue()).isEqualTo(1);assertThat(people.people().confirmedValue()).isNull();
        if(kind.equals("SERVICE"))assertThat(people.groupMembers().confirmedValue()).isNull();
        else assertThat(people.groupMembers().confirmedValue()).isEqualTo(1);
        var dimensions=kind.equals("SERVICE")?Set.of("SERVICE_ACCOUNT","RECEPTION","SERVICE_QUALIFICATION"):Set.of("SUPERVISOR_ACCOUNT","SUPERVISOR_QUALIFICATION");
        assertThat(people.partitions()).filteredOn(p->dimensions.contains(p.dimension())).isNotEmpty().allSatisfy(p->{
            assertThat(p.accounts().observedValue()).isZero();assertThat(p.accounts().confirmedValue()).isNull();assertThat(p.accounts().status()).isEqualTo(Status.PARTIAL);
        });
    }
    @ParameterizedTest
    @CsvSource({"REMOVED,REMOVED,0,0","REMOVED,ENABLED,0,1","ENABLED,REMOVED,1,1","DISABLED,REMOVED,1,1"})
    void groupMembershipRequiresServiceQualificationInBothManagementAndPlatform(String serviceState,String supervisorState,long members,long peopleCount) {
        for(var mode:List.of(ReadMode.ALL,ReadMode.MANAGED)) {
            var people=personnelStats(List.of(roster(7,1,"MANAGER",1,"SERVICE",serviceState,70L,100L)),
                List.of(roster(7,1,"MANAGER",1,"SUPERVISOR",supervisorState,70L,100L)),mode);
            assertThat(people.groupMembers().confirmedValue()).isEqualTo(members);
            assertThat(people.people().confirmedValue()).isEqualTo(peopleCount);
            assertThat(people.partitions()).filteredOn(p->p.dimension().equals("MEMBERSHIP") && p.value().equals("GROUPED"))
                .singleElement().satisfies(p->assertThat(p.accounts().confirmedValue()).isEqualTo(members));
            assertThat(people.partitions()).filteredOn(p->p.dimension().equals("GROUP_MEMBER_ACCOUNT") && p.value().equals("ENABLED"))
                .singleElement().satisfies(p->assertThat(p.accounts().confirmedValue()).isEqualTo(members));
        }
    }
    @Test void supervisorWithoutServiceQualificationIsNotAServiceMember() {
        var supervisor=roster(7,1,"MANAGER",1,"SUPERVISOR","ENABLED",70L,100L);
        var noService=qualificationRow(roster(7,1,"MANAGER",1,"SERVICE","ENABLED",70L,100L),null,null);
        var people=personnelStats(List.of(noService),List.of(supervisor),ReadMode.MANAGED);
        assertThat(people.serviceAccounts().confirmedValue()).isZero();assertThat(people.groupMembers().confirmedValue()).isZero();
        assertThat(people.supervisors().confirmedValue()).isEqualTo(1);assertThat(people.people().confirmedValue()).isEqualTo(1);
    }
    private PersonnelSummary personnelStats(List<SupportAnalyticsMapper.RosterRow> serviceRows,List<SupportAnalyticsMapper.RosterRow> supervisorRows) {
        return personnelStats(serviceRows,supervisorRows,ReadMode.ALL);
    }
    private PersonnelSummary personnelStats(List<SupportAnalyticsMapper.RosterRow> serviceRows,List<SupportAnalyticsMapper.RosterRow> supervisorRows,ReadMode mode) {
        var complete=enriched();long actor=mode==ReadMode.MANAGED?7L:8L;var all=new ReadScope(actor,mode,null,null);when(ownership.queryScope(mode,null,null)).thenReturn(all);
        when(mapper.currentCustomers(all)).thenReturn(List.of());when(mapper.scopedGroupRows(all)).thenReturn(List.of(new SupportAnalyticsMapper.GroupRow(100L,actor,"ENABLED",1)));
        when(mapper.serviceAccountRows(all)).thenReturn(serviceRows);when(mapper.supervisorAccountRows(all)).thenReturn(supervisorRows);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(actor),"",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a1_read"))));
        try {return complete.summarize(new Query(mode,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"UTC","USDT")).personnel();}
        finally {SecurityContextHolder.clearContext();}
    }
    private static SupportAnalyticsMapper.RosterRow qualificationRow(SupportAnalyticsMapper.RosterRow original,Long qualificationId,String state) {
        return new SupportAnalyticsMapper.RosterRow(original.accountId(),original.accountStatus(),original.profileEnabled(),original.profileDeleted(),original.profileSeatType(),
            qualificationId,qualificationId==null?null:original.qualificationKind(),state,qualificationId==null?null:original.qualificationStartsAt(),qualificationId==null?null:original.qualificationEndsAt(),
            original.memberId(),original.groupId(),original.memberStartsAt(),original.memberEndsAt(),original.evaluatedDbAt(),original.compatibleRole());
    }
    private static SupportAnalyticsMapper.CurrentCustomer metricsCurrent(long id,Long agent,Long group,String category,String placement) {
        return new SupportAnalyticsMapper.CurrentCustomer(id,category,placement,0,agent,group);
    }
    private static ffdd.opsconsole.device.facade.SupportDeviceReadFacade.DeviceEvidence device(long id,long customer,String order,String channel,String environment,ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus connection) {
        return new ffdd.opsconsole.device.facade.SupportDeviceReadFacade.DeviceEvidence(id,customer,order,channel,"SHARE",BigDecimal.ONE,"OWNED","ONLINE",AT.minusDays(9),null,1,environment,null,null,connection);
    }
    private static SupportAnalyticsMapper.RosterRow roster(long id,int status,String seat,int enabled,String kind,String state,Long member,Long group) {
        return new SupportAnalyticsMapper.RosterRow(id,status,enabled,0,seat,member+(kind.equals("SERVICE")?1000:2000),kind,state,AT.minusDays(10),null,member,group,AT.minusDays(10),null,AT,1);
    }
}
