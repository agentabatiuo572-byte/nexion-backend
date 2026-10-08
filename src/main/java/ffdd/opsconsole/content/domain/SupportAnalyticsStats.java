package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;

/** Observed internal read model, not a reusable paging snapshot or a complete-history certificate. */
public final class SupportAnalyticsStats {
    private SupportAnalyticsStats() { }
    public enum Basis { CURRENT_ASSET, CURRENT_CUSTOMER_HISTORY, PERIOD_EVENT }
    public enum Status { AVAILABLE, PARTIAL, UNKNOWN, FAILED, UNAVAILABLE }
    public enum Category { BOUND, PENDING, ANOMALY }
    public enum Placement { GROUPED, UNGROUPED, GROUP_QUEUE, GLOBAL_QUEUE, UNKNOWN }
    public enum AttributionStatus { KNOWN, UNASSIGNED, UNKNOWN }

    public record Query(ReadMode mode,Long groupId,Long agentId,Basis basis,
                        LocalDateTime fromInclusive,LocalDateTime toExclusive,String businessZone,String currency) {
        public Query {
            if(basis==null || businessZone==null || businessZone.isBlank()
                    || groupId!=null && groupId<=0 || agentId!=null && agentId<=0)
                throw new IllegalArgumentException("SUPPORT_ANALYTICS_QUERY_INVALID");
            try { ZoneId.of(businessZone); }
            catch(RuntimeException ex) { throw new IllegalArgumentException("SUPPORT_ANALYTICS_QUERY_INVALID"); }
            if(basis==Basis.PERIOD_EVENT ? fromInclusive==null || toExclusive==null || !fromInclusive.isBefore(toExclusive)
                    : fromInclusive!=null || toExclusive!=null)
                throw new IllegalArgumentException("SUPPORT_ANALYTICS_WINDOW_INVALID");
            if(basis==Basis.PERIOD_EVENT && (ZoneId.of(businessZone).getRules().getValidOffsets(fromInclusive).size()!=1
                    || ZoneId.of(businessZone).getRules().getValidOffsets(toExclusive).size()!=1))
                throw new IllegalArgumentException("SUPPORT_ANALYTICS_WINDOW_INVALID");
            if(currency!=null && !currency.matches("[A-Z][A-Z0-9]{1,15}"))
                throw new IllegalArgumentException("SUPPORT_ANALYTICS_CURRENCY_INVALID");
        }
    }
    public record Count(Long observedValue,Long confirmedValue,Status status) { }
    public record PlacementCount(Placement placement,long customers) { }
    public record CurrentScope(ReadMode mode,Long groupId,Long agentId,Status status,Long total,
                               Long bound,Long pending,Long anomaly,List<PlacementCount> placements) {
        public CurrentScope { placements=List.copyOf(placements); }
    }
    public record Attribution(AttributionStatus agent,AttributionStatus group,AttributionStatus owner) { }
    public record FirstCandidate(String kind,String source,LocalDateTime succeededAt,int fractionalSecondDigits,
                                 BigDecimal amount,String currency,Attribution attribution) { }
    public record FirstSelection(FirstCandidate observedCandidate,Status status,List<String> reasons) {
        public FirstSelection { reasons=List.copyOf(reasons); }
    }
    /** Current identities only; first is a whole-history profile, independent of the period/currency summary. */
    public record Customer(long customerId,Category category,Placement placement,boolean handoverRequired,
                           FirstSelection first) { }
    public record Money(BigDecimal observedAmount,BigDecimal confirmedAmount,Long observedEvents,
                        Long confirmedEvents,Long observedCustomers,Status status,List<String> reasons) {
        public Money { reasons=List.copyOf(reasons); }
    }
    public record CurrencyTotals(String currency,Money deposits,Money purchases,Money purchaseRefunds,Money net) { }
    public record AttributionPartition(String layer,AttributionStatus status,long observedEvents) { }
    public record FinancialSummary(Status status,List<CurrencyTotals> currencies,Count firstCandidates,
                                   List<AttributionPartition> attribution,List<String> reasons) {
        public FinancialSummary {
            currencies=List.copyOf(currencies);attribution=List.copyOf(attribution);reasons=List.copyOf(reasons);
        }
    }
    public record SourceCoverage(String source,String observedStatus,String historyStatus,String refundStatus,
                                 String historicalEnvironmentStatus,LocalDateTime supportedFrom,String adapterVersion) { }
    /** Historical transfer information is aggregate only, with no customer, event or order identifiers. */
    public record RestrictedSummary(Count customers,Count firstCandidates) { }
    public record Result(Query query,CurrentScope currentScope,List<Customer> currentCustomers,
                         FinancialSummary financialSummary,RestrictedSummary restrictedSummary,
                         List<SourceCoverage> coverage,Instant asOf,List<String> reasons) {
        public Result { currentCustomers=List.copyOf(currentCustomers);coverage=List.copyOf(coverage);reasons=List.copyOf(reasons); }
    }
}
