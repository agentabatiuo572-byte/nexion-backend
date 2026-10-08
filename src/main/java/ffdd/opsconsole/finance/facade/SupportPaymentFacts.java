package ffdd.opsconsole.finance.facade;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/** Source facts only. No customer ownership, first-event selection, totals or ranks. */
public final class SupportPaymentFacts {
    private SupportPaymentFacts() {}
    public enum Kind { DEPOSIT, DEVICE_PURCHASE, DEVICE_PURCHASE_REFUND }
    public enum Source { DEPOSIT_ORDER, CARD_TOPUP, VIETQR, HDPAY, WALLET_ORDER, TRADE_IN,
        CAPACITY_KEEP, TRIAL_CONVERT, ORDER_REFUND, FREE_TRIAL, UNMATCHED_LEDGER }
    public enum Status { READY, UNKNOWN }

    /** Pure financial identity checks; callers retain customer/source and transaction authorization. */
    public static String validateCanonical(Fact fact,String businessZone) {
        final String invalid="SUPPORT_PAYMENT_FACT_INVALID";
        if(fact==null || fact.kind()==null || fact.source()==null || fact.customerId()<=0 || fact.ledgerId()<=0
            || !present(fact.factId()) || !present(fact.sourceBusinessId()) || !present(fact.currency())
            || fact.amount()==null || fact.amount().signum()<=0 || fact.amount().stripTrailingZeros().scale()>6
            || fact.amount().precision()-fact.amount().scale()>12 || fact.succeededAt()==null
            || !present(fact.successTimeField()) || fact.fractionalSecondDigits()<0 || fact.fractionalSecondDigits()>6
            || !present(businessZone))return invalid;
        ZoneId.of(businessZone);
        int quantum=1;for(int i=fact.fractionalSecondDigits();i<9;i++)quantum*=10;
        if(fact.succeededAt().getNano()%quantum!=0)return invalid;
        String canonical=switch(fact.kind()) {
            case DEPOSIT -> "DEPOSIT:"+fact.ledgerId();
            case DEVICE_PURCHASE -> "PURCHASE:"+fact.orderNo();
            case DEVICE_PURCHASE_REFUND -> "ORDER_REFUND:"+fact.ledgerId();
        };
        Kind expected=switch(fact.source()) {
            case DEPOSIT_ORDER,CARD_TOPUP,VIETQR,HDPAY -> Kind.DEPOSIT;
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,TRIAL_CONVERT -> Kind.DEVICE_PURCHASE;
            case ORDER_REFUND -> Kind.DEVICE_PURCHASE_REFUND;
            case FREE_TRIAL,UNMATCHED_LEDGER -> null;
        };
        if(expected!=fact.kind() || !canonical.equals(fact.factId()) || (fact.kind()!=Kind.DEPOSIT && !present(fact.orderNo()))
            || (fact.kind()==Kind.DEVICE_PURCHASE_REFUND
                ? !Objects.equals(fact.originalFactId(),"PURCHASE:"+fact.orderNo()) : fact.originalFactId()!=null))return invalid;
        return null;
    }
    private static boolean present(String value) {return value!=null && !value.isBlank();}

    /** SQL DATETIME values remain in the business zone; precision is not invented. */
    public record Fact(String factId, Kind kind, Source source, List<String> sourceIds,
        long customerId, long ledgerId, String sourceBusinessId, String orderNo, String orderType,
        String originalFactId, String currency, BigDecimal amount, LocalDateTime succeededAt,
        String successTimeField, int fractionalSecondDigits, LocalDateTime providerPaidAt,
        LocalDateTime ledgerRecordedAt, LocalDateTime sourceConfirmationAt,
        String sourceVersion, Status historicalEnvironmentStatus) {
        public Fact { sourceIds = List.copyOf(sourceIds); }
    }
    public record Issue(Source source, String sourceId, String reason) {}
    /** READY means the observed rows were reconciled, never complete lifetime/refund coverage. */
    public record Coverage(Source source, Status observedStatus, Status historyStatus,
        Status refundStatus, Status historicalEnvironmentStatus, LocalDateTime supportedFrom,
        List<String> reasons, long excludedFreeOrNonProductionRows, String adapterVersion) {
        public Coverage { reasons = List.copyOf(reasons); }
    }
    public record Snapshot(List<Fact> facts, List<Issue> issues, List<Coverage> coverage,
        String businessZone, Instant evaluatedAt) {
        public Snapshot {
            facts = List.copyOf(facts); issues = List.copyOf(issues); coverage = List.copyOf(coverage);
        }
    }
}
