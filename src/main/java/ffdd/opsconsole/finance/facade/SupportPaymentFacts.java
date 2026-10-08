package ffdd.opsconsole.finance.facade;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** Source facts only. No customer ownership, first-event selection, totals or ranks. */
public final class SupportPaymentFacts {
    private SupportPaymentFacts() {}
    public enum Kind { DEPOSIT, DEVICE_PURCHASE, DEVICE_PURCHASE_REFUND }
    public enum Source { DEPOSIT_ORDER, CARD_TOPUP, VIETQR, HDPAY, WALLET_ORDER, TRADE_IN,
        CAPACITY_KEEP, TRIAL_CONVERT, ORDER_REFUND, FREE_TRIAL, UNMATCHED_LEDGER }
    public enum Status { READY, UNKNOWN }

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
