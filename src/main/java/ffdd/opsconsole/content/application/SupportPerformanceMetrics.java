package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.content.domain.SupportPerformance.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Shared counts for the authorized monthly fact set. */
public final class SupportPerformanceMetrics {
    private SupportPerformanceMetrics() { }

    public static Map<String, Long> counts(Snapshot snapshot, Instant from, Instant to, List<Event> events,
                                           Predicate<EventAttribution> scope) {
        var firstBinding = new HashMap<Long, Instant>();
        var bound = new java.util.HashSet<Long>();
        for (var binding : snapshot.bindings()) {
            if (!scope.test(binding.attribution()) || !binding.from().isBefore(to)) continue;
            firstBinding.merge(binding.customerId(), binding.from(), (left, right) -> left.isBefore(right) ? left : right);
            if (binding.to() == null || !binding.to().isBefore(to)) bound.add(binding.customerId());
        }
        var balance = new HashMap<Long, BigDecimal>();
        for (var event : events) {
            if (event.kind() == Kind.INFLOW || event.kind() == Kind.CORRECTION || event.kind() == Kind.WITHDRAWAL)
                balance.merge(event.customerId(), event.deltaUsdt(), BigDecimal::add);
        }
        var counts = new LinkedHashMap<String, Long>();
        counts.put("boundAtCutoff", (long) bound.size());
        counts.put("newlyBound", firstBinding.values().stream().filter(at -> !at.isBefore(from)).count());
        counts.put("withInflow", distinct(events, Kind.INFLOW));
        counts.put("withWithdrawal", distinct(events, Kind.WITHDRAWAL));
        counts.put("firstPayment", snapshot.firstPayments().stream()
                .filter(first -> first.confirmed() && "USDT".equals(first.currency()) && scope.test(first.attribution())
                        && SupportPerformance.contains(from, to, first.at())).count());
        counts.put("negativePerformance", balance.values().stream().filter(amount -> amount.signum() < 0).count());
        return counts;
    }

    private static long distinct(List<Event> events, Kind kind) {
        return events.stream().filter(event -> event.kind() == kind).mapToLong(Event::customerId).distinct().count();
    }
}
