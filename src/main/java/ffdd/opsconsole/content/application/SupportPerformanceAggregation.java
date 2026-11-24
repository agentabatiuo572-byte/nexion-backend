package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportPerformance.AttributionState;
import ffdd.opsconsole.content.domain.SupportPerformance.EventAttribution;
import ffdd.opsconsole.content.domain.SupportPerformance.Month;
import ffdd.opsconsole.content.domain.SupportPerformance.MonthlySubtotal;
import ffdd.opsconsole.content.domain.SupportPerformance.ObservedDeposit;
import ffdd.opsconsole.content.domain.SupportPerformance.WithdrawalSubtotal;
import ffdd.opsconsole.finance.facade.SupportDirectPurchaseFactsFacade.Fact;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ConfirmedDepositCorrection;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ConfirmedWithdrawal;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Groups amounts from one published source generation without certifying source coverage. */
final class SupportPerformanceAggregation {
    private static final EventAttribution UNKNOWN = new EventAttribution(AttributionState.UNKNOWN, null, null);
    private static final Set<String> CHAIN_METHODS = Set.of("CREGIS_USDT_BSC", "CREGIS_USDT_ERC20", "CREGIS_USDT_TRC20");

    private SupportPerformanceAggregation() { }

    static List<MonthlySubtotal> summarize(List<Month> months, SupportPaymentFacts.Snapshot payments,
            List<ObservedDeposit> deposits, List<ConfirmedDepositCorrection> corrections,
            List<ConfirmedWithdrawal> withdrawals, List<Fact> purchases,
            SupportPerformanceDepositAttribution.Result attribution) {
        var eligibleDeposits = new HashMap<String, String>();
        var ambiguousDeposits = new HashSet<String>();
        for (var fact : payments.facts()) {
            if (fact.kind() != SupportPaymentFacts.Kind.DEPOSIT || !"USDT".equals(fact.currency())
                    || !java.util.Objects.equals(attribution.verifiedOriginals().get(fact.factId()), fact.customerId())) continue;
            String channel = switch (fact.source()) {
                case DEPOSIT_ORDER -> fact.sourceBusinessId().matches("CR-[0-9]+") ? "CHAIN_TOPUP" : null;
                case VIETQR, HDPAY -> "VN_BANK_TOPUP";
                default -> null;
            };
            if (channel != null && eligibleDeposits.putIfAbsent(fact.factId(), channel) != null) {
                ambiguousDeposits.add(fact.factId());
            }
        }
        ambiguousDeposits.forEach(eligibleDeposits::remove);

        var groups = new HashMap<String, Map<EventAttribution, Subtotals>>();
        Set<String> seen = new HashSet<>();
        for (var deposit : deposits) {
            String channel = eligibleDeposits.get(deposit.factId());
            if (channel == null || !java.util.Objects.equals(attribution.verifiedOriginals().get(deposit.factId()), deposit.customerId())
                    || !seen.add(deposit.factId())) continue;
            group(groups, months, deposit.creditedAt(), attribution.events().get(deposit.factId()))
                    .inflows.merge(channel, deposit.creditedUsdt(), BigDecimal::add);
        }
        for (var correction : corrections) {
            String channel = eligibleDeposits.get(correction.originalFactId());
            if (channel == null || !java.util.Objects.equals(attribution.verifiedOriginals().get(correction.originalFactId()), correction.customerId())
                    || !seen.add("CORRECTION:" + correction.operationNo())) continue;
            var subtotal = group(groups, months, correction.effectiveAt(), attribution.events().get(correction.operationNo()));
            subtotal.inflows.merge(channel, correction.signedUsdt(), BigDecimal::add);
            subtotal.corrections.merge(channel, correction.signedUsdt(), BigDecimal::add);
        }
        for (var withdrawal : withdrawals) {
            String channel = switch (withdrawal.rail()) {
                case "USDT-TRC20" -> "TRC20";
                case "USDT-BEP20" -> "BEP20";
                case "USDT-ERC20" -> "ERC20";
                case "BANK-VND" -> "VN_BANK";
                default -> null;
            };
            if (channel == null || !seen.add("WITHDRAWAL:" + withdrawal.withdrawalNo())) continue;
            var subtotal = group(groups, months, withdrawal.completedAt(), UNKNOWN);
            subtotal.withdrawals.merge(channel, new WithdrawalSubtotal(
                    withdrawal.walletDebitUsdt(), withdrawal.feeUsdt(),
                    withdrawal.receivedUsdt(), withdrawal.receivedVnd()), SupportPerformanceAggregation::add);
        }
        for (var purchase : purchases) {
            String channel = "HDPAY_BANK".equals(purchase.paymentMethod()) ? "VN_BANK_DIRECT_PURCHASE"
                    : CHAIN_METHODS.contains(purchase.paymentMethod()) ? "CHAIN_DIRECT_PURCHASE" : null;
            if (channel == null || !seen.add("DIRECT_PURCHASE:" + purchase.intentNo())) continue;
            group(groups, months, purchase.paidAt(), UNKNOWN)
                    .inflows.merge(channel, purchase.businessAmountUsdt(), BigDecimal::add);
        }

        var result = new ArrayList<MonthlySubtotal>();
        for (Month month : months) {
            for (var entry : groups.getOrDefault(month.month(), Map.of()).entrySet()) {
                var subtotal = entry.getValue();
                BigDecimal inflow = subtotal.inflows.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal debit = subtotal.withdrawals.values().stream().map(WithdrawalSubtotal::walletDebitedUsdt)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                result.add(new MonthlySubtotal(month.month(), entry.getKey(), Map.copyOf(subtotal.inflows),
                        Map.copyOf(subtotal.corrections), Map.copyOf(subtotal.withdrawals), inflow.subtract(debit)));
            }
        }
        return List.copyOf(result);
    }

    private static Subtotals group(Map<String, Map<EventAttribution, Subtotals>> groups, List<Month> months,
            Instant at, EventAttribution attribution) {
        var month = months.stream().filter(m -> m.contains(at)).findFirst().orElseThrow();
        var key = attribution == null || attribution.state() == AttributionState.UNKNOWN ? UNKNOWN : attribution;
        return groups.computeIfAbsent(month.month(), ignored -> new LinkedHashMap<>())
                .computeIfAbsent(key, ignored -> new Subtotals());
    }

    private static WithdrawalSubtotal add(WithdrawalSubtotal left, WithdrawalSubtotal right) {
        return new WithdrawalSubtotal(left.walletDebitedUsdt().add(right.walletDebitedUsdt()),
                addIfKnown(left.feeUsdt(), right.feeUsdt()), addIfKnown(left.receivedUsdt(), right.receivedUsdt()),
                addIfKnown(left.receivedVnd(), right.receivedVnd()));
    }

    private static BigDecimal addIfKnown(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.add(right);
    }

    private static final class Subtotals {
        final Map<String, BigDecimal> inflows = new HashMap<>();
        final Map<String, BigDecimal> corrections = new HashMap<>();
        final Map<String, WithdrawalSubtotal> withdrawals = new HashMap<>();
    }
}
