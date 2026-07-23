package ffdd.opsconsole.bi.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * L4 read model built only from schema-accepted A4 facts. It deliberately keeps
 * incomplete denominators as null instead of manufacturing operational rates.
 */
public final class L4OperationsAnalytics {
    private static final List<String> PHASES = List.of("P1", "P2", "P3", "P4", "P5", "P6");
    private static final Set<String> L4_EVENTS = Set.of(
            "device.purchase_completed", "device.first_yield_received", "device.locked", "device.retired",
            "earnings.credited", "quest.dispatched", "quest.completed", "daily.checkin",
            "referral.bound", "referral.invite_sent", "commission.paid", "checkout.completed",
            "auth.register_completed", "store.viewed", "app.dau", "phase.transitioned", "phase.dial_changed");
    private static final Set<String> ACTOR_EVENTS = Set.of(
            "device.purchase_completed", "quest.dispatched", "quest.completed", "daily.checkin",
            "referral.bound", "referral.invite_sent", "commission.paid", "checkout.completed",
            "auth.register_completed", "store.viewed", "app.dau");

    private L4OperationsAnalytics() {
    }

    public static Map<String, Object> calculate(
            List<Map<String, Object>> source, String period, String phase, String from, String to) {
        return calculate(source, period, phase, from, to, LocalDateTime.now());
    }

    static Map<String, Object> calculate(
            List<Map<String, Object>> source, String period, String phase, String from, String to,
            LocalDateTime now) {
        String normalizedPeriod = normalizePeriod(period);
        String normalizedPhase = normalizePhase(phase);
        Range range = range(normalizedPeriod, from, to, now);
        List<Fact> all = source == null ? List.of() : source.stream()
                .map(L4OperationsAnalytics::fact)
                .filter(item -> item != null && L4_EVENTS.contains(item.event()))
                .sorted(Comparator.comparing(Fact::ts))
                .toList();
        List<Fact> selected = all.stream()
                .filter(item -> !item.ts().isBefore(range.from()) && !item.ts().isAfter(range.to()))
                .filter(item -> "ALL".equals(normalizedPhase) || normalizedPhase.equals(item.phase()))
                .toList();

        long actorRequired = selected.stream().filter(item -> ACTOR_EVENTS.contains(item.event())).count();
        long actorPresent = selected.stream()
                .filter(item -> ACTOR_EVENTS.contains(item.event()) && hasText(item.actor()))
                .count();
        boolean actorComplete = actorRequired == actorPresent;
        double actorCoverage = actorRequired == 0 ? 100.0 : pct(actorPresent, actorRequired);

        Map<String, Object> result = linked(
                "module", "L4",
                "available", !selected.isEmpty(),
                "period", linked(
                        "key", normalizedPeriod,
                        "label", periodLabel(normalizedPeriod),
                        "from", range.from().toLocalDate().toString(),
                        "to", range.to().toLocalDate().toString()),
                "phaseFilter", normalizedPhase,
                "device", device(selected),
                "tasks", tasks(selected, actorComplete),
                "network", network(selected, actorComplete),
                "phaseEffect", phaseEffect(selected, actorComplete),
                "history", history(selected, normalizedPeriod),
                "quality", linked(
                        "serverCanonical", true,
                        "sameActorRates", actorComplete,
                        "actorCoveragePct", actorCoverage,
                        "incompleteRatesAreNull", true,
                        "eventCount", selected.size()),
                "capabilities", linked(
                        "currentSnapshot", true,
                        "periodSlicing", true,
                        "customPeriod", true,
                        "phaseEffectHistory", true,
                        "networkTreeExport", true),
                "sources", List.of("A4 schema-accepted operational event stream", "E device/task facts", "F network facts", "H1 phase facts"));
        if (selected.isEmpty()) {
            result.put("degraded", linked(
                    "code", "L4_HISTORY_NOT_AVAILABLE",
                    "message", "所选周期没有可核验的运营事件，未生成比率或趋势。"));
        }
        return result;
    }

    private static Map<String, Object> device(List<Fact> rows) {
        List<Fact> purchases = events(rows, "device.purchase_completed");
        List<Fact> earnings = events(rows, "earnings.credited");
        long purchased = distinctKey(purchases, Fact::deviceId);
        long retired = distinctKey(events(rows, "device.retired"), Fact::deviceId);
        long locked = distinctKey(events(rows, "device.locked"), Fact::deviceId);
        BigDecimal yieldUsdt = sum(earnings, Fact::amountUsdt);
        BigDecimal yieldNex = sum(earnings, Fact::amountNex);
        BigDecimal degradationLoss = earnings.stream()
                .map(item -> positive(item.baselineUsdt().subtract(item.amountUsdt())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return linked(
                "summary", linked(
                        "periodPurchasedDevices", purchased,
                        "periodRetiredDevices", retired,
                        "periodLockedDevices", locked,
                        "periodFirstYieldDevices", distinctKey(events(rows, "device.first_yield_received"), Fact::deviceId),
                        "dailyYieldUsdt", decimal(yieldUsdt),
                        "dailyYieldNex", decimal(yieldNex),
                        "degradationLossUsdt", decimal(degradationLoss)),
                "byGeneration", distribution(purchases, Fact::generation),
                "byModel", distribution(purchases, Fact::model),
                "degradation", degradation(earnings));
    }

    private static Map<String, Object> tasks(List<Fact> rows, boolean actorComplete) {
        List<Fact> dispatched = events(rows, "quest.dispatched");
        List<Fact> completed = events(rows, "quest.completed");
        boolean keysComplete = merge(dispatched, completed).stream()
                .allMatch(item -> hasText(item.actor()) && hasText(item.questKey()));
        Map<String, LocalDateTime> dispatchTimes = firstTimes(dispatched, L4OperationsAnalytics::taskKey);
        Map<String, Fact> completedTasks = new LinkedHashMap<>();
        if (keysComplete) {
            for (Fact item : completed) {
                String key = taskKey(item);
                LocalDateTime dispatchedAt = dispatchTimes.get(key);
                if (dispatchedAt != null && item.ts().isAfter(dispatchedAt)) completedTasks.putIfAbsent(key, item);
            }
        }
        long dispatchedCount = keysComplete ? dispatchTimes.size() : distinctKey(dispatched, Fact::eventId);
        long completedCount = keysComplete ? completedTasks.size() : distinctKey(completed, Fact::eventId);
        Double saturation = rows.stream()
                .filter(item -> item.queueSaturation() != null)
                .max(Comparator.comparing(Fact::ts))
                .map(Fact::queueSaturation)
                .orElse(null);
        return linked(
                "summary", linked(
                        "dispatched", dispatchedCount,
                        "completed", completedCount,
                        "acceptanceRate", actorComplete && keysComplete && dispatchedCount > 0 ? pct(completedCount, dispatchedCount) : null,
                        "queueSaturation", saturation == null ? null : round(saturation),
                        "checkinActive", distinctActors(events(rows, "daily.checkin")),
                        "orderedTaskJoin", actorComplete && keysComplete),
                "byTier", distribution(keysComplete ? new ArrayList<>(completedTasks.values()) : completed, Fact::tier));
    }

    private static Map<String, Object> network(List<Fact> rows, boolean actorComplete) {
        List<Fact> holders = events(rows, "device.purchase_completed");
        List<Fact> invites = events(rows, "referral.invite_sent");
        List<Fact> referrals = events(rows, "referral.bound");
        List<Fact> commissions = events(rows, "commission.paid");
        Set<String> referredActors = actorSet(referrals);
        long triggered = orderedActors(referrals, commissions).size();
        long holderActors = distinctActors(holders);
        long inviteActors = orderedActors(holders, invites).size();
        Set<String> referredBeforeCheckout = orderedActors(referrals, events(rows, "checkout.completed"));
        BigDecimal gmv = events(rows, "checkout.completed").stream()
                .filter(item -> referredBeforeCheckout.contains(item.actor()))
                .map(Fact::amountUsdt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return linked(
                "summary", linked(
                        "directRefs", referredActors.size(),
                        "commissionEvents", commissions.size(),
                        "commissionPaidUsdt", decimal(sum(commissions, Fact::amountUsdt)),
                        "teamGmvUsdt", decimal(gmv),
                        "promotionRate", actorComplete && holderActors > 0 ? pct(inviteActors, holderActors) : null,
                        "commissionTriggerRate", actorComplete && !referredActors.isEmpty() ? pct(triggered, referredActors.size()) : null),
                "teamSizeDist", numericDistribution(referrals, Fact::teamSize),
                "vRankDist", distribution(merge(referrals, commissions), Fact::vRank),
                "commissionStructure", distribution(commissions, Fact::tier));
    }

    private static List<Map<String, Object>> phaseEffect(List<Fact> rows, boolean actorComplete) {
        List<Map<String, Object>> result = new ArrayList<>();
        Double previousConversion = null;
        for (String phase : PHASES) {
            List<Fact> phaseRows = rows.stream().filter(item -> phase.equals(item.phase())).toList();
            List<Fact> registrations = events(phaseRows, "auth.register_completed");
            List<Fact> activeFacts = events(phaseRows, "app.dau");
            List<Fact> storeFacts = events(phaseRows, "store.viewed");
            List<Fact> buyerFacts = events(phaseRows, "checkout.completed");
            long registered = distinctActors(registrations);
            long active = distinctActors(events(phaseRows, "app.dau"));
            long store = distinctActors(storeFacts);
            long retained = orderedActors(registrations, activeFacts).size();
            long converted = orderedActors(storeFacts, buyerFacts).size();
            Double retention = actorComplete && registered > 0 ? pct(retained, registered) : null;
            Double conversion = actorComplete && store > 0 ? pct(converted, store) : null;
            Double step = conversion == null || previousConversion == null ? null : round(conversion - previousConversion);
            if (conversion != null) previousConversion = conversion;
            result.add(linked(
                    "phase", phase,
                    "activeUsers", active,
                    "retentionRate", retention,
                    "conversionRate", conversion,
                    "yieldUsdt", decimal(sum(events(phaseRows, "earnings.credited"), Fact::amountUsdt)),
                    "transitionCount", events(phaseRows, "phase.transitioned").size(),
                    "dialChangeCount", events(phaseRows, "phase.dial_changed").size(),
                    "conversionStepPct", step));
        }
        return result;
    }

    private static List<Map<String, Object>> history(List<Fact> rows, String period) {
        Map<String, List<Fact>> buckets = new TreeMap<>();
        for (Fact row : rows) {
            String key = bucket(row.ts().toLocalDate(), period);
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        buckets.forEach((key, facts) -> result.add(linked(
                "bucket", key,
                "devicePurchases", events(facts, "device.purchase_completed").size(),
                "deviceRetirements", events(facts, "device.retired").size(),
                "yieldUsdt", decimal(sum(events(facts, "earnings.credited"), Fact::amountUsdt)),
                "tasksCompleted", events(facts, "quest.completed").size(),
                "directRefs", events(facts, "referral.bound").size(),
                "commissionPaidUsdt", decimal(sum(events(facts, "commission.paid"), Fact::amountUsdt)))));
        return result;
    }

    private static String bucket(LocalDate date, String period) {
        if ("month".equals(period) || "custom".equals(period)) {
            LocalDate monday = date.minusDays(date.getDayOfWeek().getValue() - 1L);
            return monday.toString();
        }
        return date.toString();
    }

    private static List<Map<String, Object>> degradation(List<Fact> rows) {
        Map<String, List<Fact>> grouped = new TreeMap<>();
        for (Fact row : rows) {
            String key = row.degradationRate() == null ? "未标注" : round(row.degradationRate()) + "%";
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        grouped.forEach((key, values) -> result.add(linked(
                "band", key,
                "events", values.size(),
                "actualUsdt", decimal(sum(values, Fact::amountUsdt)),
                "lossUsdt", decimal(values.stream()
                        .map(item -> positive(item.baselineUsdt().subtract(item.amountUsdt())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)))));
        return result;
    }

    private static List<Map<String, Object>> distribution(List<Fact> rows, TextValue value) {
        Map<String, Long> grouped = new TreeMap<>();
        for (Fact row : rows) {
            String key = hasText(value.apply(row)) ? value.apply(row).trim() : "未标注";
            grouped.merge(key, 1L, Long::sum);
        }
        return grouped.entrySet().stream()
                .map(entry -> linked("key", entry.getKey(), "count", entry.getValue()))
                .toList();
    }

    private static List<Map<String, Object>> numericDistribution(List<Fact> rows, NumericValue value) {
        Map<String, Long> grouped = new TreeMap<>();
        for (Fact row : rows) {
            Double number = value.apply(row);
            String key = number == null ? "未标注" : number < 10 ? "1-9" : number < 50 ? "10-49" : number < 200 ? "50-199" : "200+";
            grouped.merge(key, 1L, Long::sum);
        }
        return grouped.entrySet().stream().map(entry -> linked("key", entry.getKey(), "count", entry.getValue())).toList();
    }

    private static List<Fact> merge(List<Fact> first, List<Fact> second) {
        List<Fact> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static List<Fact> events(List<Fact> rows, String event) {
        return rows.stream().filter(item -> event.equals(item.event())).toList();
    }

    private static long distinctActors(List<Fact> rows) {
        return actorSet(rows).size();
    }

    private static Set<String> actorSet(List<Fact> rows) {
        Set<String> result = new LinkedHashSet<>();
        rows.stream().map(Fact::actor).filter(L4OperationsAnalytics::hasText).forEach(result::add);
        return result;
    }

    private static long distinctKey(List<Fact> rows, TextValue value) {
        Set<String> result = new LinkedHashSet<>();
        rows.forEach(item -> result.add(hasText(value.apply(item)) ? value.apply(item) : item.eventId()));
        return result.size();
    }

    private static String taskKey(Fact fact) {
        return fact.actor() + "|" + fact.questKey();
    }

    private static Map<String, LocalDateTime> firstTimes(List<Fact> rows, TextValue keyValue) {
        Map<String, LocalDateTime> result = new LinkedHashMap<>();
        for (Fact item : rows) {
            String key = keyValue.apply(item);
            if (!hasText(key)) continue;
            result.merge(key, item.ts(), (left, right) -> left.isBefore(right) ? left : right);
        }
        return result;
    }

    private static Set<String> orderedActors(List<Fact> prerequisite, List<Fact> consequence) {
        Map<String, LocalDateTime> starts = firstTimes(prerequisite, Fact::actor);
        Set<String> result = new LinkedHashSet<>();
        for (Fact item : consequence) {
            LocalDateTime start = starts.get(item.actor());
            if (start != null && item.ts().isAfter(start)) result.add(item.actor());
        }
        return result;
    }

    private static BigDecimal sum(List<Fact> rows, DecimalValue value) {
        return rows.stream().map(value::apply).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Fact fact(Map<String, Object> row) {
        if (row == null) return null;
        LocalDateTime ts = timestamp(row.get("eventTs"));
        if (ts == null) return null;
        return new Fact(
                text(row.get("eventName")), ts, text(row.get("eventId")), text(row.get("actorId")),
                upper(row.get("phase"), "UNASSIGNED"), text(row.get("cohort")), text(row.get("locale")),
                text(row.get("refCode")), text(row.get("deviceId")), text(row.get("model")),
                text(row.get("generation")), text(row.get("status")), text(row.get("tier")),
                text(row.get("questKey")), text(row.get("relationshipKey")),
                text(row.get("vRank")), number(row.get("teamSize")), money(row.get("amountUsdt")),
                money(row.get("amountNex")), money(row.get("baselineUsdt")),
                number(row.get("degradationRate")), number(row.get("queueSaturation")));
    }

    private static LocalDateTime timestamp(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value == null) return null;
        try {
            return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Range range(String period, String from, String to, LocalDateTime now) {
        if ("custom".equals(period)) {
            LocalDate start = parseDate(from, now.toLocalDate().minusDays(29));
            LocalDate end = parseDate(to, now.toLocalDate());
            if (start.isAfter(end)) {
                LocalDate swap = start;
                start = end;
                end = swap;
            }
            if (ChronoUnit.DAYS.between(start, end) > 399) start = end.minusDays(399);
            return new Range(start.atStartOfDay(), end.atTime(LocalTime.MAX));
        }
        long days = "day".equals(period) ? 1 : "month".equals(period) ? 30 : 7;
        return new Range(now.minusDays(days), now);
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        try {
            return hasText(value) ? LocalDate.parse(value.trim()) : fallback;
        } catch (DateTimeParseException ignored) {
            return fallback;
        }
    }

    private static String normalizePeriod(String value) {
        String normalized = text(value).toLowerCase(Locale.ROOT);
        return Set.of("day", "week", "month", "custom").contains(normalized) ? normalized : "week";
    }

    private static String normalizePhase(String value) {
        String normalized = upper(value, "ALL");
        return PHASES.contains(normalized) ? normalized : "ALL";
    }

    private static String periodLabel(String period) {
        return switch (period) {
            case "day" -> "近 24 小时";
            case "month" -> "近 30 天";
            case "custom" -> "自定义周期";
            default -> "近 7 天";
        };
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String upper(Object value, String fallback) {
        String text = text(value);
        return text.isEmpty() ? fallback : text.toUpperCase(Locale.ROOT);
    }

    private static BigDecimal money(Object value) {
        try {
            return value == null || String.valueOf(value).isBlank() ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static Double number(Object value) {
        try {
            if (value == null || String.valueOf(value).isBlank()) return null;
            double number = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(number) ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static BigDecimal positive(BigDecimal value) {
        return value.signum() > 0 ? value : BigDecimal.ZERO;
    }

    private static double decimal(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().doubleValue();
    }

    private static double pct(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : round(numerator * 100.0 / denominator);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private record Range(LocalDateTime from, LocalDateTime to) {
    }

    private record Fact(
            String event, LocalDateTime ts, String eventId, String actor, String phase, String cohort,
            String locale, String refCode, String deviceId, String model, String generation, String status,
            String tier, String questKey, String relationshipKey, String vRank, Double teamSize, BigDecimal amountUsdt, BigDecimal amountNex,
            BigDecimal baselineUsdt, Double degradationRate, Double queueSaturation) {
    }

    @FunctionalInterface
    private interface TextValue {
        String apply(Fact fact);
    }

    @FunctionalInterface
    private interface NumericValue {
        Double apply(Fact fact);
    }

    @FunctionalInterface
    private interface DecimalValue {
        BigDecimal apply(Fact fact);
    }
}
