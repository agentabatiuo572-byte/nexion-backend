package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.config.VietnamBusinessPeriod;
import ffdd.opsconsole.team.mapper.BinaryPlatformSettlementMapper;
import ffdd.opsconsole.team.mapper.BinaryPlatformSettlementMapper.Allocation;
import ffdd.opsconsole.team.mapper.BinaryPlatformSettlementMapper.Period;
import ffdd.opsconsole.team.mapper.BinarySettlementScheduleMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.LongFunction;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** The first successful settlement freezes the same platform budget for both entry points. */
@Component
class BinaryPlatformSettlementPlan {
    private static final int PAGE_SIZE = 200;
    private final BinaryPlatformSettlementMapper periods;
    private final BinarySettlementScheduleMapper schedule;
    private final TransactionTemplate quoteTransaction;

    BinaryPlatformSettlementPlan(BinaryPlatformSettlementMapper periods, BinarySettlementScheduleMapper schedule,
                                 PlatformTransactionManager transactionManager) {
        this.periods = periods;
        this.schedule = schedule;
        this.quoteTransaction = new TransactionTemplate(transactionManager);
        this.quoteTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.quoteTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    ProposedPlan quote(LocalDate date, int periodDays, BigDecimal capPct, long firstOwner,
                       BigDecimal firstAmount, LongFunction<BigDecimal> quoteOwner) {
        LocalDate start = date.minusDays(periodDays - 1L);
        LocalDate previousEnd = periods.previousEnd(date);
        if (previousEnd != null && !previousEnd.isBefore(start)) start = previousEnd.plusDays(1);
        BigDecimal paid = periods.paidSales(VietnamBusinessPeriod.storageBoundary(start),
                VietnamBusinessPeriod.storageBoundary(date.plusDays(1)));
        BigDecimal cap = paid.multiply(capPct).movePointLeft(2).setScale(6, RoundingMode.DOWN);
        NavigableMap<Long, BigDecimal> amounts = new TreeMap<>();
        amounts.put(firstOwner, firstAmount);
        long cursor = 0;
        List<Long> owners;
        do {
            owners = schedule.listOwners(cursor, PAGE_SIZE);
            for (long owner : owners) {
                cursor = owner;
                if (owner == firstOwner) continue;
                // Roll back preview-only placement and volume captures. A failed read cannot
                // be treated as a zero quote when computing everyone else's proportional share.
                BigDecimal raw = quoteTransaction.execute(status -> {
                    status.setRollbackOnly();
                    return quoteOwner.apply(owner);
                });
                if (raw.signum() > 0) amounts.put(owner, raw);
            }
        } while (owners.size() == PAGE_SIZE);
        BigDecimal total = amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budget = cap.min(total);
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal allocated = BigDecimal.ZERO;
        List<Allocation> allocations = new ArrayList<>(amounts.size());
        for (var entry : amounts.entrySet()) {
            cumulative = cumulative.add(entry.getValue());
            // Allocate differences between rounded cumulative shares. The last cumulative
            // share is the exact budget, including the final micro-USDT.
            BigDecimal next = budget.multiply(cumulative).divide(total, 6, RoundingMode.DOWN);
            allocations.add(new Allocation(entry.getKey(), entry.getValue(), next.subtract(allocated)));
            allocated = next;
        }
        return new ProposedPlan(new Period(date, start, periodDays, capPct, paid, cap, total), allocations, budget);
    }

    void freeze(ProposedPlan plan) {
        if (periods.insertPeriod(plan.period()) != 1) throw new IllegalStateException("F3_PLATFORM_PERIOD_INSERT_FAILED");
        var rows = plan.allocations();
        for (int start = 0; start < rows.size(); start += PAGE_SIZE) {
            List<Allocation> batch = rows.subList(start, Math.min(start + PAGE_SIZE, rows.size()));
            if (periods.insertAllocations(plan.period().settlementDate(), batch) != batch.size()) {
                throw new IllegalStateException("F3_PLATFORM_ALLOCATION_INSERT_FAILED");
            }
        }
    }

    record ProposedPlan(Period period, List<Allocation> allocations, BigDecimal budget) {
        Allocation forOwner(long owner) {
            return allocations.stream().filter(row -> row.userId() == owner).findFirst().orElseThrow();
        }
    }
}
