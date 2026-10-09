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
    public enum FirstState { CONFIRMED, NONE, UNKNOWN }
    public enum Acquisition { PAID_PURCHASE, UNKNOWN }
    public enum WindowState { ACTIVE, INACTIVE, UNKNOWN }
    public enum AccountState { ENABLED, DISABLED, UNKNOWN }
    public enum QualificationState { ENABLED, DISABLED, REMOVED, UNKNOWN }
    public enum MemberState { GROUPED, UNGROUPED, UNKNOWN }

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
    public record FirstSelection(FirstCandidate observedCandidate,Status status,List<String> reasons,FirstState state) {
        public FirstSelection(FirstCandidate observedCandidate,Status status,List<String> reasons) {
            this(observedCandidate,status,reasons,FirstState.UNKNOWN);
        }
        public FirstSelection { reasons=List.copyOf(reasons); }
    }
    /** Current identities only; first is a whole-history profile, independent of the period/currency summary. */
    public record Customer(long customerId,Category category,Placement placement,boolean handoverRequired,
                           FirstSelection first,CurrentOwner owner,CustomerMetrics metrics) {
        public Customer(long customerId,Category category,Placement placement,boolean handoverRequired,FirstSelection first) {
            this(customerId,category,placement,handoverRequired,first,new CurrentOwner(null,null),unavailableCustomerMetrics());
        }
    }
    public record CurrentOwner(Long agentId,Long groupId) { }
    public record Money(BigDecimal observedAmount,BigDecimal confirmedAmount,Long observedEvents,
                        Long confirmedEvents,Long observedCustomers,Status status,List<String> reasons) {
        public Money { reasons=List.copyOf(reasons); }
    }
    public record CurrencyTotals(String currency,Money deposits,Money purchases,Money purchaseRefunds,Money net) { }
    public record FirstCurrencyTotals(String currency,Money deposits,Money purchases) { }
    public record AttributionPartition(String layer,AttributionStatus status,long observedEvents) { }
    public record FinancialSummary(Status status,List<CurrencyTotals> currencies,Count firstCandidates,
                                   List<AttributionPartition> attribution,List<String> reasons,List<FirstCurrencyTotals> firstSources) {
        public FinancialSummary(Status status,List<CurrencyTotals> currencies,Count firstCandidates,
                List<AttributionPartition> attribution,List<String> reasons) {
            this(status,currencies,firstCandidates,attribution,reasons,List.of());
        }
        public FinancialSummary {
            currencies=List.copyOf(currencies);attribution=List.copyOf(attribution);reasons=List.copyOf(reasons);
            firstSources=List.copyOf(firstSources);
        }
    }
    public record SourceCoverage(String source,String observedStatus,String historyStatus,String refundStatus,
                                 String historicalEnvironmentStatus,LocalDateTime supportedFrom,String adapterVersion) { }
    /** Historical transfer information is aggregate only, with no customer, event or order identifiers. */
    public record RestrictedSummary(Count customers,Count firstCandidates) { }
    public record CustomerCurrencyTotals(String currency,Money deposits,Money purchases) { }
    public record InvitationCurrencyTotal(String currency,Money deposits) { }
    /** Descendant identities and per-descendant facts never belong in this projection. */
    public record InvitationSummary(Count directCustomers,Count descendantCustomers,
            List<InvitationCurrencyTotal> descendantDeposits,Status status,List<String> reasons) {
        public InvitationSummary {descendantDeposits=List.copyOf(descendantDeposits);reasons=List.copyOf(reasons);}
    }
    public record DevicePartition(String dimension,String value,Count devices) { }
    public record DeviceSummary(Count held,Count unknownHolding,List<DevicePartition> partitions,
            LocalDateTime evaluatedDbAt,Status status,List<String> reasons) {
        public DeviceSummary {partitions=List.copyOf(partitions);reasons=List.copyOf(reasons);}
    }
    public record ActivityWindow(Integer days,LocalDateTime fromInclusive,LocalDateTime throughInclusive,
            LocalDateTime coverageStartAt,Long rulesVersion,String source,Status status) { }
    public record CustomerActivity(LocalDateTime lastEffectiveAt,WindowState state,Status status,List<String> reasons) {
        public CustomerActivity {reasons=List.copyOf(reasons);}
    }
    public record ActivityPartition(Category category,Count active,Count inactive,Count unknown) { }
    public record ActivitySummary(Count active,Count inactive,Count unknown,ActivityWindow window,
            Status status,List<String> reasons,List<ActivityPartition> partitions) {
        public ActivitySummary(Count active,Count inactive,Count unknown,ActivityWindow window,Status status,List<String> reasons) {
            this(active,inactive,unknown,window,status,reasons,List.of());
        }
        public ActivitySummary {reasons=List.copyOf(reasons);partitions=List.copyOf(partitions);}
    }
    public record CustomerMetrics(Basis lifetimeBasis,Status lifetimeStatus,List<CustomerCurrencyTotals> lifetime,
            InvitationSummary invitations,DeviceSummary devices,CustomerActivity activity) {
        public CustomerMetrics {lifetime=List.copyOf(lifetime);}
    }
    /** Category, account activation, reception qualification and membership are separate facts. */
    public record AccountRow(long accountId,boolean serviceAccount,boolean supervisorAccount,Status serviceCategoryStatus,Status supervisorCategoryStatus,AccountState accountState,
            QualificationState serviceQualification,QualificationState supervisorQualification,AccountState receptionState,
            MemberState memberState,Long groupId,boolean handoverRequired,Status status) { }
    public record PersonnelPartition(String dimension,String value,Count accounts) { }
    public record PersonnelSummary(List<AccountRow> accounts,Count serviceAccounts,Count groupMembers,
            Count supervisors,Count people,Count groups,List<PersonnelPartition> partitions,Status status,List<String> reasons) {
        public PersonnelSummary {accounts=List.copyOf(accounts);partitions=List.copyOf(partitions);reasons=List.copyOf(reasons);}
    }
    public record CurrentMetrics(Basis lifetimeBasis,Status status,List<CustomerCurrencyTotals> ownLifetime,
            Count firstConfirmed,Count firstNone,Count firstUnknown,DeviceSummary devices,ActivitySummary activity) {
        public CurrentMetrics {ownLifetime=List.copyOf(ownLifetime);}
    }
    public record GroupAggregate(long groupId,CurrentScope customers,CurrentMetrics current,
            FinancialSummary period,PersonnelSummary personnel) { }
    public record Result(Query query,CurrentScope currentScope,List<Customer> currentCustomers,
                         FinancialSummary financialSummary,RestrictedSummary restrictedSummary,
                         List<SourceCoverage> coverage,Instant asOf,List<String> reasons,CurrentMetrics currentMetrics,
                         PersonnelSummary personnel,List<GroupAggregate> groups) {
        public Result(Query query,CurrentScope currentScope,List<Customer> currentCustomers,FinancialSummary financialSummary,
                RestrictedSummary restrictedSummary,List<SourceCoverage> coverage,Instant asOf,List<String> reasons) {
            this(query,currentScope,currentCustomers,financialSummary,restrictedSummary,coverage,asOf,reasons,
                unavailableCurrentMetrics(),unavailablePersonnel(),List.of());
        }
        public Result { currentCustomers=List.copyOf(currentCustomers);coverage=List.copyOf(coverage);reasons=List.copyOf(reasons);groups=List.copyOf(groups); }
    }
    public static Count unavailableCount() {return new Count(null,null,Status.UNAVAILABLE);}
    public static DeviceSummary unavailableDevices() {return new DeviceSummary(unavailableCount(),unavailableCount(),List.of(),null,Status.UNAVAILABLE,List.of("CURRENT_DEVICE_NOT_REQUESTED"));}
    public static ActivityWindow unavailableWindow() {return new ActivityWindow(null,null,null,null,null,"INTERACTIVE_LOGIN",Status.UNAVAILABLE);}
    public static CustomerActivity unavailableActivity() {return new CustomerActivity(null,WindowState.UNKNOWN,Status.UNAVAILABLE,List.of("ACTIVITY_NOT_REQUESTED"));}
    public static CustomerMetrics unavailableCustomerMetrics() {
        return new CustomerMetrics(Basis.CURRENT_CUSTOMER_HISTORY,Status.UNAVAILABLE,List.of(),
            new InvitationSummary(unavailableCount(),unavailableCount(),List.of(),Status.UNAVAILABLE,List.of("INVITATION_NOT_REQUESTED")),unavailableDevices(),unavailableActivity());
    }
    public static CurrentMetrics unavailableCurrentMetrics() {
        return new CurrentMetrics(Basis.CURRENT_CUSTOMER_HISTORY,Status.UNAVAILABLE,List.of(),unavailableCount(),unavailableCount(),unavailableCount(),unavailableDevices(),
            new ActivitySummary(unavailableCount(),unavailableCount(),unavailableCount(),unavailableWindow(),Status.UNAVAILABLE,List.of("ACTIVITY_NOT_REQUESTED")));
    }
    public static PersonnelSummary unavailablePersonnel() {
        return new PersonnelSummary(List.of(),unavailableCount(),unavailableCount(),unavailableCount(),unavailableCount(),unavailableCount(),List.of(),Status.UNAVAILABLE,List.of("PERSONNEL_NOT_AUTHORIZED_OR_REQUESTED"));
    }
}
