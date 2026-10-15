package ffdd.opsconsole.finance.application;

import static ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.*;

/** Declarative c81 coverage expectations for the batch fixture, independent of source-service output.
 * The fixture has customer 7's proven UTC birth, no production payment audits, no refund origins,
 * no excluded rows, and no free-trial/unmatched rows. Each scenario selects explicit exceptions.
 */
final class SupportPaymentBatchCoverageExpectations {
    static final String ENVIRONMENT = "HISTORICAL_ENVIRONMENT_NOT_RECORDED";
    static final String LIFETIME = "LIFETIME_HISTORY_COVERAGE_NOT_ATTESTED";
    static final String REFUND_HISTORY = "REFUND_HISTORY_COVERAGE_NOT_ATTESTED";
    static final String DEPOSIT_FINAL = "SUCCESSFUL_DEPOSITS_NON_REFUNDABLE";
    static final String PAYMENT_UNPROVEN = "PAYMENT_NEW_SUCCESS_NOT_PROVEN";
    static final String REFUND_ORIGIN = "BACKEND_SUCCESSFUL_ORIGINAL_REFUND_NOT_PROVEN";
    // Explicit business-zone conversion of 2026-10-07T12:00 UTC; no actual snapshot input.
    static final LocalDateTime BIRTH = LocalDateTime.of(2026,10,7,20,0);
    private static final List<Source> SOURCES = List.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.VIETQR,
        Source.HDPAY,Source.WALLET_ORDER,Source.TRADE_IN,Source.CAPACITY_KEEP,Source.TRIAL_CONVERT,
        Source.ORDER_REFUND,Source.GENESIS_PRIMARY,Source.GENESIS_SECONDARY,Source.FREE_TRIAL,Source.UNMATCHED_LEDGER);
    private static final List<Source> CUSTOMER_SOURCES = SOURCES.subList(0,11);
    private static final Set<Source> DEPOSITS = EnumSet.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.VIETQR,Source.HDPAY);
    private final Map<Source,Coverage> coverage = new EnumMap<>(Source.class);
    private final Map<Source,CustomerCoverage> customers = new EnumMap<>(Source.class);
    private List<FirstHistory> first = List.of(new FirstHistory(7L,Status.READY,List.of()));

    static SupportPaymentBatchCoverageExpectations empty() { return new SupportPaymentBatchCoverageExpectations(); }

    private SupportPaymentBatchCoverageExpectations() {
        for(Source source:CUSTOMER_SOURCES) {
            customers.put(source,new CustomerCoverage(7L,source,Status.READY,Status.READY,Status.READY,BIRTH,List.of()));
            coverage.put(source,new Coverage(source,Status.READY,Status.READY,Status.READY,Status.READY,BIRTH,
                DEPOSITS.contains(source)?List.of(DEPOSIT_FINAL):List.of(),0,"support-payment-facts-v1"));
        }
        for(Source source:List.of(Source.FREE_TRIAL,Source.UNMATCHED_LEDGER))
            coverage.put(source,new Coverage(source,Status.READY,Status.UNKNOWN,Status.UNKNOWN,Status.UNKNOWN,null,
                reasons(ENVIRONMENT,LIFETIME,REFUND_HISTORY),0,"support-payment-facts-v1"));
    }

    /** A real capture reconciles history; this fixture has no independent production success audit. */
    SupportPaymentBatchCoverageExpectations paid(Source source) {
        customers.put(source,new CustomerCoverage(7L,source,Status.READY,Status.READY,Status.UNKNOWN,BIRTH,List.of(ENVIRONMENT)));
        coverage.put(source,new Coverage(source,Status.READY,Status.READY,Status.READY,Status.UNKNOWN,BIRTH,
            DEPOSITS.contains(source)?reasons(ENVIRONMENT,DEPOSIT_FINAL):List.of(ENVIRONMENT),0,"support-payment-facts-v1"));
        return this;
    }

    /** Scenarios explicitly distinguish quarantined contradictions from retained ordinary payment rows. */
    SupportPaymentBatchCoverageExpectations rejected(Source source,String issue,boolean ordinaryRetained) {
        List<String> local=ordinaryRetained?reasons(ENVIRONMENT,issue,PAYMENT_UNPROVEN):reasons(ENVIRONMENT,issue);
        customers.put(source,new CustomerCoverage(7L,source,Status.UNKNOWN,Status.READY,Status.UNKNOWN,null,local));
        coverage.put(source,new Coverage(source,Status.UNKNOWN,Status.UNKNOWN,Status.READY,Status.UNKNOWN,null,
            union(local,DEPOSITS.contains(source)?reasons(LIFETIME,DEPOSIT_FINAL):List.of(LIFETIME)),0,"support-payment-facts-v1"));
        return this;
    }

    /** Explicit refund fixture outcomes propagate refund uncertainty only to non-deposit sources.
     * Other history/environment fields remain those declared by the scenario's payment outcome.
     */
    SupportPaymentBatchCoverageExpectations refundUnknown(Status observed,String... refundReasons) {
        List<String> failures=reasons(refundReasons);
        for(Source source:List.of(Source.WALLET_ORDER,Source.TRADE_IN,Source.CAPACITY_KEEP,Source.TRIAL_CONVERT,
                Source.GENESIS_PRIMARY,Source.GENESIS_SECONDARY)) {
            var customer=customers.get(source);var global=coverage.get(source);
            customers.put(source,new CustomerCoverage(7L,source,customer.historyStatus(),Status.UNKNOWN,
                customer.historicalEnvironmentStatus(),customer.supportedFrom(),union(customer.reasons(),failures)));
            coverage.put(source,new Coverage(source,global.observedStatus(),global.historyStatus(),Status.UNKNOWN,
                global.historicalEnvironmentStatus(),global.supportedFrom(),union(global.reasons(),union(failures,List.of(REFUND_HISTORY))),
                0,"support-payment-facts-v1"));
        }
        customers.put(Source.ORDER_REFUND,new CustomerCoverage(7L,Source.ORDER_REFUND,Status.UNKNOWN,Status.UNKNOWN,
            Status.UNKNOWN,null,union(failures,List.of(ENVIRONMENT))));
        coverage.put(Source.ORDER_REFUND,new Coverage(Source.ORDER_REFUND,observed,Status.UNKNOWN,Status.UNKNOWN,
            Status.UNKNOWN,null,union(failures,reasons(ENVIRONMENT,LIFETIME,REFUND_HISTORY)),0,"support-payment-facts-v1"));
        return this;
    }

    SupportPaymentBatchCoverageExpectations firstUnknown(String... failures) {
        first=List.of(new FirstHistory(7L,Status.UNKNOWN,reasons(failures)));return this;
    }

    void assertMatches(Snapshot actual) {
        assertEquals(SOURCES.stream().map(coverage::get).toList(),actual.coverage(),"all 13 ordered source rows and every coverage field");
        assertEquals(CUSTOMER_SOURCES.stream().map(customers::get).toList(),actual.customerCoverage(),"all 11 ordered customer/source rows and every scoped coverage field");
        assertEquals(first,actual.firstHistory(),"exact first-payment customer, status and reason list");
    }

    private static List<String> reasons(String... values) {return List.copyOf(new TreeSet<>(Arrays.asList(values)));}
    private static List<String> union(List<String> left,List<String> right) {
        var values=new TreeSet<>(left);values.addAll(right);return List.copyOf(values);
    }
}
