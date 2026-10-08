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
        when(mapper.attributions(any(),any())).thenReturn(List.of(new AttributionRow(r.factId(),r.customerId(),r.kind(),r.source(),r.ledgerId(),r.sourceBusinessId(),
            r.orderNo(),r.orderType(),r.originalFactId(),r.currency(),r.amount(),r.succeededAt(),r.sourceBusinessZone(),r.successTimeField(),r.fractionalSecondDigits(),
            "OLD_SOURCE",r.captureSchemaVersion(),null,null,null,"UNKNOWN","UNKNOWN","UNKNOWN")));
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
        assertThat(managedResult.financialSummary().firstCandidates().observedValue()).isZero();
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
        when(mapper.attributions(any(),any())).thenReturn(facts.stream().map(SupportAnalyticsServiceTest::attribution).toList());
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
}
