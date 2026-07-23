package ffdd.opsconsole.bi.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * L2 read-model calculator. It consumes only schema-accepted A4 event facts and
 * keeps every conversion numerator inside the previous stage's same-user set.
 */
public final class L2FunnelAnalytics {
    private static final List<String> MAIN_EVENTS = List.of(
            "auth.register_completed",
            "kyc.express_verified",
            "checkout.completed",
            "wallet.reinvest",
            "withdraw.submitted");
    private static final List<Integer> CURVE_DAYS = List.of(0, 1, 3, 7, 14, 21, 30, 60);

    private L2FunnelAnalytics() {
    }

    public static Map<String, Object> calculate(List<Map<String, Object>> rawRows) {
        long totalRelevant = rawRows == null ? 0 : rawRows.stream()
                .filter(row -> MAIN_EVENTS.contains(text(row, "eventName", "event_name")))
                .count();
        List<EventFact> events = rawRows == null ? List.of() : rawRows.stream()
                .map(L2FunnelAnalytics::eventFact)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(EventFact::at))
                .toList();
        long identifiedRelevant = events.stream().filter(event -> MAIN_EVENTS.contains(event.name())).count();
        if (identifiedRelevant != totalRelevant) {
            return Map.of(
                    "available", false,
                    "reason", "ACTOR_COVERAGE_INCOMPLETE",
                    "message", "存在缺少 user_id 的主漏斗事件，不能安全计算同用户转化率",
                    "actorCoverage", totalRelevant == 0 ? 1D : percent(identifiedRelevant, totalRelevant));
        }
        List<EventFact> registrations = named(events, "auth.register_completed");
        if (registrations.isEmpty()) {
            return Map.of(
                    "available", false,
                    "reason", "NO_REGISTRATION_COHORT",
                    "message", "没有可识别的注册 cohort，不能计算同用户逐级转化或留存率");
        }

        Map<String, List<EventFact>> byActor = events.stream()
                .collect(Collectors.groupingBy(EventFact::actor, LinkedHashMap::new, Collectors.toList()));
        Map<String, EventFact> registered = firstByActor(registrations);
        Map<String, EventFact> verified = nextStage(byActor, registered, "kyc.express_verified");
        Map<String, EventFact> firstPurchase = nextStage(byActor, verified, "checkout.completed");
        Map<String, EventFact> repurchased = repurchaseStage(byActor, firstPurchase);
        Map<String, EventFact> withdrew = nextStage(byActor, repurchased, "withdraw.submitted");

        List<Map<String, EventFact>> stages = List.of(registered, verified, firstPurchase, repurchased, withdrew);
        List<String> stageNames = List.of("注册", "绑卡", "首购", "复投", "提现");
        List<String> stageEvents = List.of(
                "auth.register_completed",
                "kyc.express_verified",
                "checkout.completed",
                "wallet.reinvest / 二次 checkout.completed",
                "withdraw.submitted");
        List<String> lifecycle = List.of("L2", "L3", "L4", "L5", "L5+");
        List<String> colors = List.of("var(--cyan)", "#8b7cf6", "#a78bfa", "#c084fc", "#e879f9");

        List<Map<String, Object>> funnel = new ArrayList<>();
        List<Map<String, Object>> funnelExt = new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            Map<String, EventFact> current = stages.get(index);
            Map<String, EventFact> previous = index == 0 ? Map.of() : stages.get(index - 1);
            Double conversion = index == 0 || previous.isEmpty()
                    ? null
                    : percent(current.size(), previous.size());
            funnel.add(linked(
                    "stage", stageNames.get(index),
                    "ev", stageEvents.get(index),
                    "users", current.size(),
                    "cvr", conversion,
                    "lc", lifecycle.get(index),
                    "color", colors.get(index),
                    "source", "nx_event_outbox:" + stageEvents.get(index)));
            funnelExt.add(linked(
                    "plain", stagePlain(index),
                    "inflow", index == 0 ? "漏斗入口" : previous.size() + " 人",
                    "lost", index == 0 ? "—" : Math.max(0, previous.size() - current.size()) + " 人未进入下一阶段",
                    "dwell", index == 0 ? List.of() : dwellDistribution(previous, current),
                    "note", stageNote(index),
                    "trial", index == 2,
                    "v1", index == 3));
        }

        List<Map<String, Object>> cohorts = cohortRows(events, registered, false);
        Map<String, Object> curves = cohortCurves(events, registered, cohorts, false);
        List<Map<String, Object>> monthlyCohorts = cohortRows(events, registered, true);
        Map<String, Object> monthlyCurves = cohortCurves(events, registered, monthlyCohorts, true);
        List<Map<String, Object>> trial = trialRows(events);
        Map<String, Object> cross = crossAnalysis(events, registered, verified, firstPurchase);
        Map<String, Object> latestMature = cohorts.stream()
                .filter(row -> row.get("d7") instanceof Number)
                .reduce((left, right) -> right)
                .orElse(Map.of());

        return linked(
                "available", true,
                "funnel", funnel,
                "funnelExt", funnelExt,
                "trialSteps", trial,
                "cohorts", cohorts,
                "curves", curves,
                "monthlyCohorts", monthlyCohorts,
                "monthlyCurves", monthlyCurves,
                "crossAnalysis", cross,
                "stageEvents", stageEvents,
                "day7Kpi", linked("value", latestMature.get("d7"), "target", 60),
                "quality", linked(
                        "sameUserJoin", true,
                        "stageOrderEnforced", true,
                        "actorCoverage", totalRelevant == 0 ? 1D : percent(identifiedRelevant, totalRelevant),
                        "retentionEvent", "app.dau",
                        "incompleteRatesAreNull", true),
                "sources", List.of("nx_event_outbox", "nx_event_schema_registry"));
    }

    private static Map<String, EventFact> firstByActor(List<EventFact> events) {
        Map<String, EventFact> result = new LinkedHashMap<>();
        for (EventFact event : events) result.putIfAbsent(event.actor(), event);
        return result;
    }

    private static Map<String, EventFact> nextStage(
            Map<String, List<EventFact>> byActor,
            Map<String, EventFact> previous,
            String eventName) {
        Map<String, EventFact> result = new LinkedHashMap<>();
        previous.forEach((actor, prior) -> byActor.getOrDefault(actor, List.of()).stream()
                .filter(event -> event.name().equals(eventName) && event.at().isAfter(prior.at()))
                .findFirst()
                .ifPresent(event -> result.put(actor, event)));
        return result;
    }

    private static Map<String, EventFact> repurchaseStage(
            Map<String, List<EventFact>> byActor,
            Map<String, EventFact> firstPurchase) {
        Map<String, EventFact> result = new LinkedHashMap<>();
        firstPurchase.forEach((actor, first) -> {
            List<EventFact> candidates = byActor.getOrDefault(actor, List.of()).stream()
                    .filter(event -> (event.name().equals("wallet.reinvest")
                            || event.name().equals("checkout.completed")) && event.at().isAfter(first.at()))
                    .toList();
            if (!candidates.isEmpty()) result.put(actor, candidates.get(0));
        });
        return result;
    }

    private static List<Map<String, Object>> cohortRows(
            List<EventFact> events,
            Map<String, EventFact> registered,
            boolean monthly) {
        Map<String, Set<String>> actorsByCohort = new TreeMap<>();
        registered.forEach((actor, event) -> actorsByCohort
                .computeIfAbsent(cohortKey(event, monthly), ignored -> new LinkedHashSet<>()).add(actor));
        List<Map<String, Object>> result = new ArrayList<>();
        actorsByCohort.forEach((cohort, actors) -> result.add(linked(
                "w", cohort,
                "size", actors.size(),
                "d1", retention(events, registered, actors, 1),
                "d7", retention(events, registered, actors, 7),
                "d14", retention(events, registered, actors, 14),
                "d30", retention(events, registered, actors, 30),
                "d60", retention(events, registered, actors, 60))));
        return result.size() <= 6 ? result : result.subList(result.size() - 6, result.size());
    }

    private static Map<String, Object> cohortCurves(
            List<EventFact> events,
            Map<String, EventFact> registered,
            List<Map<String, Object>> cohorts,
            boolean monthly) {
        Map<String, Object> curves = new LinkedHashMap<>();
        for (Map<String, Object> cohort : cohorts) {
            String key = String.valueOf(cohort.get("w"));
            Set<String> actors = registered.entrySet().stream()
                    .filter(entry -> cohortKey(entry.getValue(), monthly).equals(key))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<List<Object>> points = new ArrayList<>();
            for (int day : CURVE_DAYS) {
                Object value = day == 0 ? (Object) 100D : retention(events, registered, actors, day);
                if (value instanceof Number) points.add(List.of(day, value));
            }
            curves.put(key, points);
        }
        return curves;
    }

    private static Double retention(
            List<EventFact> events,
            Map<String, EventFact> registered,
            Set<String> actors,
            int day) {
        if (actors.isEmpty()) return null;
        boolean mature = actors.stream().allMatch(actor ->
                registered.get(actor).at().toLocalDate().plusDays(day).isBefore(LocalDate.now().plusDays(1)));
        if (!mature) return null;
        long active = actors.stream().filter(actor -> events.stream().anyMatch(event ->
                event.actor().equals(actor)
                        && event.name().equals("app.dau")
                        && ChronoUnit.DAYS.between(
                                registered.get(actor).at().toLocalDate(), event.at().toLocalDate()) == day)).count();
        return percent(active, actors.size());
    }

    private static List<Map<String, Object>> trialRows(List<EventFact> events) {
        List<String> names = List.of("trial.claim_sheet_shown", "trial.started", "trial.redeemed");
        Map<String, List<EventFact>> byActor = events.stream()
                .collect(Collectors.groupingBy(EventFact::actor, LinkedHashMap::new, Collectors.toList()));
        Map<String, EventFact> claimed = firstByActor(named(events, names.get(0)));
        Map<String, EventFact> started = nextStage(byActor, claimed, names.get(1));
        Map<String, EventFact> redeemed = nextStage(byActor, started, names.get(2));
        List<Map<String, EventFact>> stages = List.of(claimed, started, redeemed);
        List<Map<String, Object>> result = new ArrayList<>();
        long previous = 0;
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            long count = stages.get(index).size();
            Map<String, Object> row = linked("e", name, "n", count);
            if (index > 0) {
                row.put("arr", previous == 0 ? null : percent(count, previous) + "%");
                row.put("arrLb", previous == 0 ? "上级数据不足" : " 上级转化");
            }
            result.add(row);
            previous = count;
        }
        return result;
    }

    private static Map<String, Object> crossAnalysis(
            List<EventFact> events,
            Map<String, EventFact> registered,
            Map<String, EventFact> verified,
            Map<String, EventFact> purchased) {
        List<String> locales = List.of("en", "zh", "es", "pt");
        Map<String, List<EventFact>> byActor = events.stream()
                .collect(Collectors.groupingBy(EventFact::actor, LinkedHashMap::new, Collectors.toList()));
        Map<String, EventFact> claimedTrial = firstByActor(named(events, "trial.claim_sheet_shown"));
        Map<String, EventFact> startedTrial = nextStage(byActor, claimedTrial, "trial.started");
        Map<String, EventFact> redeemedTrial = nextStage(byActor, startedTrial, "trial.redeemed");
        List<String> groups = registered.values().stream()
                .map(event -> event.ref() + " · " + event.phase())
                .distinct()
                .limit(4)
                .toList();
        List<String> safeGroups = groups.isEmpty() ? List.of("direct · unknown") : groups;
        return linked(
                "cvr", crossMetric(safeGroups, locales, registered, verified, purchased,
                        "同用户首购 ÷ 已绑卡；分母不足显示为空"),
                "ret", retentionCrossMetric(safeGroups, locales, events, registered),
                "trial", crossMetric(safeGroups, locales, registered, startedTrial, redeemedTrial,
                        "trial 兑换 ÷ trial 启动；与主漏斗独立计量"));
    }

    private static Map<String, Object> crossMetric(
            List<String> groups,
            List<String> locales,
            Map<String, EventFact> groupingBase,
            Map<String, EventFact> denominator,
            Map<String, EventFact> numerator,
            String message) {
        List<List<Object>> rows = new ArrayList<>();
        for (String group : groups) {
            List<Object> row = new ArrayList<>();
            row.add(group);
            List<Double> valid = new ArrayList<>();
            for (String locale : locales) {
                Set<String> actors = actorsFor(groupingBase, group, locale);
                long den = actors.stream().filter(denominator::containsKey).count();
                long num = actors.stream().filter(numerator::containsKey).count();
                Double value = den == 0 ? null : percent(num, den);
                row.add(value);
                if (value != null) valid.add(value);
            }
            row.add(valid.isEmpty() ? null : round(valid.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
            rows.add(row);
        }
        return linked("rows", rows, "alert", List.of(-1, -1), "unit", "%",
                "msg", linked("pre", message, "bold", "", "post", ""));
    }

    private static Map<String, Object> retentionCrossMetric(
            List<String> groups,
            List<String> locales,
            List<EventFact> events,
            Map<String, EventFact> registered) {
        List<List<Object>> rows = new ArrayList<>();
        for (String group : groups) {
            List<Object> row = new ArrayList<>();
            row.add(group);
            List<Double> valid = new ArrayList<>();
            for (String locale : locales) {
                Set<String> actors = actorsFor(registered, group, locale);
                Double value = retention(events, registered, actors, 7);
                row.add(value);
                if (value != null) valid.add(value);
            }
            row.add(valid.isEmpty() ? null : round(valid.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
            rows.add(row);
        }
        return linked("rows", rows, "alert", List.of(-1, -1), "unit", "%",
                "msg", linked("pre", "Day7 app.dau ÷ 注册 cohort；未成熟窗口显示为空", "bold", "", "post", ""));
    }

    private static Set<String> actorsFor(Map<String, EventFact> base, String group, String locale) {
        return base.entrySet().stream()
                .filter(entry -> (entry.getValue().ref() + " · " + entry.getValue().phase()).equals(group))
                .filter(entry -> entry.getValue().locale().equalsIgnoreCase(locale))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<Integer> dwellDistribution(
            Map<String, EventFact> previous,
            Map<String, EventFact> current) {
        int[] buckets = new int[8];
        current.forEach((actor, event) -> {
            EventFact prior = previous.get(actor);
            if (prior == null) return;
            long hours = Math.max(0, ChronoUnit.HOURS.between(prior.at(), event.at()));
            int bucket = hours < 1 ? 0 : hours < 6 ? 1 : hours < 24 ? 2 : hours < 48 ? 3
                    : hours < 72 ? 4 : hours < 120 ? 5 : hours < 168 ? 6 : 7;
            buckets[bucket]++;
        });
        List<Integer> result = new ArrayList<>();
        for (int value : buckets) result.add(value);
        return result;
    }

    private static EventFact eventFact(Map<String, Object> row) {
        String name = text(row, "eventName", "event_name");
        String actor = text(row, "actorId", "actor_id");
        LocalDateTime at = dateTime(value(row, "eventTs", "event_ts"));
        if (name.isBlank() || actor.isBlank() || at == null) return null;
        return new EventFact(
                name,
                actor,
                at,
                text(row, "cohort"),
                defaultText(text(row, "phase"), "unknown"),
                normalizeLocale(text(row, "locale")),
                defaultText(text(row, "refCode", "ref_code", "ref"), "direct"));
    }

    private static LocalDateTime dateTime(Object value) {
        if (value instanceof LocalDateTime local) return local;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value == null) return null;
        String text = String.valueOf(value).trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String cohortOf(EventFact event) {
        if (event.cohort().matches("\\d{4}-W\\d{2}")) return event.cohort();
        LocalDate date = event.at().toLocalDate();
        return String.format(Locale.ROOT, "%d-W%02d",
                date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private static String cohortKey(EventFact event, boolean monthly) {
        return monthly ? event.at().toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM")) : cohortOf(event);
    }

    private static String normalizeLocale(String value) {
        String normalized = defaultText(value, "und").toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('-');
        return separator > 0 ? normalized.substring(0, separator) : normalized;
    }

    private static List<EventFact> named(List<EventFact> events, String eventName) {
        return events.stream().filter(event -> event.name().equals(eventName)).toList();
    }

    private static String stagePlain(int index) {
        return List.of(
                "服务器权威注册完成",
                "同一注册用户完成 KYC 绑卡验证",
                "同一已绑卡用户完成首笔购买",
                "首购后 wallet.reinvest 或第二笔购买",
                "复投后发起提现").get(index);
    }

    private static String stageNote(int index) {
        return List.of(
                "注册是 cohort 与后续全部阶段的同用户分母。",
                "仅统计注册之后发生的 KYC 通过事件。",
                "仅统计已绑卡同一用户后续的第一笔 checkout.completed。trial 子路径独立展示。",
                "优先使用 wallet.reinvest；同时兼容首购后的第二笔 checkout.completed。",
                "仅统计完成复投后同一用户的 withdraw.submitted。").get(index);
    }

    private static Double percent(long numerator, long denominator) {
        return denominator == 0 ? null : round(numerator * 100D / denominator);
    }

    private static Double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static Map<String, Object> linked(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return result;
    }

    private static Object value(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) return row.get(key);
            String match = row.keySet().stream().filter(candidate -> candidate.equalsIgnoreCase(key)).findFirst().orElse(null);
            if (match != null) return row.get(match);
        }
        return null;
    }

    private static String text(Map<String, Object> row, String... keys) {
        Object value = value(row, keys);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record EventFact(
            String name,
            String actor,
            LocalDateTime at,
            String cohort,
            String phase,
            String locale,
            String ref) {
    }
}
