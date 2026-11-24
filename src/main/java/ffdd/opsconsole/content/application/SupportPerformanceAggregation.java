package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.content.domain.SupportPerformance.*;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper.Evidence;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper.Point;
import ffdd.opsconsole.finance.facade.SupportDirectPurchaseFactsFacade.Fact;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ConfirmedDepositCorrection;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ConfirmedWithdrawal;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/** Establishes one set of verified events for reports, rankings and exports. */
public final class SupportPerformanceAggregation {
    private static final EventAttribution UNKNOWN = new EventAttribution(AttributionState.UNKNOWN, null, null);

    private SupportPerformanceAggregation() { }

    static List<Event> events(SupportPaymentFacts.Snapshot payments,
            List<ObservedDeposit> deposits, List<ConfirmedDepositCorrection> corrections,
            List<ConfirmedWithdrawal> withdrawals, List<Fact> purchases,
            SupportPerformanceDepositAttribution.Result attribution, SupportPerformanceHistoryMapper history) {
        var originals = new HashMap<String, SupportPaymentFacts.Fact>();
        for (var fact : payments.facts()) {
            if (fact.kind() == SupportPaymentFacts.Kind.DEPOSIT && SupportPerformance.CURRENCY.equals(fact.currency())
                    && Objects.equals(attribution.verifiedOriginals().get(fact.factId()), fact.customerId())
                    && depositChannel(fact) != null) {
                originals.put(fact.factId(), fact);
            }
        }
        Map<String, List<Evidence>> historical = history(originals.values(), ZoneId.of(payments.businessZone()),
                withdrawals, purchases, history);
        Map<String, Event> events = new LinkedHashMap<>();
        for (var deposit : deposits) {
            var original = originals.get(deposit.factId());
            if (original == null || original.customerId() != deposit.customerId()) continue;
            var assigned = normalized(attribution.events().get(deposit.factId()));
            events.putIfAbsent(deposit.factId(), new Event(deposit.factId(), deposit.customerId(), assigned,
                    archivedInterval(assigned, historical.get(deposit.factId())),
                    Kind.INFLOW, depositChannel(original), null, false, deposit.sourceBusinessId(), null, null,
                    deposit.creditedAt(), deposit.creditedAt(), "CREDITED", deposit.creditedUsdt(),
                    deposit.creditedUsdt(), null, null, null, null, null, deposit.creditedUsdt(), null));
        }
        for (var correction : corrections) {
            var original = originals.get(correction.originalFactId());
            if (original == null || original.customerId() != correction.customerId()) continue;
            var assigned = normalized(attribution.events().get(correction.operationNo()));
            String id = "CORRECTION:" + correction.operationNo();
            events.putIfAbsent(id, new Event(id, correction.customerId(), assigned,
                    archivedInterval(assigned, historical.get(correction.originalFactId())),
                    Kind.CORRECTION, depositChannel(original), null, false, correction.operationNo(),
                    original.sourceBusinessId(), correction.originalFactId(), correction.effectiveAt(), correction.effectiveAt(), correction.phase(),
                    correction.signedUsdt().abs(), correction.signedUsdt(), null, null, null, null, null, null, null));
        }
        for (var withdrawal : withdrawals) {
            String network = switch (withdrawal.rail()) {
                case "USDT-TRC20" -> "TRC20";
                case "USDT-BEP20" -> "BEP20";
                case "USDT-ERC20" -> "ERC20";
                default -> null;
            };
            String channel = "BANK-VND".equals(withdrawal.rail()) ? "VN_BANK" : network;
            if (channel == null) continue;
            String id = withdrawalKey(withdrawal);
            Evidence evidence = unique(historical.get(id));
            var assigned = attribution(evidence);
            events.putIfAbsent(id, new Event(id, withdrawal.customerId(), assigned, interval(assigned, evidence),
                    Kind.WITHDRAWAL, channel, network, false, withdrawal.withdrawalNo(), null, null,
                    withdrawal.requestedAt(), withdrawal.completedAt(), "CONFIRMED", withdrawal.walletDebitUsdt(),
                    withdrawal.walletDebitUsdt().negate(), null, null, withdrawal.feeUsdt(), null,
                    withdrawal.networkFeeUsdt(), withdrawal.receivedUsdt(), withdrawal.receivedVnd()));
        }
        for (var purchase : purchases) {
            String network = switch (purchase.paymentMethod()) {
                case "CREGIS_USDT_BSC" -> "BEP20";
                case "CREGIS_USDT_ERC20" -> "ERC20";
                case "CREGIS_USDT_TRC20" -> "TRC20";
                default -> null;
            };
            String channel = "HDPAY_BANK".equals(purchase.paymentMethod()) ? "VN_BANK_DIRECT_PURCHASE"
                    : network == null ? null : "CHAIN_DIRECT_PURCHASE";
            if (channel == null) continue;
            String id = purchaseKey(purchase);
            Evidence evidence = unique(historical.get(id));
            var assigned = attribution(evidence);
            events.putIfAbsent(id, new Event(id, purchase.customerId(), assigned, interval(assigned, evidence),
                    Kind.INFLOW, channel, network, true, purchase.orderNo(), null, null,
                    purchase.paidAt(), purchase.paidAt(), "DELIVERED", purchase.businessAmountUsdt(),
                    purchase.businessAmountUsdt(), purchase.roundingAdjustmentUsdt(), purchase.totalUsdt(), null,
                    purchase.channelFeeUsdt(), purchase.networkFeeUsdt(), null, purchase.paidVnd()));
        }
        return events.values().stream().sorted(Comparator.comparing(Event::effectiveAt).thenComparing(Event::id)).toList();
    }

    private static String depositChannel(SupportPaymentFacts.Fact fact) {
        return switch (fact.source()) {
            case DEPOSIT_ORDER -> fact.sourceBusinessId().matches("CR-[0-9]+") ? "CHAIN_TOPUP" : null;
            case VIETQR, HDPAY -> "VN_BANK_TOPUP";
            default -> null;
        };
    }

    private static String withdrawalKey(ConfirmedWithdrawal withdrawal) {
        return "WITHDRAWAL:" + withdrawal.withdrawalNo();
    }

    private static String purchaseKey(Fact purchase) { return "DIRECT_PURCHASE:" + purchase.orderNo(); }

    private static Map<String, List<Evidence>> history(Collection<SupportPaymentFacts.Fact> deposits,
            ZoneId paymentZone, List<ConfirmedWithdrawal> withdrawals, List<Fact> purchases, SupportPerformanceHistoryMapper mapper) {
        var points = new LinkedHashMap<String, Point>();
        for (var deposit : deposits) {
            String id = deposit.factId();
            points.putIfAbsent(id, new Point(id, deposit.customerId(), utc(deposit.succeededAt().atZone(paymentZone).toInstant())));
        }
        for (var withdrawal : withdrawals) {
            String id = withdrawalKey(withdrawal);
            points.putIfAbsent(id, new Point(id, withdrawal.customerId(), utc(withdrawal.completedAt())));
        }
        for (var purchase : purchases) {
            String id = purchaseKey(purchase);
            points.putIfAbsent(id, new Point(id, purchase.customerId(), utc(purchase.paidAt())));
        }
        var batch = List.copyOf(points.values());
        Map<String, List<Evidence>> result = new HashMap<>();
        for (int offset = 0; offset < batch.size(); offset += 200) {
            mapper.atEvents(batch.subList(offset, Math.min(offset + 200, batch.size()))).forEach(row ->
                    result.computeIfAbsent(row.eventId(), unused -> new ArrayList<>()).add(row));
        }
        return result;
    }

    private static LocalDateTime utc(Instant at) { return LocalDateTime.ofInstant(at, ZoneOffset.UTC); }

    private static Evidence unique(List<Evidence> rows) {
        return rows != null && rows.size() == 1 ? rows.get(0) : null;
    }

    private static EventAttribution normalized(EventAttribution value) {
        return value == null || value.state() == AttributionState.UNKNOWN ? UNKNOWN : value;
    }

    private static EventAttribution attribution(Evidence row) {
        if (row == null || row.boundaryUncertain()) return UNKNOWN;
        if (row.bindingId() == null) {
            return row.routeId() != null
                    ? new EventAttribution(AttributionState.UNASSIGNED, null, null) : UNKNOWN;
        }
        if (row.membershipId() == null || row.qualificationId() == null || !"ENABLED".equals(row.qualificationState())
                || (row.routeId() != null && !Objects.equals(row.routeGroupId(), row.groupId()))) return UNKNOWN;
        return new EventAttribution(row.groupId() == null ? AttributionState.AGENT_WITHOUT_GROUP : AttributionState.ASSIGNED,
                row.agentId(), row.groupId());
    }

    private static BindingPeriod interval(EventAttribution assigned, Evidence row) {
        if (assigned.agentId() == null) return null;
        var from = row.bindingFrom().isAfter(row.groupFrom()) ? row.bindingFrom() : row.groupFrom();
        var to = row.bindingTo();
        if (to == null || row.groupTo() != null && row.groupTo().isBefore(to)) to = row.groupTo();
        return new BindingPeriod(from.toInstant(ZoneOffset.UTC), to == null ? null : to.toInstant(ZoneOffset.UTC));
    }

    private static BindingPeriod archivedInterval(EventAttribution assigned, List<Evidence> rows) {
        Evidence evidence = unique(rows);
        return assigned.equals(attribution(evidence)) ? interval(assigned, evidence) : null;
    }

    static List<MonthlySubtotal> byMonth(List<Month> months, List<Event> events) {
        var result = new ArrayList<MonthlySubtotal>();
        for (Month month : months) {
            var groups = events.stream().filter(event -> month.contains(event.effectiveAt()))
                    .collect(Collectors.groupingBy(Event::attribution, LinkedHashMap::new, Collectors.toList()));
            groups.forEach((attribution, rows) -> result.add(subtotal(month.month(), attribution, rows)));
        }
        return List.copyOf(result);
    }

    public static MonthlySubtotal subtotal(String month, EventAttribution attribution, List<Event> events) {
        Map<String, BigDecimal> inflows = new LinkedHashMap<>();
        Map<String, BigDecimal> corrections = new LinkedHashMap<>();
        Map<String, WithdrawalSubtotal> withdrawals = new LinkedHashMap<>();
        BigDecimal performance = BigDecimal.ZERO;
        for (Event event : events) {
            switch (event.kind()) {
                case INFLOW -> inflows.merge(event.channel(), event.deltaUsdt(), BigDecimal::add);
                case CORRECTION -> {
                    inflows.merge(event.channel(), event.deltaUsdt(), BigDecimal::add);
                    corrections.merge(event.channel(), event.deltaUsdt(), BigDecimal::add);
                }
                case WITHDRAWAL -> withdrawals.merge(event.channel(), new WithdrawalSubtotal(event.amountUsdt(),
                        event.feeUsdt(), event.receivedUsdt(), event.vnd()), SupportPerformanceAggregation::add);
            }
            performance = performance.add(event.deltaUsdt());
        }
        return new MonthlySubtotal(month, attribution, Map.copyOf(inflows), Map.copyOf(corrections),
                Map.copyOf(withdrawals), performance);
    }

    private static WithdrawalSubtotal add(WithdrawalSubtotal left, WithdrawalSubtotal right) {
        return new WithdrawalSubtotal(left.walletDebitedUsdt().add(right.walletDebitedUsdt()),
                addIfKnown(left.feeUsdt(), right.feeUsdt()), addIfKnown(left.receivedUsdt(), right.receivedUsdt()),
                addIfKnown(left.receivedVnd(), right.receivedVnd()));
    }

    private static BigDecimal addIfKnown(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.add(right);
    }
}
