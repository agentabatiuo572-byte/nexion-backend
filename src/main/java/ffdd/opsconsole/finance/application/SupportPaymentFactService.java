package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper;
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
@RequiredArgsConstructor
public class SupportPaymentFactService {
    public static final String ADAPTER_VERSION = "support-payment-facts-v1";
    private final SupportPaymentFactMapper mapper;

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
            Map<Long,List<String>> registrationProblems) {
        CapturedFinancialHistory(List<VerifiedCandidate> candidates,List<Issue> issues,Set<String> conflictingFactIds) {
            this(candidates,issues,conflictingFactIds,Map.of());
        }
        CapturedFinancialHistory {
            candidates=List.copyOf(candidates);issues=List.copyOf(issues);conflictingFactIds=Set.copyOf(conflictingFactIds);
            var copy=new TreeMap<Long,List<String>>();
            registrationProblems.forEach((id,reasons) -> copy.put(id,List.copyOf(reasons)));
            registrationProblems=Collections.unmodifiableMap(copy);
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
        load(rows, issues, () -> mapper.deposits(ids), Source.DEPOSIT_ORDER);
        load(rows, issues, () -> mapper.cards(ids), Source.CARD_TOPUP);
        load(rows, issues, () -> mapper.vietqr(ids), Source.VIETQR);
        load(rows, issues, () -> mapper.hdpay(ids), Source.HDPAY);
        load(rows, issues, () -> mapper.orders(ids), Source.WALLET_ORDER, Source.TRADE_IN, Source.CAPACITY_KEEP);
        load(rows, issues, () -> mapper.trials(ids), Source.TRIAL_CONVERT);
        load(rows, issues, () -> mapper.refunds(ids), Source.ORDER_REFUND);
        load(rows, issues, () -> mapper.freeTrials(ids), Source.FREE_TRIAL);
        load(rows, issues, () -> mapper.unmatched(ids), Source.UNMATCHED_LEDGER);

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
                default -> null;
            };
            if(kind!=expected) {
                issues.add(new Issue(source,sourceId,"UNKNOWN_FINANCIAL_KIND",customer>0?customer:null));continue;
            }
            String id=kind==Kind.DEPOSIT ? "DEPOSIT:"+number(row,"ledgerId") : kind==Kind.DEVICE_PURCHASE
                ? "PURCHASE:"+text(row,"orderNo") : "ORDER_REFUND:"+number(row,"ledgerId");
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
            if(!resolved)issues.add(new Issue(Source.UNMATCHED_LEDGER,text(row,"sourceId"),
                Kind.DEVICE_PURCHASE_REFUND.name().equals(text(row,"kind"))?"MISSING_AUTHORITATIVE_REFUND_SOURCE":"MISSING_AUTHORITATIVE_SOURCE",
                number(row,"customerId")>0?number(row,"customerId"):null));
        }
        var coverage = new ArrayList<Coverage>();
        for (Source source : Source.values()) {
            var reasons = new TreeSet<String>();
            reasons.add("LIFETIME_HISTORY_COVERAGE_NOT_ATTESTED");
            reasons.add("HISTORICAL_ENVIRONMENT_NOT_RECORDED");
            reasons.add(source == Source.DEPOSIT_ORDER || source == Source.CARD_TOPUP || source == Source.VIETQR
                || source == Source.HDPAY ? "FINAL_DEPOSIT_REFUND_SOURCE_UNAVAILABLE" : "REFUND_HISTORY_COVERAGE_NOT_ATTESTED");
            issues.stream().filter(i -> i.source() == source).map(Issue::reason).forEach(reasons::add);
            coverage.add(new Coverage(source,issues.stream().anyMatch(i -> i.source() == source) ? Status.UNKNOWN : Status.READY,
                Status.UNKNOWN,Status.UNKNOWN,Status.UNKNOWN,null,List.copyOf(reasons),excluded.getOrDefault(source,0L),ADAPTER_VERSION));
        }
        var sorted = facts.values().stream().sorted(Comparator.comparing(Fact::succeededAt).thenComparing(Fact::factId)).toList();
        return new Snapshot(sorted,issues,coverage,DateTimeFormatConfig.BUSINESS_ZONE.getId(),Instant.now(),
            firstHistory(ids,captured,sorted,issues));
    }

    private static List<FirstHistory> firstHistory(List<Long> ids,CapturedFinancialHistory captured,List<Fact> facts,List<Issue> issues) {
        var result=new ArrayList<FirstHistory>();
        var payments=new HashMap<Long,List<Fact>>();
        var captures=new HashMap<Long,List<Fact>>();
        var verifiedById=new HashMap<String,List<Fact>>();
        var reconciledById=new HashMap<String,Fact>();
        for(var fact:facts)if(fact.kind()!=Kind.DEVICE_PURCHASE_REFUND) {
            payments.computeIfAbsent(fact.customerId(),ignored -> new ArrayList<>()).add(fact);
            reconciledById.put(fact.factId(),fact);
        }
        for(var candidate:captured.candidates()) {
            var fact=candidate.fact();if(fact.kind()==Kind.DEVICE_PURCHASE_REFUND)continue;
            captures.computeIfAbsent(fact.customerId(),ignored -> new ArrayList<>()).add(fact);
            verifiedById.computeIfAbsent(fact.factId(),ignored -> new ArrayList<>()).add(fact);
        }
        for(long customer:ids) {
            var reasons=new TreeSet<String>();
            var registration=captured.registrationProblems().get(customer);
            if(registration==null)reasons.add("NEW_ACCOUNT_BIRTH_NOT_PROVEN");else reasons.addAll(registration);
            for(var issue:issues) {
                if(issue.customerId()!=null && issue.customerId()!=customer)continue;
                if(firstBarrier(issue))reasons.add(issue.reason());
            }
            // Forward: every reconciled real payment needs the same independently verified NEW capture.
            for(var fact:payments.getOrDefault(customer,List.of())) {
                if(verifiedById.getOrDefault(fact.factId(),List.of()).stream().noneMatch(c -> sameCapturedProjection(c,fact)))
                    reasons.add("PAYMENT_NEW_SUCCESS_NOT_PROVEN");
            }
            // Reverse: a verified payment may not disappear during source merging, dedup or conflict rejection.
            for(var fact:captures.getOrDefault(customer,List.of())) {
                var reconciled=reconciledById.get(fact.factId());
                if(reconciled==null || !sameCapturedProjection(fact,reconciled))reasons.add("CAPTURED_PAYMENT_NOT_RECONCILED");
            }
            result.add(new FirstHistory(customer,reasons.isEmpty()?Status.READY:Status.UNKNOWN,List.copyOf(reasons)));
        }
        return List.copyOf(result);
    }
    private static boolean firstBarrier(Issue issue) {
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
        if (number(r,"ledgerCustomerId") != number(r,"customerId") || !Objects.equals(text(r,"businessId"),text(r,"ledgerBusinessId"))
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
        String expectedDirection = Kind.DEVICE_PURCHASE.name().equals(text(r,"kind")) ? "OUT" : "IN";
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
            ?"PURCHASE:"+text(row,"orderNo"):"ORDER_REFUND:"+ledger;
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
    static BigDecimal decimal(Map<String,Object> r,String key) { Object value=r.get(key); return value==null?null:value instanceof BigDecimal d?d:new BigDecimal(value.toString()); }
    static LocalDateTime time(Map<String,Object> r,String key) {
        Object value=r.get(key); return value instanceof LocalDateTime t?t:value instanceof Timestamp t?t.toLocalDateTime():null;
    }
}
