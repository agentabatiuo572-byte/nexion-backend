package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.content.domain.SupportPerformance.*;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper.Evidence;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper.Point;
import ffdd.opsconsole.finance.facade.SupportDirectPurchaseFactsFacade.Fact;
import ffdd.opsconsole.finance.facade.SupportDirectPurchaseFactsFacade.Refund;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade.WalletReference;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ConfirmedDepositCorrection;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ConfirmedWithdrawal;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.WithdrawalReference;
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
            List<ConfirmedWithdrawal> withdrawals, List<WithdrawalReference> withdrawalReferences,
            List<Fact> purchases, List<Refund> refunds, List<WalletReference> walletReferences, Instant from, Instant to,
            SupportPerformanceDepositAttribution.Result attribution, SupportPerformanceHistoryMapper history) {
        var originals = new HashMap<String, SupportPaymentFacts.Fact>();
        for (var fact : payments.facts()) {
            if (fact.kind() == SupportPaymentFacts.Kind.DEPOSIT && SupportPerformance.CURRENCY.equals(fact.currency())
                    && Objects.equals(attribution.verifiedOriginals().get(fact.factId()), fact.customerId())
                    && depositChannel(fact) != null) {
                originals.put(fact.factId(), fact);
            }
        }
        var zone = ZoneId.of(payments.businessZone());
        var purchaseOrders = purchases.stream().map(Fact::orderNo).collect(Collectors.toSet());
        var references = payments.facts().stream().filter(f -> SupportPerformance.CURRENCY.equals(f.currency())
                && (f.kind() == SupportPaymentFacts.Kind.DEVICE_PURCHASE_REFUND
                    || f.kind() == SupportPaymentFacts.Kind.DEVICE_PURCHASE && !purchaseOrders.contains(f.orderNo()))
                && SupportPerformance.contains(from, to, f.succeededAt().atZone(zone).toInstant())).toList();
        Map<String, List<Evidence>> historical = history(originals.values(), zone,
                withdrawals, withdrawalReferences, purchases, references, walletReferences, history);
        Map<String, Event> events = new LinkedHashMap<>();
        for (var deposit : deposits) {
            var original = originals.get(deposit.factId());
            if (original == null || original.customerId() != deposit.customerId()) continue;
            var assigned = normalized(attribution.events().get(deposit.factId()));
            events.putIfAbsent(deposit.factId(), new Event(deposit.factId(), deposit.customerId(), assigned,
                    archivedInterval(assigned, historical.get(deposit.factId())),
                    Kind.INFLOW, depositChannel(original), null, false, deposit.sourceBusinessId(), null, null,
                    deposit.creditedAt(), deposit.creditedAt(), "CREDITED", deposit.creditedUsdt(),
                    deposit.creditedUsdt(), null, null, null, null, null, deposit.creditedUsdt(), deposit.receivedVnd()));
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
            String network = withdrawalNetwork(withdrawal.rail());
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
        for (var reference : withdrawalReferences) {
            if (reference.effectiveAt() == null || events.containsKey("WITHDRAWAL:" + reference.withdrawalNo())) continue;
            String network = withdrawalNetwork(reference.rail());
            String channel = "BANK-VND".equals(reference.rail()) ? "VN_BANK" : network;
            if (channel == null) continue;
            String id = "WITHDRAWAL_REFERENCE:" + reference.withdrawalNo();
            Evidence evidence = unique(historical.get(id));
            var assigned = attribution(evidence);
            events.putIfAbsent(id, new Event(id, reference.customerId(), assigned, interval(assigned, evidence),
                    Kind.WITHDRAWAL_REFERENCE, channel, network, false, reference.withdrawalNo(), null, null,
                    reference.requestedAt(), reference.effectiveAt(), reference.state(), reference.requestedUsdt(),
                    BigDecimal.ZERO, null, null, null, null, null, null, null));
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
        for (var refund : refunds) {
            var original = events.get("DIRECT_PURCHASE:" + refund.orderNo());
            if (original == null || original.customerId() != refund.customerId()
                    || !SupportPerformance.contains(from, to, original.effectiveAt())
                    || !SupportPerformance.contains(from, to, refund.confirmedAt())) continue;
            String id = "DIRECT_REFUND:" + refund.intentNo();
            events.putIfAbsent(id, new Event(id, refund.customerId(), original.attribution(), original.bindingPeriod(),
                    Kind.CORRECTION, original.channel(), original.network(), false, refund.intentNo(), original.businessNo(),
                    original.id(), refund.confirmedAt(), refund.confirmedAt(), "EXTERNAL_REFUND_CONFIRMED",
                    refund.businessAmountUsdt(), refund.businessAmountUsdt().negate(), null, null, null, null, null,
                    null, "VND".equals(refund.currency()) ? refund.originalCurrencyAmount() : null));
        }
        for (var reference : references) {
            boolean purchase = reference.kind() == SupportPaymentFacts.Kind.DEVICE_PURCHASE;
            Instant at = reference.succeededAt().atZone(zone).toInstant();
            var evidence = unique(historical.get(reference.factId()));
            var assigned = attribution(evidence);
            events.putIfAbsent(reference.factId(), new Event(reference.factId(), reference.customerId(), assigned,
                    interval(assigned, evidence), Kind.REFERENCE, null, null, purchase, reference.sourceBusinessId(),
                    purchase ? null : reference.orderNo(), reference.originalFactId(), at, at,
                    purchase ? "DEVICE_PURCHASE" : "REFUND", reference.amount(), BigDecimal.ZERO,
                    null, null, null, null, null, null, null));
        }
        for (var reference : walletReferences) {
            String id = "WALLET_REFERENCE:" + reference.ledgerId();
            var evidence = unique(historical.get(id));
            var assigned = attribution(evidence);
            events.putIfAbsent(id, new Event(id, reference.customerId(), assigned, interval(assigned, evidence),
                    Kind.REFERENCE, null, null, false, reference.businessNo(), null, null, reference.at(), reference.at(),
                    reference.kind(), reference.amount(), BigDecimal.ZERO, null, null, null, null, null, null, null));
        }
        return events.values().stream().filter(event -> SupportPerformance.contains(from, to, event.effectiveAt()))
                .sorted(Comparator.comparing(Event::effectiveAt).thenComparing(Event::id)).toList();
    }

    private static String withdrawalNetwork(String rail) {
        return switch (rail) {
            case "USDT-TRC20" -> "TRC20";
            case "USDT-BEP20" -> "BEP20";
            case "USDT-ERC20" -> "ERC20";
            default -> null;
        };
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
            ZoneId paymentZone, List<ConfirmedWithdrawal> withdrawals, List<WithdrawalReference> withdrawalReferences,
            List<Fact> purchases, List<SupportPaymentFacts.Fact> references, List<WalletReference> walletReferences,
            SupportPerformanceHistoryMapper mapper) {
        var points = new LinkedHashMap<String, Point>();
        for (var deposit : deposits) {
            String id = deposit.factId();
            points.putIfAbsent(id, new Point(id, deposit.customerId(), utc(deposit.succeededAt().atZone(paymentZone).toInstant())));
        }
        for (var withdrawal : withdrawals) {
            String id = withdrawalKey(withdrawal);
            points.putIfAbsent(id, new Point(id, withdrawal.customerId(), utc(withdrawal.completedAt())));
        }
        for (var reference : withdrawalReferences) {
            if (reference.effectiveAt() != null) {
                String id = "WITHDRAWAL_REFERENCE:" + reference.withdrawalNo();
                points.putIfAbsent(id, new Point(id, reference.customerId(), utc(reference.effectiveAt())));
            }
        }
        for (var reference : references) {
            points.putIfAbsent(reference.factId(), new Point(reference.factId(), reference.customerId(),
                    utc(reference.succeededAt().atZone(paymentZone).toInstant())));
        }
        for (var reference : walletReferences) {
            String id = "WALLET_REFERENCE:" + reference.ledgerId();
            points.putIfAbsent(id, new Point(id, reference.customerId(), utc(reference.at())));
        }
        for (var purchase : purchases) {
            String id = purchaseKey(purchase);
            points.putIfAbsent(id, new Point(id, purchase.customerId(), utc(purchase.paidAt())));
        }
        return history(List.copyOf(points.values()), mapper);
    }

    private static Map<String, List<Evidence>> history(List<Point> batch, SupportPerformanceHistoryMapper mapper) {
        Map<String, List<Evidence>> result = new HashMap<>();
        for (int offset = 0; offset < batch.size(); offset += 200) {
            mapper.atEvents(batch.subList(offset, Math.min(offset + 200, batch.size()))).forEach(row ->
                    result.computeIfAbsent(row.eventId(), unused -> new ArrayList<>()).add(row));
        }
        return result;
    }

    static List<FirstPayment> firstPayments(SupportPerformanceFirstPaymentService.Snapshot first,
            SupportPerformanceHistoryMapper mapper) {
        var payments = first.observedFirst().values().stream().sorted(Comparator
                .comparingLong(SupportPerformanceFirstPaymentService.ObservedPayment::customerId)).toList();
        var points = payments.stream().map(payment -> new Point(payment.factId(), payment.customerId(),
                utc(payment.paidAt()))).toList();
        var history = history(points, mapper);
        var ready = first.legacyHistory().stream().filter(h -> h.status() == SupportPaymentFacts.Status.READY)
                .map(SupportPaymentFacts.FirstHistory::customerId).collect(Collectors.toSet());
        var gaps = first.directIssues().stream().filter(issue -> !"EXTERNAL_REFUND_UNVERIFIED".equals(issue.reason()))
                .map(issue -> issue.customerId()).collect(Collectors.toSet());
        return payments.stream().map(payment -> {
            String channel = switch (payment.source()) {
                case "DEPOSIT_ORDER" -> payment.evidenceId().matches("CR-[0-9]+") ? "CHAIN_TOPUP" : null;
                case "VIETQR", "HDPAY" -> "VN_BANK_TOPUP";
                case "CREGIS_USDT_BSC", "CREGIS_USDT_ERC20", "CREGIS_USDT_TRC20" -> "CHAIN_DIRECT_PURCHASE";
                case "HDPAY_BANK" -> "VN_BANK_DIRECT_PURCHASE";
                default -> null;
            };
            return new FirstPayment(payment.customerId(), payment.factId(), payment.orderNo(), payment.kind().name(),
                    channel, payment.currency(), payment.amount(), payment.paidAt(),
                    attribution(unique(history.get(payment.factId()))),
                    ready.contains(payment.customerId()) && !gaps.contains(payment.customerId()));
        }).toList();
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
                case WITHDRAWAL_REFERENCE, REFERENCE -> { }
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
