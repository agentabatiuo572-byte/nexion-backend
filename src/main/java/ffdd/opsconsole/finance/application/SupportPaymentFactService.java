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
import static ffdd.opsconsole.finance.application.SupportPaymentFacts.*;

/** Internal source adapter. A caller must authorize the requested customer IDs. */
@ApplicationService
@RequiredArgsConstructor
public class SupportPaymentFactService {
    public static final String ADAPTER_VERSION = "support-payment-facts-v1";
    private final SupportPaymentFactMapper mapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot read(Collection<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty() || customerIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("Explicit positive customer scope required");
        var ids = List.copyOf(new TreeSet<>(customerIds));
        var rows = new ArrayList<Map<String,Object>>();
        var issues = new ArrayList<Issue>();
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
        var rejected = new HashSet<String>();
        var logicalPayments = new HashMap<String,String>();
        for (var row : rows) {
            Source source = Source.valueOf(text(row,"source"));
            String sourceId = text(row,"sourceId");
            if (source == Source.FREE_TRIAL || number(row,"excludedEnvironment") == 1
                    || (Kind.DEVICE_PURCHASE.name().equals(text(row,"kind")) && decimal(row,"amount") != null
                        && decimal(row,"amount").signum() == 0
                        && (decimal(row,"ledgerAmount") == null || decimal(row,"ledgerAmount").signum() == 0))) {
                excluded.merge(source,1L,Long::sum); continue;
            }
            String problem = invalid(row, source);
            if (problem != null) { issues.add(new Issue(source,sourceId,problem)); continue; }
            Kind kind = Kind.valueOf(text(row,"kind"));
            long ledger = number(row,"ledgerId"), customer = number(row,"customerId");
            String id = kind == Kind.DEPOSIT ? "DEPOSIT:"+ledger : kind == Kind.DEVICE_PURCHASE
                ? "PURCHASE:"+text(row,"orderNo") : "ORDER_REFUND:"+ledger;
            var fact = new Fact(id,kind,source,List.of(sourceId),customer,ledger,text(row,"businessId"),
                text(row,"orderNo"),text(row,"orderType"),kind == Kind.DEVICE_PURCHASE_REFUND
                    ? "PURCHASE:"+text(row,"orderNo") : null,
                text(row,"currency"),decimal(row,"amount"),time(row,"succeededAt"),text(row,"successTimeField"),
                Math.toIntExact(number(row,"fractionalSecondDigits")),time(row,"providerPaidAt"),
                time(row,"ledgerRecordedAt"),time(row,"sourceConfirmationAt"),text(row,"sourceVersion"),Status.UNKNOWN);
            Fact previous = facts.get(id);
            if (previous != null) {
                if (!samePayment(previous,fact)) {
                    rejected.add(id); issues.add(new Issue(source,sourceId,"CONFLICTING_FACT_PROJECTION"));
                } else {
                    var references = new TreeSet<>(previous.sourceIds()); references.addAll(fact.sourceIds());
                    facts.put(id,new Fact(previous.factId(),previous.kind(),previous.source(),List.copyOf(references),
                        previous.customerId(),previous.ledgerId(),previous.sourceBusinessId(),previous.orderNo(),previous.orderType(),
                        previous.originalFactId(),previous.currency(),previous.amount(),previous.succeededAt(),
                        previous.successTimeField(),previous.fractionalSecondDigits(),previous.providerPaidAt(),
                        previous.ledgerRecordedAt(),previous.sourceConfirmationAt(),previous.sourceVersion(),previous.historicalEnvironmentStatus()));
                }
            } else facts.put(id,fact);
            String logical = customer+":"+kind+":"+text(row,"currency")+":"+text(row,"duplicateKey");
            String priorId = logicalPayments.putIfAbsent(logical,id);
            if (priorId != null && !priorId.equals(id)) {
                rejected.add(priorId); rejected.add(id);
                issues.add(new Issue(source,sourceId,"DUPLICATE_PAYMENT_SOURCE"));
            }
        }
        // A legacy conversion backfill can map the same charge to multiple orders.
        var ledgerOwners = new HashMap<String,String>();
        for (Fact fact : facts.values()) {
            String ledgerKey = fact.kind()+":"+fact.ledgerId();
            String previous = ledgerOwners.putIfAbsent(ledgerKey,fact.factId());
            if (previous != null && !previous.equals(fact.factId())) {
                rejected.add(previous); rejected.add(fact.factId());
                issues.add(new Issue(fact.source(),fact.sourceIds().get(0),"DUPLICATE_SETTLEMENT_LEDGER"));
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
                issues.add(new Issue(refund.source(),refund.sourceIds().get(0),"UNPROVEN_ORIGINAL_PAYMENT"));
            } else if (refund.succeededAt().withNano(0).isBefore(original.succeededAt().withNano(0))) {
                invalidRefunds.add(refund.factId());
                issues.add(new Issue(refund.source(),refund.sourceIds().get(0),"REFUND_PREDATES_ORIGINAL_PAYMENT"));
            } else refundTotals.merge(original.factId(),refund.amount(),BigDecimal::add);
        }
        for (Fact refund : facts.values()) {
            BigDecimal total = refundTotals.get(refund.originalFactId());
            Fact original = facts.get(refund.originalFactId());
            if (refund.kind() == Kind.DEVICE_PURCHASE_REFUND && total != null && original != null
                    && total.compareTo(original.amount()) > 0) {
                invalidRefunds.add(refund.factId());
                issues.add(new Issue(refund.source(),refund.sourceIds().get(0),"REFUND_EXCEEDS_ORIGINAL_AMOUNT"));
            }
        }
        invalidRefunds.forEach(facts::remove);
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
        return new Snapshot(sorted,issues,coverage,DateTimeFormatConfig.BUSINESS_ZONE.getId(),Instant.now());
    }

    private static void load(List<Map<String,Object>> rows,List<Issue> issues,Supplier<List<Map<String,Object>>> query,Source... sources) {
        try { rows.addAll(query.get()); }
        catch (DataAccessException failure) {
            // A failed source is visible in coverage; exception messages can contain private connection details.
            for (Source source : sources) issues.add(new Issue(source,null,"SOURCE_READ_FAILED"));
        }
    }
    private static String invalid(Map<String,Object> r, Source source) {
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
        if (!sameRecordedSecond(succeeded,ledgerAt)) return "CONFLICTING_SUCCESS_TIME";
        if (source == Source.WALLET_ORDER || source == Source.TRIAL_CONVERT) {
            LocalDateTime confirmation=time(r,"sourceConfirmationAt");
            if (confirmation == null) return "MISSING_SOURCE_CONFIRMATION_TIME";
            if (!sameRecordedSecond(succeeded,confirmation)) return "CONFLICTING_SUCCESS_TIME";
        }
        if (source == Source.ORDER_REFUND && text(r,"orderNo") == null) return "MISSING_ORIGINAL_ORDER";
        return null;
    }
    private static boolean samePayment(Fact left,Fact right) {
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
    private static String text(Map<String,Object> r,String key) { Object value=r.get(key); return value==null?null:value.toString(); }
    private static long number(Map<String,Object> r,String key) { Object value=r.get(key); return value instanceof Number n?n.longValue():value==null?0:Long.parseLong(value.toString()); }
    private static BigDecimal decimal(Map<String,Object> r,String key) { Object value=r.get(key); return value==null?null:value instanceof BigDecimal d?d:new BigDecimal(value.toString()); }
    private static LocalDateTime time(Map<String,Object> r,String key) {
        Object value=r.get(key); return value instanceof LocalDateTime t?t:value instanceof Timestamp t?t.toLocalDateTime():null;
    }
}
