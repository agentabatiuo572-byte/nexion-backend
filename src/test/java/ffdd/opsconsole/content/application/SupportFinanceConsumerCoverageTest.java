package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.Status;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper;
import ffdd.opsconsole.finance.application.FinanceSupportReadService;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.finance.mapper.DepositOrderMapper;
import ffdd.opsconsole.finance.mapper.WithdrawalOrderMapper;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SupportFinanceConsumerCoverageTest {
    private static final LocalDateTime AT=LocalDateTime.of(2026,10,10,10,0);
    private static final Set<Source> DEPOSITS=Set.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.VIETQR,Source.HDPAY);
    private static final Set<Source> PURCHASES=Set.of(Source.WALLET_ORDER,Source.TRADE_IN,Source.CAPACITY_KEEP,Source.TRIAL_CONVERT);
    private static Fact fact(long customer,long ledger,Kind kind,String currency,String amount) {
        String order=kind==Kind.DEPOSIT?null:"order-"+ledger;
        return new Fact(kind==Kind.DEPOSIT?"DEPOSIT:"+ledger:kind==Kind.DEVICE_PURCHASE?"PURCHASE:"+order:"ORDER_REFUND:"+ledger,
            kind,kind==Kind.DEPOSIT?Source.CARD_TOPUP:kind==Kind.DEVICE_PURCHASE?Source.WALLET_ORDER:Source.ORDER_REFUND,
            List.of("source-"+ledger),customer,ledger,"source-"+ledger,order,order==null?null:"NEW",
            kind==Kind.DEVICE_PURCHASE_REFUND?"PURCHASE:"+order:null,currency,new BigDecimal(amount),AT,
            kind==Kind.DEVICE_PURCHASE?"nx_order.paid_at":"nx_wallet_ledger.created_at",0,null,AT,null,"v1",SupportPaymentFacts.Status.READY);
    }
    private static List<CustomerCoverage> ready(long customer,Set<Source> sources) {
        return sources.stream().map(s->new CustomerCoverage(customer,s,SupportPaymentFacts.Status.READY,SupportPaymentFacts.Status.UNKNOWN,
            SupportPaymentFacts.Status.READY,AT.minusDays(1),List.of())).toList();
    }
    private static List<Coverage> coverage() {
        return Arrays.stream(Source.values()).filter(s->DEPOSITS.contains(s)||PURCHASES.contains(s)||s==Source.ORDER_REFUND)
            .map(s->new Coverage(s,SupportPaymentFacts.Status.READY,SupportPaymentFacts.Status.READY,SupportPaymentFacts.Status.READY,
                SupportPaymentFacts.Status.READY,AT.minusDays(1),List.of(),0,"test")).toList();
    }
    private static Snapshot snapshot(List<Fact> facts,List<CustomerCoverage> scoped) {
        return new Snapshot(facts,List.of(),coverage(),"Asia/Shanghai",Instant.parse("2026-10-10T04:00:00Z"),List.of(),scoped);
    }
    private static Result summary(Snapshot snapshot,Long... customers) {
        var ownership=mock(SupportOwnershipService.class);var finance=mock(FinanceSupportPaymentFactsFacade.class);var mapper=mock(SupportAnalyticsMapper.class);
        when(ownership.queryScope(ReadMode.PERSONAL,null,null)).thenReturn(new ReadScope(7L,ReadMode.PERSONAL,null,null));
        when(mapper.currentCustomers(any())).thenReturn(Arrays.stream(customers).map(id->new SupportAnalyticsMapper.CurrentCustomer(id,"BOUND","GROUPED",0)).toList());
        when(finance.readHistory(any())).thenReturn(snapshot);
        return new SupportAnalyticsService(ownership,finance,mapper).summarize(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT"));
    }
    @Test void oldGlobalCertificateAndFirstHistoryCannotCertifyMissingCustomerMoneyProof() {
        var old=new Snapshot(List.of(),List.of(),coverage(),"Asia/Shanghai",Instant.now(),List.of(new FirstHistory(1,SupportPaymentFacts.Status.READY,List.of())));
        var money=summary(old,1L).financialSummary().currencies().get(0).deposits();
        assertThat(money.status()).isEqualTo(Status.UNKNOWN);assertThat(money.observedAmount()).isNull();assertThat(money.confirmedAmount()).isNull();
    }
    @Test void completeCustomerProofCertifiesTrueZeroWithoutRefundDependency() {
        var money=summary(snapshot(List.of(),ready(1,DEPOSITS)),1L).financialSummary().currencies().get(0).deposits();
        assertThat(money.status()).isEqualTo(Status.AVAILABLE);assertThat(money.confirmedAmount()).isZero();
    }
    @Test void mixedHistoryKeepsPositiveConfirmedSubtotalButUnknownZeroDoesNotBecomeZero() {
        var facts=List.of(fact(1,1,Kind.DEPOSIT,"USDT","10.123456"),fact(2,2,Kind.DEPOSIT,"USDT","90"));
        var money=summary(snapshot(facts,ready(1,DEPOSITS)),1L,2L).financialSummary().currencies().get(0).deposits();
        assertThat(money.status()).isEqualTo(Status.PARTIAL);assertThat(money.observedAmount()).isEqualByComparingTo("100.123456");
        assertThat(money.confirmedAmount()).isEqualByComparingTo("10.123456");assertThat(money.confirmedEvents()).isEqualTo(1);
        money=summary(snapshot(List.of(),ready(1,DEPOSITS)),1L,2L).financialSummary().currencies().get(0).deposits();
        assertThat(money.status()).isEqualTo(Status.UNKNOWN);assertThat(money.observedAmount()).isNull();assertThat(money.confirmedAmount()).isNull();
    }
    @Test void purchaseGrossDoesNotBorrowRefundProofAndCurrenciesStaySeparate() {
        var facts=List.of(fact(1,1,Kind.DEVICE_PURCHASE,"USDT","80.123456"),fact(1,2,Kind.DEVICE_PURCHASE,"UVT","500"));
        var scoped=ready(1,PURCHASES).stream().map(c->new CustomerCoverage(c.customerId(),c.source(),c.historyStatus(),c.refundStatus(),
            c.historicalEnvironmentStatus(),c.supportedFrom(),List.of("REFUND_SOURCE_READ_FAILED"))).toList();
        var result=summary(snapshot(facts,scoped),1L).financialSummary().currencies().get(0);
        assertThat(result.purchases().status()).isEqualTo(Status.AVAILABLE);assertThat(result.purchases().confirmedAmount()).isEqualByComparingTo("80.123456");
        assertThat(result.purchaseRefunds().status()).isEqualTo(Status.UNKNOWN);assertThat(result.net().confirmedAmount()).isNull();
    }
    @Test void customerCoverageCannotEscapeAuthorizedBoundaryOrDuplicateSource() {
        assertThatThrownBy(()->summary(snapshot(List.of(),ready(2,DEPOSITS)),1L)).hasMessage("SUPPORT_ANALYTICS_FINANCIAL_SCOPE_INVALID");
        var duplicate=new ArrayList<>(ready(1,DEPOSITS));duplicate.add(duplicate.get(0));
        assertThatThrownBy(()->summary(snapshot(List.of(),duplicate),1L)).hasMessage("SUPPORT_ANALYTICS_FINANCIAL_COVERAGE_INVALID");
    }
    @Test void oldFundsMissingSourceDoesNotInventTotalsOrLendHistoryReadiness() {
        var deposits=mock(DepositOrderMapper.class);var withdrawals=mock(WithdrawalOrderMapper.class);
        when(deposits.supportBalances(1L)).thenReturn(List.of(Map.of("currency","USDT","availableBalance",new BigDecimal("5"))));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)new FinanceSupportReadService(deposits,withdrawals).totals(1L).get("byCurrency");
        var row=rows.get(0);assertThat(row.get("creditedDepositTotal")).isNull();assertThat(row.get("successfulWithdrawalPrincipalTotal")).isNull();
        assertThat(row).containsEntry("depositRefundTotal","0").containsEntry("availableBalance","5");
        when(deposits.supportCredits(1L)).thenReturn(List.of(Map.of("currency","USDT","creditedDepositTotal",new BigDecimal("31.000031"))));
        @SuppressWarnings("unchecked") var positive=(List<Map<String,Object>>)new FinanceSupportReadService(deposits,withdrawals).totals(1L).get("byCurrency");
        assertThat(positive.get(0)).containsEntry("creditedDepositTotal","31.000031");
        assertThat(((Map<?,?>)positive.get(0).get("fieldStatuses")).get("creditedDepositTotal")).isEqualTo("PARTIAL");
    }
    @Test void refundDomainNeedsItsOwnCertificateAndKeepsGrossPurchaseAndNetSeparate() {
        var facts=List.of(fact(1,1,Kind.DEVICE_PURCHASE,"USDT","80"),fact(1,1,Kind.DEVICE_PURCHASE_REFUND,"USDT","80"));
        var scoped=new ArrayList<>(ready(1,PURCHASES));
        scoped.add(new CustomerCoverage(1,Source.ORDER_REFUND,SupportPaymentFacts.Status.READY,SupportPaymentFacts.Status.READY,
            SupportPaymentFacts.Status.READY,AT.minusDays(1),List.of()));
        var result=summary(snapshot(facts,scoped),1L).financialSummary().currencies().get(0);
        assertThat(result.purchases().confirmedAmount()).isEqualByComparingTo("80");assertThat(result.purchaseRefunds().confirmedAmount()).isEqualByComparingTo("80");
        assertThat(result.net().status()).isEqualTo(Status.UNKNOWN);assertThat(result.net().observedAmount()).isNull();
        scoped.removeIf(c->c.source()==Source.ORDER_REFUND);
        result=summary(snapshot(facts,scoped),1L).financialSummary().currencies().get(0);
        assertThat(result.purchases().confirmedAmount()).isEqualByComparingTo("80");assertThat(result.purchaseRefunds().confirmedAmount()).isNull();
    }
    @Test void sourceReadFailureForAnotherCustomerCannotEraseKnownPositiveSubtotal() {
        var facts=List.of(fact(1,1,Kind.DEPOSIT,"USDT","10"));var base=snapshot(facts,ready(1,DEPOSITS));
        var source=new Snapshot(facts,List.of(new Issue(Source.CARD_TOPUP,null,"SOURCE_READ_FAILED",2L)),base.coverage(),base.businessZone(),base.evaluatedAt(),List.of(),base.customerCoverage());
        var money=summary(source,1L,2L).financialSummary().currencies().get(0).deposits();
        assertThat(money.status()).isEqualTo(Status.PARTIAL);assertThat(money.confirmedAmount()).isEqualByComparingTo("10");
    }
    @Test void unmatchedDepositGapCannotOverrulePurchaseCustomerSourceCertificate() {
        var facts=List.of(fact(1,1,Kind.DEVICE_PURCHASE,"USDT","80"));var base=snapshot(facts,ready(1,PURCHASES));
        var source=new Snapshot(facts,List.of(new Issue(Source.UNMATCHED_LEDGER,"orphan-deposit","MISSING_AUTHORITATIVE_SOURCE",1L)),
            base.coverage(),base.businessZone(),base.evaluatedAt(),List.of(),base.customerCoverage());
        var totals=summary(source,1L).financialSummary().currencies().get(0);
        assertThat(totals.purchases().confirmedAmount()).isEqualByComparingTo("80");assertThat(totals.purchases().status()).isEqualTo(Status.AVAILABLE);
        assertThat(totals.deposits().confirmedAmount()).isNull();
    }
    @Test void missingHistoryOrEnvironmentOrSourceStillBlocksZeroAndKeepsObservedPositive() {
        for(String gap:List.of("HISTORY","ENVIRONMENT","MISSING")) {
            var scoped=new ArrayList<>(ready(1,DEPOSITS));
            if(gap.equals("MISSING"))scoped.removeIf(c->c.source()==Source.HDPAY);
            else scoped.replaceAll(c->c.source()!=Source.CARD_TOPUP?c:new CustomerCoverage(c.customerId(),c.source(),
                gap.equals("HISTORY")?SupportPaymentFacts.Status.UNKNOWN:c.historyStatus(),c.refundStatus(),
                gap.equals("ENVIRONMENT")?SupportPaymentFacts.Status.UNKNOWN:c.historicalEnvironmentStatus(),c.supportedFrom(),List.of(gap)));
            var empty=summary(snapshot(List.of(),scoped),1L).financialSummary().currencies().get(0).deposits();
            assertThat(empty.status()).as(gap).isEqualTo(Status.UNKNOWN);assertThat(empty.observedAmount()).isNull();
            var positive=summary(snapshot(List.of(fact(1,1,Kind.DEPOSIT,"USDT","10")),scoped),1L).financialSummary().currencies().get(0).deposits();
            assertThat(positive.status()).isEqualTo(Status.PARTIAL);assertThat(positive.observedAmount()).isEqualByComparingTo("10");assertThat(positive.confirmedAmount()).isNull();
        }
    }
    @Test void ownLifetimeAndInvitationReadUseTheSameCustomerProofAcrossBothSnapshots() {
        var ownership=mock(SupportOwnershipService.class);var finance=mock(FinanceSupportPaymentFactsFacade.class);var mapper=mock(SupportAnalyticsMapper.class);
        var invitations=mock(SupportInvitationReadFacade.class);var devices=mock(SupportDeviceReadFacade.class);
        when(ownership.queryScope(ReadMode.PERSONAL,null,null)).thenReturn(new ReadScope(7L,ReadMode.PERSONAL,null,null));
        when(mapper.currentCustomers(any())).thenReturn(List.of(new SupportAnalyticsMapper.CurrentCustomer(1L,"BOUND","GROUPED",0,7L,100L)));
        var ownProof=new ArrayList<>(ready(1,DEPOSITS));ownProof.addAll(ready(1,PURCHASES));
        var own=snapshot(List.of(fact(1,1,Kind.DEPOSIT,"USDT","10")),ownProof);
        var team=snapshot(List.of(fact(2,2,Kind.DEPOSIT,"USDT","20")),ready(2,DEPOSITS));
        when(finance.readHistory(List.of(1L))).thenReturn(own);when(finance.readHistory(List.of(2L))).thenReturn(team);
        when(invitations.readInvitations(any())).thenReturn(List.of(new SupportInvitationReadFacade.Invitation(1,0,List.of(2L),List.of(2L),SupportInvitationReadFacade.Completeness.COMPLETE,Set.of())));
        when(devices.readCurrent(any())).thenReturn(new SupportDeviceReadFacade.Snapshot(List.of(),List.of(),AT));
        var result=new SupportAnalyticsService(ownership,finance,mapper,invitations,devices).summarize(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT"));
        var metric=result.currentCustomers().get(0).metrics();
        assertThat(metric.lifetimeStatus()).isEqualTo(Status.AVAILABLE);assertThat(metric.lifetime().get(0).deposits().confirmedAmount()).isEqualByComparingTo("10");
        assertThat(metric.lifetime().get(0).purchases().confirmedAmount()).isZero();
        assertThat(metric.invitations().descendantDeposits().get(0).deposits().confirmedAmount()).isEqualByComparingTo("20");
        assertThat(result.currentMetrics().ownLifetime().get(0).deposits().confirmedAmount()).isEqualByComparingTo("10");
        var failedOwn=new Snapshot(List.of(),List.of(new Issue(Source.CARD_TOPUP,null,"SOURCE_READ_FAILED")),own.coverage(),own.businessZone(),own.evaluatedAt());
        when(finance.readHistory(List.of(1L))).thenReturn(failedOwn);
        result=new SupportAnalyticsService(ownership,finance,mapper,invitations,devices).summarize(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT"));
        assertThat(result.currentCustomers().get(0).metrics().invitations().descendantDeposits().get(0).deposits().confirmedAmount()).isEqualByComparingTo("20");
    }
}
