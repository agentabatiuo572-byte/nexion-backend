package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper;
import ffdd.opsconsole.market.facade.SupportGenesisReadFacade;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;

/** Internal source adapter. A caller must authorize the requested customer IDs. */
@ApplicationService
public class SupportPaymentFactService {
    public static final String ADAPTER_VERSION = "support-payment-facts-v1";
    private final SupportPaymentFactMapper mapper;
    private final SupportGenesisReadFacade genesis;
    @org.springframework.beans.factory.annotation.Autowired
    public SupportPaymentFactService(SupportPaymentFactMapper mapper,SupportGenesisReadFacade genesis) {this.mapper=mapper;this.genesis=genesis;}
    public SupportPaymentFactService(SupportPaymentFactMapper mapper) {this(mapper,null);}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot read(Collection<Long> customerIds) {
        return readWithCapturedHistory(customerIds,new CapturedFinancialHistory(List.of(),List.of(),Set.of()));
    }
    record VerifiedCandidate(Fact fact,String logicalPaymentKey) {
        VerifiedCandidate {
            Objects.requireNonNull(fact);
            if(logicalPaymentKey==null || logicalPaymentKey.isBlank())throw new IllegalArgumentException("Explicit logical payment key required");
        }
    }
    record CapturedFinancialHistory(List<VerifiedCandidate> candidates,List<Issue> issues,Set<String> conflictingFactIds,
            Map<Long,List<String>> registrationProblems,Map<Long,LocalDateTime> productionBirths,
            Set<String> historicalProductionFacts,List<Map<String,Object>> refundOrigins) {
        CapturedFinancialHistory(List<VerifiedCandidate> candidates,List<Issue> issues,Set<String> conflictingFactIds) {
            this(candidates,issues,conflictingFactIds,Map.of(),Map.of(),Set.of(),List.of());
        }
        CapturedFinancialHistory(List<VerifiedCandidate> candidates,List<Issue> issues,Set<String> conflictingFactIds,
                Map<Long,List<String>> registrationProblems) {
            this(candidates,issues,conflictingFactIds,registrationProblems,Map.of(),Set.of(),List.of());
        }
        CapturedFinancialHistory {
            candidates=List.copyOf(candidates);issues=List.copyOf(issues);conflictingFactIds=Set.copyOf(conflictingFactIds);
            var copy=new TreeMap<Long,List<String>>();
            registrationProblems.forEach((id,reasons) -> copy.put(id,List.copyOf(reasons)));
            registrationProblems=Collections.unmodifiableMap(copy);
            productionBirths=Map.copyOf(productionBirths);historicalProductionFacts=Set.copyOf(historicalProductionFacts);
            refundOrigins=refundOrigins.stream().map(row -> Collections.unmodifiableMap(new HashMap<>(row))).toList();
        }
    }
    // The source facade supplies verified financial data inside its existing RR transaction.
    Snapshot readWithCapturedHistory(Collection<Long> customerIds,CapturedFinancialHistory captured) {
        if (customerIds == null || customerIds.isEmpty() || customerIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("Explicit positive customer scope required");
        var ids = List.copyOf(new TreeSet<>(customerIds));
        var rows = new ArrayList<Map<String,Object>>();
        var issues = new ArrayList<Issue>(captured.issues());
        var excluded = new EnumMap<Source,Long>(Source.class);
        var excludedCustomers=new HashSet<String>();
        var unmatchedKinds=new HashMap<String,String>();
        load(rows, issues, () -> mapper.deposits(ids), Source.DEPOSIT_ORDER);
        load(rows, issues, () -> mapper.cards(ids), Source.CARD_TOPUP);
        load(rows, issues, () -> mapper.vietqr(ids), Source.VIETQR);
        load(rows, issues, () -> mapper.hdpay(ids), Source.HDPAY);
        load(rows, issues, () -> mapper.orders(ids), Source.WALLET_ORDER, Source.TRADE_IN, Source.CAPACITY_KEEP);
        load(rows, issues, () -> mapper.trials(ids), Source.TRIAL_CONVERT);
        load(rows, issues, () -> mapper.refunds(ids), Source.ORDER_REFUND);
        load(rows, issues, () -> mapper.freeTrials(ids), Source.FREE_TRIAL);
        load(rows, issues, () -> mapper.unmatched(ids), Source.UNMATCHED_LEDGER);
        load(rows,issues,()->{
            if(genesis==null)throw new org.springframework.dao.DataRetrievalFailureException("GENESIS_SOURCE_UNAVAILABLE");
            return genesis.readByCustomers(ids).stream().flatMap(s->SupportPaymentSourceService.genesisRows(s).stream())
                .filter(r->ids.contains(number(r,"customerId"))).toList();
        },Source.GENESIS_PRIMARY,Source.GENESIS_SECONDARY);

        var facts = new LinkedHashMap<String,Fact>();
        var rejected = new HashSet<String>(captured.conflictingFactIds());
        var logicalPayments = new HashMap<String,String>();
        var verified=new HashMap<String,Fact>();
        for(var candidate:captured.candidates()) {
            Fact fact=candidate.fact();
            if(!ids.contains(fact.customerId()))throw new IllegalArgumentException("Captured customer outside explicit scope");
            if(capturedContradiction(captured.issues(),fact.customerId(),fact.source(),fact.factId()))continue;
            Fact prior=verified.putIfAbsent(fact.factId(),fact);
            if(prior!=null && !sameCapturedProjection(prior,fact)) {
                rejected.add(fact.factId());issues.add(new Issue(fact.source(),fact.sourceIds().get(0),"CONFLICTING_CAPTURED_SOURCE_PROJECTION",fact.customerId()));
            }
            accept(fact,candidate.logicalPaymentKey(),facts,rejected,logicalPayments,issues);
        }
        for (var row : rows) {
            if(row==null) {issues.add(new Issue(null,null,"INVALID_SOURCE_ROW"));continue;}
            long customer=number(row,"customerId");
            if(customer>0 && !ids.contains(customer))throw new IllegalArgumentException("Source customer outside explicit scope");
            Source source;
            try { source=Source.valueOf(text(row,"source")); }
            catch(IllegalArgumentException | NullPointerException ex) {
                issues.add(new Issue(null,text(row,"sourceId"),"UNKNOWN_FINANCIAL_SOURCE",customer>0?customer:null));continue;
            }
            String sourceId = text(row,"sourceId");
            if(source==Source.UNMATCHED_LEDGER)continue;
            if (source == Source.FREE_TRIAL || number(row,"excludedEnvironment") == 1
                    || (Kind.DEVICE_PURCHASE.name().equals(text(row,"kind")) && decimal(row,"amount") != null
                        && decimal(row,"amount").signum() == 0
                        && (decimal(row,"ledgerAmount") == null || decimal(row,"ledgerAmount").signum() == 0))) {
                if(source!=Source.FREE_TRIAL && number(row,"excludedEnvironment")==1)
                    excludedCustomers.add(customer+":"+source);
                excluded.merge(source,1L,Long::sum); continue;
            }
            Kind kind;
            try { kind=Kind.valueOf(text(row,"kind")); }
            catch(IllegalArgumentException | NullPointerException ex) {
                issues.add(new Issue(source,sourceId,"UNKNOWN_FINANCIAL_KIND",customer>0?customer:null));continue;
            }
            Kind expected=switch(source) {
                case DEPOSIT_ORDER,CARD_TOPUP,VIETQR,HDPAY -> Kind.DEPOSIT;
                case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,TRIAL_CONVERT -> Kind.DEVICE_PURCHASE;
                case ORDER_REFUND -> Kind.DEVICE_PURCHASE_REFUND;
                case GENESIS_PRIMARY,GENESIS_SECONDARY -> Kind.CONSUMPTION;
                default -> null;
            };
            if(kind!=expected) {
                issues.add(new Issue(source,sourceId,"UNKNOWN_FINANCIAL_KIND",customer>0?customer:null));continue;
            }
            String id=kind==Kind.DEPOSIT ? "DEPOSIT:"+number(row,"ledgerId") : kind==Kind.DEVICE_PURCHASE
                ? "PURCHASE:"+text(row,"orderNo") : kind==Kind.CONSUMPTION?"CONSUMPTION:"+text(row,"orderNo"):"ORDER_REFUND:"+number(row,"ledgerId");
            // Quarantine only the real row's customer/source/canonical, never a foreign claimed ID globally.
            if(capturedContradiction(captured.issues(),customer,source,id))continue;
            Fact saved=verified.get(id);
            // The actual source customer owns a malformed row, never the customer behind its claimed ledger/order.
            if(saved!=null && saved.customerId()!=customer) {
                issues.add(new Issue(source,sourceId,"CANONICAL_CUSTOMER_MISMATCH",customer>0?customer:null));continue;
            }
            String problem = invalid(row, source);
            if(saved!=null && "CONFLICTING_SUCCESS_TIME".equals(problem)
                && invalidNewSource(row,source)==null && sameCapturedProjection(saved,fact(row,source)))problem=null;
            if (problem != null) {
                issues.add(new Issue(source,sourceId,problem,customer>0?customer:null));
                if(saved!=null)rejected.add(id);
                continue;
            }
            var fact = fact(row,source);
            if(saved!=null && !sameCapturedProjection(saved,fact)) {
                rejected.add(id);issues.add(new Issue(source,sourceId,"CONFLICTING_CAPTURED_SOURCE_PROJECTION",customer));
                continue;
            }
            accept(fact,text(row,"duplicateKey"),facts,rejected,logicalPayments,issues);
        }
        // A legacy conversion backfill can map the same charge to multiple orders.
        var ledgerOwners = new HashMap<String,String>();
        for (Fact fact : facts.values()) {
            String ledgerKey = fact.kind()+":"+fact.ledgerId();
            String previous = ledgerOwners.putIfAbsent(ledgerKey,fact.factId());
            if (previous != null && !previous.equals(fact.factId())) {
                rejected.add(previous); rejected.add(fact.factId());
                issues.add(new Issue(fact.source(),fact.sourceIds().get(0),"DUPLICATE_SETTLEMENT_LEDGER",fact.customerId()));
            }
        }
        rejected.forEach(facts::remove);
        var refundTotals = new HashMap<String,BigDecimal>();
        var invalidRefunds = new HashSet<String>();
        for (Fact refund : facts.values()) {
            if (refund.kind() != Kind.DEVICE_PURCHASE_REFUND) continue;
            Fact original = facts.get(refund.originalFactId());
            if (original == null || original.kind() != Kind.DEVICE_PURCHASE
                    || original.customerId() != refund.customerId() || !original.currency().equals(refund.currency())) {
                invalidRefunds.add(refund.factId());
                issues.add(new Issue(refund.source(),refund.sourceIds().get(0),"UNPROVEN_ORIGINAL_PAYMENT",refund.customerId()));
            } else if (refund.succeededAt().withNano(0).isBefore(original.succeededAt().withNano(0))) {
                invalidRefunds.add(refund.factId());
                issues.add(new Issue(refund.source(),refund.sourceIds().get(0),"REFUND_PREDATES_ORIGINAL_PAYMENT",refund.customerId()));
            } else refundTotals.merge(original.factId(),refund.amount(),BigDecimal::add);
        }
        for (Fact refund : facts.values()) {
            BigDecimal total = refundTotals.get(refund.originalFactId());
            Fact original = facts.get(refund.originalFactId());
            if (refund.kind() == Kind.DEVICE_PURCHASE_REFUND && total != null && original != null
                    && total.compareTo(original.amount()) > 0) {
                invalidRefunds.add(refund.factId());
                issues.add(new Issue(refund.source(),refund.sourceIds().get(0),"REFUND_EXCEEDS_ORIGINAL_AMOUNT",refund.customerId()));
            }
        }
        invalidRefunds.forEach(facts::remove);
        // Legacy missing-source queries can overlook a retained, independently verified soft-deleted root.
        for(var row:rows) {
            if(row==null)continue;
            if(!Source.UNMATCHED_LEDGER.name().equals(text(row,"source")))continue;
            boolean resolved=number(row,"ledgerId")>0 && facts.values().stream().anyMatch(f ->
                f.ledgerId()==number(row,"ledgerId") && f.customerId()==number(row,"customerId")
                && f.kind().name().equals(text(row,"kind")) && Objects.equals(f.sourceBusinessId(),text(row,"businessId"))
                && f.currency().equals(text(row,"currency")) && decimal(row,"amount")!=null
                && f.amount().compareTo(decimal(row,"amount"))==0);
            if(!resolved) {
                unmatchedKinds.put(number(row,"customerId")+":"+text(row,"sourceId"),text(row,"kind"));
                issues.add(new Issue(Source.UNMATCHED_LEDGER,text(row,"sourceId"),
                Kind.DEVICE_PURCHASE_REFUND.name().equals(text(row,"kind"))?"MISSING_AUTHORITATIVE_REFUND_SOURCE":"MISSING_AUTHORITATIVE_SOURCE",
                number(row,"customerId")>0?number(row,"customerId"):null));
            }
        }
        var sorted = facts.values().stream().sorted(Comparator.comparing(Fact::succeededAt).thenComparing(Fact::factId)).toList();
        var index=index(captured,sorted);
        var customerCoverage=customerCoverage(ids,captured,index,issues,excludedCustomers,unmatchedKinds);
        var coverage = new ArrayList<Coverage>();
        for (Source source : Source.values()) {
            var reasons = new TreeSet<String>();
            boolean deposit=source == Source.DEPOSIT_ORDER || source == Source.CARD_TOPUP || source == Source.VIETQR || source == Source.HDPAY;
            var scoped=customerCoverage.stream().filter(c -> c.source()==source).toList();
            boolean lifetime=scoped.size()==ids.size() && scoped.stream().allMatch(c -> c.historyStatus()==Status.READY);
            boolean environment=lifetime && scoped.stream().allMatch(c -> c.historicalEnvironmentStatus()==Status.READY);
            boolean refunds=deposit || scoped.size()==ids.size() && scoped.stream().allMatch(c -> c.refundStatus()==Status.READY);
            if(!lifetime)reasons.add("LIFETIME_HISTORY_COVERAGE_NOT_ATTESTED");
            if(!environment)reasons.add("HISTORICAL_ENVIRONMENT_NOT_RECORDED");
            if(deposit)reasons.add("SUCCESSFUL_DEPOSITS_NON_REFUNDABLE");
            else if(!refunds)reasons.add("REFUND_HISTORY_COVERAGE_NOT_ATTESTED");
            scoped.forEach(c -> reasons.addAll(c.reasons()));
            issues.stream().filter(i -> i.source() == source).map(Issue::reason).forEach(reasons::add);
            coverage.add(new Coverage(source,issues.stream().anyMatch(i -> i.source() == source) ? Status.UNKNOWN : Status.READY,
                lifetime?Status.READY:Status.UNKNOWN,refunds?Status.READY:Status.UNKNOWN,environment?Status.READY:Status.UNKNOWN,
                lifetime?scoped.stream().map(CustomerCoverage::supportedFrom).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null):null,
                List.copyOf(reasons),excluded.getOrDefault(source,0L),ADAPTER_VERSION));
        }
        return new Snapshot(sorted,issues,coverage,DateTimeFormatConfig.BUSINESS_ZONE.getId(),Instant.now(),
            firstHistory(ids,captured,index,issues),customerCoverage);
    }

    private static final Set<Source> PAYMENT_SOURCES=EnumSet.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.VIETQR,
        Source.HDPAY,Source.WALLET_ORDER,Source.TRADE_IN,Source.CAPACITY_KEEP,Source.TRIAL_CONVERT);

    /** Shared by first-event and amount verification; refund interpretation stays separate. */
    private record ReconciledIndex(Map<String,Fact> byId,Map<String,List<Fact>> verified,
        Map<String,List<Fact>> payments,Map<String,List<Fact>> captures) { }
    private static ReconciledIndex index(CapturedFinancialHistory captured,List<Fact> facts) {
        var byId=new HashMap<String,Fact>();var verified=new HashMap<String,List<Fact>>();
        var payments=new HashMap<String,List<Fact>>();var captures=new HashMap<String,List<Fact>>();
        for(var fact:facts) {byId.put(fact.factId(),fact);payments.computeIfAbsent(fact.customerId()+":"+fact.source(),k -> new ArrayList<>()).add(fact);}
        for(var candidate:captured.candidates()) {
            var fact=candidate.fact();verified.computeIfAbsent(fact.factId(),k -> new ArrayList<>()).add(fact);
            captures.computeIfAbsent(fact.customerId()+":"+fact.source(),k -> new ArrayList<>()).add(fact);
        }
        return new ReconciledIndex(byId,verified,payments,captures);
    }
    private static List<CustomerCoverage> customerCoverage(List<Long> ids,CapturedFinancialHistory captured,ReconciledIndex index,
            List<Issue> issues,Set<String> excluded,Map<String,String> unmatchedKinds) {
        var byId=index.byId();var verified=index.verified();var payments=index.payments();var captures=index.captures();
        var result=new ArrayList<CustomerCoverage>();
        for(long customer:ids) {
            var refundReasons=sourceReasons(customer,Source.ORDER_REFUND,captured,issues,excluded,unmatchedKinds);
            var customerRefunds=payments.getOrDefault(customer+":"+Source.ORDER_REFUND,List.of());
            bilateral(customerRefunds,captures.getOrDefault(customer+":"+Source.ORDER_REFUND,List.of()),byId,verified,refundReasons);
            var origins=captured.refundOrigins().stream().filter(r -> number(r,"customerId")==customer).toList();
            for(var refund:customerRefunds) {
                var original=byId.get(refund.originalFactId());
                var matches=origins.stream().filter(r -> refund.ledgerId()==number(r,"ledgerId")
                    && Objects.equals(refund.orderNo(),text(r,"orderNo"))).toList();
                if(original==null || matches.size()!=1 || !validRefundOrigin(matches.get(0),refund,original))
                    refundReasons.add("BACKEND_SUCCESSFUL_ORIGINAL_REFUND_NOT_PROVEN");
            }
            for(var origin:origins) {
                var matches=customerRefunds.stream().filter(f -> f.ledgerId()==number(origin,"ledgerId")
                    && Objects.equals(f.orderNo(),text(origin,"orderNo"))).toList();
                if(matches.size()!=1)refundReasons.add("BACKEND_REFUND_NOT_RECONCILED");
            }
            boolean refundReady=refundReasons.isEmpty();
            for(Source source:Source.values()) {
                if(!PAYMENT_SOURCES.contains(source) && source!=Source.ORDER_REFUND && !genesis(source))continue;
                var reasons=source==Source.ORDER_REFUND?new TreeSet<>(refundReasons):sourceReasons(customer,source,captured,issues,excluded,unmatchedKinds);
                var actual=payments.getOrDefault(customer+":"+source,List.of());
                if(source!=Source.ORDER_REFUND)bilateral(actual,captures.getOrDefault(customer+":"+source,List.of()),byId,verified,reasons);
                boolean history=reasons.isEmpty();
                boolean environment=history && actual.stream().allMatch(f -> captured.historicalProductionFacts()
                    .contains(source==Source.ORDER_REFUND?f.originalFactId():f.factId()));
                if(!environment)reasons.add("HISTORICAL_ENVIRONMENT_NOT_RECORDED");
                boolean deposit=source==Source.DEPOSIT_ORDER || source==Source.CARD_TOPUP || source==Source.VIETQR || source==Source.HDPAY;
                boolean refund=deposit || refundReady;
                if(!deposit && !refund)reasons.addAll(refundReasons);
                result.add(new CustomerCoverage(customer,source,history?Status.READY:Status.UNKNOWN,refund?Status.READY:Status.UNKNOWN,
                    environment?Status.READY:Status.UNKNOWN,history?captured.productionBirths().get(customer):null,List.copyOf(reasons)));
            }
        }
        return List.copyOf(result);
    }
    private static TreeSet<String> sourceReasons(long customer,Source source,CapturedFinancialHistory captured,List<Issue> issues,
            Set<String> excluded,Map<String,String> unmatchedKinds) {
        var reasons=new TreeSet<String>();var birth=captured.registrationProblems().get(customer);
        if(birth==null || !captured.productionBirths().containsKey(customer))reasons.add("NEW_ACCOUNT_BIRTH_NOT_PROVEN");
        if(birth!=null)reasons.addAll(birth);
        for(var issue:issues) {
            if(issue.customerId()!=null && issue.customerId()!=customer)continue;
            if(issue.source()==Source.UNMATCHED_LEDGER && issue.customerId()!=null && issue.sourceId()!=null) {
                String kind=unmatchedKinds.get(customer+":"+issue.sourceId());
                boolean deposit=source==Source.DEPOSIT_ORDER || source==Source.CARD_TOPUP || source==Source.VIETQR || source==Source.HDPAY;
                if(Kind.DEPOSIT.name().equals(kind) && !deposit || Kind.DEVICE_PURCHASE.name().equals(kind) && (deposit || source==Source.ORDER_REFUND))continue;
            }
            // Unknown ledger classification can hide any payment; refunds have their own ledger/domain read.
            if(issue.source()==source || issue.source()==null || issue.source()==Source.UNMATCHED_LEDGER
                && source!=Source.ORDER_REFUND && firstBarrier(issue))reasons.add(issue.reason());
        }
        if(excluded.contains(customer+":"+source))reasons.add("EXCLUDED_ENVIRONMENT_HISTORY_NOT_PROVEN");
        return reasons;
    }
    private static void bilateral(List<Fact> actual,List<Fact> captured,Map<String,Fact> byId,Map<String,List<Fact>> verified,Set<String> reasons) {
        for(var fact:actual)if(verified.getOrDefault(fact.factId(),List.of()).stream().noneMatch(c -> sameCapturedProjection(c,fact)))
            reasons.add("PAYMENT_NEW_SUCCESS_NOT_PROVEN");
        for(var fact:captured) {
            var reconciled=byId.get(fact.factId());
            if(reconciled==null || !sameCapturedProjection(fact,reconciled))reasons.add("CAPTURED_PAYMENT_NOT_RECONCILED");
        }
    }
    private static boolean validRefundOrigin(Map<String,Object> row,Fact refund,Fact original) {
        return number(row,"customerId")==refund.customerId() && number(row,"proofCount")==1
            && number(row,"ledgerId")==refund.ledgerId() && Objects.equals(time(row,"ledgerAt"),refund.ledgerRecordedAt())
            && "USDT".equals(refund.currency()) && original.currency().equals(refund.currency())
            && decimal(row,"amount")!=null && decimal(row,"amount").compareTo(original.amount())==0
            && refund.amount().compareTo(original.amount())==0;
    }

    private static List<FirstHistory> firstHistory(List<Long> ids,CapturedFinancialHistory captured,ReconciledIndex index,List<Issue> issues) {
        var result=new ArrayList<FirstHistory>();
        for(long customer:ids) {
            var reasons=new TreeSet<String>();
            var registration=captured.registrationProblems().get(customer);
            if(registration==null)reasons.add("NEW_ACCOUNT_BIRTH_NOT_PROVEN");else reasons.addAll(registration);
            for(var issue:issues) {
                if(issue.customerId()!=null && issue.customerId()!=customer)continue;
                if(firstBarrier(issue))reasons.add(issue.reason());
            }
            for(var source:PAYMENT_SOURCES)bilateral(index.payments().getOrDefault(customer+":"+source,List.of()),
                index.captures().getOrDefault(customer+":"+source,List.of()),index.byId(),index.verified(),reasons);
            result.add(new FirstHistory(customer,reasons.isEmpty()?Status.READY:Status.UNKNOWN,List.copyOf(reasons)));
        }
        return List.copyOf(result);
    }
    private static boolean firstBarrier(Issue issue) {
        if(genesis(issue.source()))return false;
        if("UNKNOWN_FINANCIAL_KIND".equals(issue.reason()))return true;
        if(issue.source()==Source.ORDER_REFUND || issue.source()==Source.FREE_TRIAL)return false;
        return !"MISSING_AUTHORITATIVE_REFUND_SOURCE".equals(issue.reason())
            && !"FINAL_DEPOSIT_REFUND_SOURCE_UNAVAILABLE".equals(issue.reason());
    }
    private static boolean capturedContradiction(List<Issue> issues,long customer,Source source,String canonical) {
        return customer>0 && issues.stream().anyMatch(i -> i.customerId()!=null && i.customerId()==customer
            && i.source()==source && Objects.equals(i.sourceId(),canonical) && capturedFinancialContradiction(i.reason()));
    }

    private static void accept(Fact fact,String logicalKey,Map<String,Fact> facts,Set<String> rejected,
            Map<String,String> logicalPayments,List<Issue> issues) {
        String id=fact.factId();Fact previous=facts.get(id);
        if(previous!=null) {
            if(!samePayment(previous,fact)) {
                rejected.add(id);issues.add(new Issue(fact.source(),fact.sourceIds().get(0),"CONFLICTING_FACT_PROJECTION",fact.customerId()));
            } else {
                var refs=new TreeSet<>(previous.sourceIds());refs.addAll(fact.sourceIds());
                facts.put(id,new Fact(previous.factId(),previous.kind(),previous.source(),List.copyOf(refs),previous.customerId(),
                    previous.ledgerId(),previous.sourceBusinessId(),previous.orderNo(),previous.orderType(),previous.originalFactId(),
                    previous.currency(),previous.amount(),previous.succeededAt(),previous.successTimeField(),previous.fractionalSecondDigits(),
                    previous.providerPaidAt(),previous.ledgerRecordedAt(),previous.sourceConfirmationAt(),previous.sourceVersion(),previous.historicalEnvironmentStatus()));
            }
        } else facts.put(id,fact);
        String logical=fact.customerId()+":"+fact.kind()+":"+fact.currency()+":"+logicalKey;
        String prior=logicalPayments.putIfAbsent(logical,id);
        if(prior!=null && !prior.equals(id)) {
            rejected.add(prior);rejected.add(id);issues.add(new Issue(fact.source(),fact.sourceIds().get(0),"DUPLICATE_PAYMENT_SOURCE",fact.customerId()));
        }
    }
    private static boolean sameCapturedProjection(Fact left,Fact right) {
        return samePayment(left,right) && left.source()==right.source() && left.sourceIds().equals(right.sourceIds())
            && Objects.equals(left.sourceBusinessId(),right.sourceBusinessId()) && Objects.equals(left.orderType(),right.orderType())
            && Objects.equals(left.originalFactId(),right.originalFactId()) && Objects.equals(left.successTimeField(),right.successTimeField())
            && left.fractionalSecondDigits()==right.fractionalSecondDigits() && Objects.equals(left.providerPaidAt(),right.providerPaidAt());
    }

    private static void load(List<Map<String,Object>> rows,List<Issue> issues,Supplier<List<Map<String,Object>>> query,Source... sources) {
        try { rows.addAll(query.get()); }
        catch (DataAccessException failure) {
            // A failed source is visible in coverage; exception messages can contain private connection details.
            for (Source source : sources) issues.add(new Issue(source,null,"SOURCE_READ_FAILED"));
        }
    }
    static String invalid(Map<String,Object> r, Source source) {
        return invalid(r,source,false);
    }
    static String invalidNewSource(Map<String,Object> r,Source source) { return invalid(r,source,true); }
    // Only the source service's private same-transaction proof permits the causal-time branch.
    private static String invalid(Map<String,Object> r, Source source, boolean newSuccess) {
        if (source == Source.UNMATCHED_LEDGER) return "MISSING_AUTHORITATIVE_SOURCE";
        if (decimal(r,"amount") == null || decimal(r,"amount").signum() <= 0) return "NON_POSITIVE_AMOUNT";
        if (number(r,"customerId") <= 0 || text(r,"sourceId") == null || text(r,"businessId") == null) return "MISSING_SOURCE_ID";
        if (number(r,"ledgerId") <= 0) return "MISSING_SETTLEMENT_LEDGER";
        if (number(r,"sourceLinked") != 1) return "BROKEN_SOURCE_LINK";
        if (number(r,"ledgerCustomerId") != number(r,"customerId") || !Objects.equals(ffdd.opsconsole.finance.mapper.SupportPaymentSourceSql.ledgerKey(source,text(r,"businessId")),text(r,"ledgerBusinessId"))
                || !Objects.equals(text(r,"currency"),text(r,"ledgerCurrency")) || decimal(r,"ledgerAmount") == null
                || decimal(r,"amount").compareTo(decimal(r,"ledgerAmount")) != 0) return "SETTLEMENT_MISMATCH";
        String expectedType = switch (source) {
            case CARD_TOPUP -> "CARD_TOPUP"; case VIETQR, HDPAY -> "VIETQR_DEPOSIT";
            case WALLET_ORDER -> "ORDER_PURCHASE"; case TRADE_IN -> "TRADE_IN_PURCHASE";
            case CAPACITY_KEEP -> "DEVICE_PURCHASE"; case TRIAL_CONVERT -> "TRIAL_CHARGE";
            case ORDER_REFUND -> "ORDER_REFUND"; default -> null;
        };
        if (expectedType != null && !expectedType.equals(text(r,"ledgerType"))) return "SETTLEMENT_TYPE_MISMATCH";
        if (source == Source.DEPOSIT_ORDER && !Set.of("CHAIN_TOPUP","DEPOSIT","TOPUP").contains(text(r,"ledgerType")))
            return "SETTLEMENT_TYPE_MISMATCH";
        if(genesis(source) && !ffdd.opsconsole.finance.mapper.SupportPaymentSourceSql.ledgerType(source).equals(text(r,"ledgerType")))return "SETTLEMENT_TYPE_MISMATCH";
        String expectedDirection = Kind.DEVICE_PURCHASE.name().equals(text(r,"kind")) || Kind.CONSUMPTION.name().equals(text(r,"kind")) ? "OUT" : "IN";
        if (!expectedDirection.equals(text(r,"ledgerDirection")) || !(source == Source.TRIAL_CONVERT ? "POSTED" : "SUCCESS").equals(text(r,"ledgerStatus"))
                || number(r,"ledgerDeleted") != 0) return "UNSUCCESSFUL_SETTLEMENT";
        LocalDateTime succeeded=time(r,"succeededAt"),ledgerAt=time(r,"ledgerRecordedAt");
        if (succeeded == null) return "MISSING_SUCCESS_TIME";
        if (ledgerAt == null) return "MISSING_SETTLEMENT_TIME";
        if (!newSuccess && !sameRecordedSecond(succeeded,ledgerAt)) return "CONFLICTING_SUCCESS_TIME";
        if (source == Source.WALLET_ORDER || source == Source.TRIAL_CONVERT) {
            LocalDateTime confirmation=time(r,"sourceConfirmationAt");
            if (confirmation == null) return "MISSING_SOURCE_CONFIRMATION_TIME";
            if (!newSuccess && !sameRecordedSecond(succeeded,confirmation)) return "CONFLICTING_SUCCESS_TIME";
        }
        if (source == Source.ORDER_REFUND && text(r,"orderNo") == null) return "MISSING_ORIGINAL_ORDER";
        return null;
    }
    static Fact fact(Map<String,Object> row,Source source) {
        Kind kind=Kind.valueOf(text(row,"kind"));long ledger=number(row,"ledgerId");
        String id=kind==Kind.DEPOSIT?"DEPOSIT:"+ledger:kind==Kind.DEVICE_PURCHASE
            ?"PURCHASE:"+text(row,"orderNo"):kind==Kind.CONSUMPTION?"CONSUMPTION:"+text(row,"orderNo"):"ORDER_REFUND:"+ledger;
        return new Fact(id,kind,source,List.of(text(row,"sourceId")),number(row,"customerId"),ledger,
            text(row,"businessId"),text(row,"orderNo"),text(row,"orderType"),
            kind==Kind.DEVICE_PURCHASE_REFUND?"PURCHASE:"+text(row,"orderNo"):null,
            text(row,"currency"),decimal(row,"amount"),time(row,"succeededAt"),text(row,"successTimeField"),
            Math.toIntExact(number(row,"fractionalSecondDigits")),time(row,"providerPaidAt"),
            time(row,"ledgerRecordedAt"),time(row,"sourceConfirmationAt"),text(row,"sourceVersion"),Status.UNKNOWN);
    }
    static boolean samePayment(Fact left,Fact right) {
        return left.kind()==right.kind() && left.customerId()==right.customerId() && left.ledgerId()==right.ledgerId()
            && left.currency().equals(right.currency()) && left.amount().compareTo(right.amount())==0
            && left.succeededAt().equals(right.succeededAt()) && Objects.equals(left.orderNo(),right.orderNo())
            && Objects.equals(left.ledgerRecordedAt(),right.ledgerRecordedAt())
            && Objects.equals(left.sourceConfirmationAt(),right.sourceConfirmationAt());
    }
    // Ledger/payment/claim DATETIME currently preserves seconds, while order.paid_at is DATETIME(6).
    // Require overlapping source precision, without guessing a delay allowance. A real crossing-second
    // transaction is conservatively UNKNOWN until stronger timing evidence can attest it.
    private static boolean sameRecordedSecond(LocalDateTime a,LocalDateTime b) {
        return a.withNano(0).equals(b.withNano(0));
    }
    static String text(Map<String,Object> r,String key) { Object value=r.get(key); return value==null?null:value.toString(); }
    static long number(Map<String,Object> r,String key) { Object value=r.get(key); return value instanceof Number n?n.longValue():value==null?0:Long.parseLong(value.toString()); }
    static int flag(Map<String,Object> row,String key) {
        Object value=row.get(key);
        if(value instanceof Boolean bit)return bit?1:0;
        if(value instanceof Number) {
            BigDecimal bit=new BigDecimal(value.toString());
            if(bit.compareTo(BigDecimal.ZERO)==0)return 0;
            if(bit.compareTo(BigDecimal.ONE)==0)return 1;
        }
        throw new IllegalArgumentException("Invalid binary flag: "+key);
    }
    static BigDecimal decimal(Map<String,Object> r,String key) { Object value=r.get(key); return value==null?null:value instanceof BigDecimal d?d:new BigDecimal(value.toString()); }
    static LocalDateTime time(Map<String,Object> r,String key) {
        Object value=r.get(key); return value instanceof LocalDateTime t?t:value instanceof Timestamp t?t.toLocalDateTime():null;
    }
}
