package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.content.application.SupportAnalyticsService;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.Money;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.Status;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.finance.mapper.DepositOrderMapper;
import ffdd.opsconsole.finance.mapper.WithdrawalOrderMapper;
import ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Stage behavior checks call real read methods with mocked sources; no DB/runtime acceptance. */
class SupportDepositNoRefundReadTest {
    private static final LocalDateTime AT=LocalDateTime.of(2026,10,10,10,0);
    private static final Set<Source> DEPOSITS=Set.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.VIETQR,Source.HDPAY);

    @Test void oldM3WireReportsPolicyZeroWithoutInventingBalanceOrHistory() {
        var deposits=mock(DepositOrderMapper.class);var withdrawals=mock(WithdrawalOrderMapper.class);
        when(deposits.supportCredits(7L)).thenReturn(List.of(Map.of("currency","USDT","creditedDepositTotal",new BigDecimal("31.000031"))));
        var totals=new FinanceSupportReadService(deposits,withdrawals).totals(7L);
        assertThat(totals).containsEntry("refundSourceStatus","NOT_APPLICABLE").containsEntry("balanceSourceStatus","UNKNOWN").containsEntry("completeness","PARTIAL");
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)totals.get("byCurrency");
        assertThat(rows).singleElement().satisfies(row->{
            assertThat(row).containsEntry("depositRefundTotal","0").containsEntry("creditedDepositTotal","31.000031").containsEntry("balance",null);
            @SuppressWarnings("unchecked") var states=(Map<String,String>)row.get("fieldStatuses");
            assertThat(states).containsEntry("depositRefundTotal","READY").containsEntry("balance","UNKNOWN");
        });
        when(deposits.supportCreditAnomalies(7L)).thenReturn(List.of(Map.of("currency","USDT","anomalies",1L)));
        @SuppressWarnings("unchecked") var bad=(List<Map<String,Object>>)new FinanceSupportReadService(deposits,withdrawals).totals(7L).get("byCurrency");
        assertThat(bad.get(0).get("creditedDepositTotal")).isNull();
    }

    @Test void financeDepositRefundCoverageIsPolicyReadyWhileHistoryAndPurchaseRemainUnverified() {
        var mapper=mock(SupportPaymentFactMapper.class);var service=new SupportPaymentFactService(mapper);
        var snapshot=service.read(List.of(7L));
        assertThat(snapshot.coverage()).filteredOn(c->DEPOSITS.contains(c.source())).hasSize(4).allSatisfy(c->{
            assertThat(c.refundStatus()).isEqualTo(SupportPaymentFacts.Status.READY);
            assertThat(c.historyStatus()).isEqualTo(SupportPaymentFacts.Status.UNKNOWN);
            assertThat(c.historicalEnvironmentStatus()).isEqualTo(SupportPaymentFacts.Status.UNKNOWN);
            assertThat(c.reasons()).contains("SUCCESSFUL_DEPOSITS_NON_REFUNDABLE").doesNotContain("FINAL_DEPOSIT_REFUND_SOURCE_UNAVAILABLE");
        });
        assertThat(snapshot.coverage()).filteredOn(c->c.source()==Source.WALLET_ORDER || c.source()==Source.ORDER_REFUND)
            .allSatisfy(c->assertThat(c.refundStatus()).isEqualTo(SupportPaymentFacts.Status.UNKNOWN));
        when(mapper.cards(any())).thenThrow(new DataAccessResourceFailureException("private details"));
        assertThat(service.read(List.of(7L)).coverage()).filteredOn(c->c.source()==Source.CARD_TOPUP).singleElement().satisfies(c->{
            assertThat(c.observedStatus()).isEqualTo(SupportPaymentFacts.Status.UNKNOWN);
            assertThat(c.refundStatus()).isEqualTo(SupportPaymentFacts.Status.READY);
            assertThat(c.reasons()).contains("SOURCE_READ_FAILED");
        });
    }

    @Test void certifiedDepositTotalsIgnoreRefundAbsenceKeepFullHistoryAndBothCurrencies() throws Exception {
        var facts=new ArrayList<Fact>();for(int i=1;i<=31;i++)facts.add(fact(i,"USDT","1.000001",Kind.DEPOSIT));
        facts.add(fact(32,"NEX","2.123456",Kind.DEPOSIT));facts.add(fact(33,"USDT","80",Kind.DEVICE_PURCHASE));
        var snapshot=snapshot(facts,complete());
        var usdt=money(facts,snapshot,Kind.DEPOSIT,"USDT",false,false);
        assertThat(usdt.status()).isEqualTo(Status.AVAILABLE);assertThat(usdt.observedAmount()).isEqualByComparingTo("31.000031");
        assertThat(usdt.confirmedAmount()).isEqualByComparingTo("31.000031");assertThat(usdt.confirmedEvents()).isEqualTo(31);assertThat(usdt.reasons()).isEmpty();
        assertThat(money(facts,snapshot,Kind.DEPOSIT,"NEX",false,false).confirmedAmount()).isEqualByComparingTo("2.123456");
        var purchases=money(facts,snapshot,Kind.DEVICE_PURCHASE,"USDT",false,false);
        assertThat(purchases.status()).isEqualTo(Status.PARTIAL);assertThat(purchases.confirmedAmount()).isNull();
        assertThat(purchases.reasons()).contains("COMPLETE_HISTORY_NOT_PROVEN");
    }

    @Test void certifiedEmptyDepositDomainHasTrueZero() throws Exception {
        var result=money(List.of(),snapshot(List.of(),complete()),Kind.DEPOSIT,"USDT",false,false);
        assertThat(result.status()).isEqualTo(Status.AVAILABLE);assertThat(result.confirmedAmount()).isEqualByComparingTo("0");
        assertThat(result.confirmedEvents()).isZero();
    }

    @Test void realHistoryEnvironmentObservedAndMissingSourceGapsCannotBorrowRefundPolicy() throws Exception {
        var fact=fact(1,"USDT","10",Kind.DEPOSIT);
        for(String gap:List.of("HISTORY","ENVIRONMENT","OBSERVED","MISSING")) {
            var coverage=new ArrayList<>(complete());
            if(gap.equals("MISSING"))coverage.removeIf(c->c.source()==Source.HDPAY);
            else coverage.replaceAll(c->c.source()!=Source.CARD_TOPUP?c:new Coverage(c.source(),gap.equals("OBSERVED")?SupportPaymentFacts.Status.UNKNOWN:c.observedStatus(),
                gap.equals("HISTORY")?SupportPaymentFacts.Status.UNKNOWN:c.historyStatus(),c.refundStatus(),
                gap.equals("ENVIRONMENT")?SupportPaymentFacts.Status.UNKNOWN:c.historicalEnvironmentStatus(),c.supportedFrom(),List.of(gap),0,c.adapterVersion()));
            var result=money(List.of(fact),snapshot(List.of(fact),coverage),Kind.DEPOSIT,"USDT",false,false);
            assertThat(result.status()).as(gap).isEqualTo(Status.PARTIAL);assertThat(result.confirmedAmount()).isNull();
            assertThat(result.reasons()).doesNotContain("COMPLETE_REFUNDS_NOT_PROVEN");
        }
    }

    @Test void attributionAndReadingGapsStillBlockCertification() throws Exception {
        var fact=fact(1,"USDT","10",Kind.DEPOSIT);var snapshot=snapshot(List.of(fact),complete());
        var gap=money(List.of(fact),snapshot,Kind.DEPOSIT,"USDT",false,true);
        assertThat(gap.status()).isEqualTo(Status.PARTIAL);assertThat(gap.confirmedAmount()).isEqualByComparingTo("10");
        assertThat(gap.reasons()).contains("PERIOD_EVENT_COVERAGE_UNVERIFIED").doesNotContain("COMPLETE_REFUNDS_NOT_PROVEN");
        assertThat(money(List.of(),snapshot,Kind.DEPOSIT,"USDT",true,false).status()).isEqualTo(Status.FAILED);
        assertThat(money(List.of(),null,Kind.DEPOSIT,"USDT",false,false).status()).isEqualTo(Status.UNKNOWN);
    }

    @Test void unmatchedPaymentIssuesCannotCertifyZeroButPurchaseRefundGapStaysIndependent() throws Exception {
        for(String reason:List.of("MISSING_AUTHORITATIVE_SOURCE","SOURCE_READ_FAILED")) {
            var baseline=snapshot(List.of(),complete());
            var unresolved=baseline.customerCoverage().stream().map(c->new CustomerCoverage(c.customerId(),c.source(),SupportPaymentFacts.Status.UNKNOWN,
                c.refundStatus(),SupportPaymentFacts.Status.UNKNOWN,null,List.of(reason))).toList();
            var snapshot=new Snapshot(List.of(),List.of(new Issue(Source.UNMATCHED_LEDGER,"unmatched",reason,7L)),
                complete(),baseline.businessZone(),baseline.evaluatedAt(),List.of(),unresolved);
            var result=money(List.of(),snapshot,Kind.DEPOSIT,"USDT",false,false);
            assertThat(result.status()).as(reason).isEqualTo(Status.UNKNOWN);
            assertThat(result.confirmedAmount()).isNull();
        }
        var baseline=snapshot(List.of(),complete());
        var refunds=new Snapshot(List.of(),List.of(new Issue(Source.UNMATCHED_LEDGER,"refund",
            "MISSING_AUTHORITATIVE_REFUND_SOURCE",7L)),complete(),baseline.businessZone(),baseline.evaluatedAt(),List.of(),baseline.customerCoverage());
        var result=money(List.of(),refunds,Kind.DEPOSIT,"USDT",false,false);
        assertThat(result.status()).isEqualTo(Status.AVAILABLE);
        assertThat(result.confirmedAmount()).isEqualByComparingTo("0");
    }

    private static List<Coverage> complete() {
        return DEPOSITS.stream().map(s->new Coverage(s,SupportPaymentFacts.Status.READY,SupportPaymentFacts.Status.READY,
            SupportPaymentFacts.Status.UNKNOWN,SupportPaymentFacts.Status.READY,AT.minusDays(1),List.of(),0,"test-certified-scope")).toList();
    }
    private static Snapshot snapshot(List<Fact> facts,List<Coverage> coverage) {
        var scoped=coverage.stream().map(c->new CustomerCoverage(7,c.source(),c.observedStatus()==SupportPaymentFacts.Status.UNKNOWN?SupportPaymentFacts.Status.UNKNOWN:c.historyStatus(),
            c.refundStatus(),c.historicalEnvironmentStatus(),c.supportedFrom(),c.reasons())).toList();
        return new Snapshot(facts,List.of(),coverage,"Asia/Shanghai",Instant.parse("2026-10-10T04:00:00Z"),List.of(),scoped);
    }
    private static Fact fact(long ledger,String currency,String amount,Kind kind) {
        String order=kind==Kind.DEVICE_PURCHASE?"p"+ledger:null;
        return new Fact(kind==Kind.DEPOSIT?"DEPOSIT:"+ledger:"PURCHASE:"+order,kind,kind==Kind.DEPOSIT?Source.CARD_TOPUP:Source.WALLET_ORDER,
            List.of("source"+ledger),7,ledger,"source"+ledger,order,order==null?null:"SINGLE",null,currency,new BigDecimal(amount),AT,
            kind==Kind.DEPOSIT?"nx_wallet_ledger.created_at":"nx_order.paid_at",kind==Kind.DEPOSIT?0:6,null,AT,null,"v1",SupportPaymentFacts.Status.UNKNOWN);
    }
    private static Money money(List<Fact> selected,Snapshot snapshot,Kind kind,String currency,boolean unavailable,boolean periodGap) throws Exception {
        var method=SupportAnalyticsService.class.getDeclaredMethod("money",List.class,Snapshot.class,Kind.class,String.class,boolean.class,boolean.class);
        method.setAccessible(true);return (Money)method.invoke(null,selected,snapshot,kind,currency,unavailable,periodGap);
    }
}
