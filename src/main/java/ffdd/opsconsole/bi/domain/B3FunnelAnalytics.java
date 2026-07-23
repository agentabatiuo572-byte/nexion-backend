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
 * Canonical B3 read model. Every stage is a same-actor, timestamp-ordered
 * projection of schema-accepted A4 facts. Filters are anchored to the
 * registration event so later stage metadata cannot move an actor between
 * cohorts.
 */
public final class B3FunnelAnalytics {
    private static final List<String> MAIN_EVENTS = List.of(
            "auth.register_completed",
            "kyc.express_verified",
            "checkout.completed",
            "wallet.reinvest",
            "withdraw.submitted");
    private static final List<String> STAGE_NAMES = List.of("注册", "绑卡", "首购", "复投", "提现");
    private static final List<String> STAGE_KEYS = List.of("register", "kyc", "purchase", "repurchase", "withdraw");
    private static final List<String> STAGE_EVENTS = List.of(
            "auth.register_completed",
            "kyc.express_verified",
            "checkout.completed",
            "wallet.reinvest / 二次 checkout.completed",
            "withdraw.submitted");
    private static final List<String> LIFECYCLES = List.of("L1", "L2", "L3→L4", "L5", "L5");
    private static final List<String> COLORS = List.of(
            "var(--brand)", "var(--cyan)", "var(--success)", "var(--warning)", "var(--danger)");

    private B3FunnelAnalytics() {
    }

    public static Map<String, Object> calculate(
            List<Map<String, Object>> rawRows, String cohort, String phase, String ref) {
        List<EventFact> allEvents = facts(rawRows);
        long relevantRaw = rawRows == null ? 0 : rawRows.stream()
                .filter(row -> MAIN_EVENTS.contains(text(row, "eventName", "event_name")))
                .count();
        long relevantIdentified = allEvents.stream().filter(event -> MAIN_EVENTS.contains(event.name())).count();
        if (relevantRaw != relevantIdentified) {
            return linked(
                    "available", false,
                    "reason", "ACTOR_COVERAGE_INCOMPLETE",
                    "message", "存在缺少用户标识或事件时间的主漏斗事实，已停止计算，避免产生伪转化率",
                    "filters", filters(cohort, phase, ref),
                    "sources", List.of("nx_event_outbox", "nx_event_schema_registry"));
        }

        List<EventFact> registrations = named(allEvents, "auth.register_completed");
        List<String> cohorts = registrations.stream().map(B3FunnelAnalytics::cohortOf).distinct().sorted().toList();
        List<String> phases = registrations.stream().map(EventFact::phase).distinct().sorted().toList();
        List<String> refs = registrations.stream().map(EventFact::ref).distinct().sorted().toList();

        List<EventFact> selectedRegistrations = registrations.stream()
                .filter(event -> blank(cohort) || cohortOf(event).equals(cohort))
                .filter(event -> blank(phase) || event.phase().equalsIgnoreCase(phase))
                .filter(event -> blank(ref) || event.ref().equalsIgnoreCase(ref))
                .toList();
        Map<String, List<EventFact>> byActor = allEvents.stream()
                .collect(Collectors.groupingBy(EventFact::actor, LinkedHashMap::new, Collectors.toList()));
        Map<String, EventFact> registered = firstByActor(selectedRegistrations);
        Map<String, EventFact> verified = nextStage(byActor, registered, "kyc.express_verified");
        Map<String, EventFact> purchased = nextStage(byActor, verified, "checkout.completed");
        Map<String, EventFact> repurchased = repurchaseStage(byActor, purchased);
        Map<String, EventFact> withdrew = nextStage(byActor, repurchased, "withdraw.submitted");
        List<Map<String, EventFact>> stageFacts = List.of(registered, verified, purchased, repurchased, withdrew);

        List<Map<String, Object>> stages = new ArrayList<>();
        for (int index = 0; index < stageFacts.size(); index++) {
            int current = stageFacts.get(index).size();
            int previous = index == 0 ? current : stageFacts.get(index - 1).size();
            Double cvr = index == 0 ? null : percent(current, previous);
            stages.add(linked(
                    "key", STAGE_KEYS.get(index),
                    "stage", STAGE_NAMES.get(index),
                    "event", STAGE_EVENTS.get(index),
                    "distinctUsers", current,
                    "previousUsers", previous,
                    "cvrFromPrev", cvr,
                    "momDelta", null,
                    "lifecycleLabel", LIFECYCLES.get(index),
                    "kpiTarget", index == 2 ? "5%–10%（store→首购）" : null,
                    "color", COLORS.get(index),
                    "source", "nx_event_outbox:" + STAGE_EVENTS.get(index)));
        }

        Map<String, Object> aux = auxMetrics(allEvents, registered);
        return linked(
                "available", true,
                "module", "B3",
                "generatedAt", LocalDateTime.now().toString(),
                "filters", filters(cohort, phase, ref),
                "filterOptions", linked("cohorts", cohorts, "phases", phases, "refs", refs),
                "stages", stages,
                "auxMetrics", aux,
                "trend", trendFromEvents(allEvents, "purchase", phase, ref),
                "sources", List.of("nx_event_outbox", "nx_event_schema_registry"),
                "sourceStatement", "仅统计 schema_registered=1 且权威性满足 A4 规则的事件；主漏斗按同一用户严格有序推进");
    }

    public static Map<String, Object> cohortTrend(
            List<Map<String, Object>> rawRows, String stage, String phase, String ref) {
        List<EventFact> events = facts(rawRows);
        return linked(
                "stage", normalizeStage(stage),
                "points", trendFromEvents(events, normalizeStage(stage), phase, ref),
                "sources", List.of("nx_event_outbox", "nx_event_schema_registry"));
    }

    private static List<Map<String, Object>> trendFromEvents(
            List<EventFact> events, String stage, String phase, String ref) {
        Map<String, List<EventFact>> registrationsByCohort = named(events, "auth.register_completed").stream()
                .filter(event -> blank(phase) || event.phase().equalsIgnoreCase(phase))
                .filter(event -> blank(ref) || event.ref().equalsIgnoreCase(ref))
                .collect(Collectors.groupingBy(B3FunnelAnalytics::cohortOf, TreeMap::new, Collectors.toList()));
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map.Entry<String, List<EventFact>> entry : registrationsByCohort.entrySet()) {
            Map<String, List<EventFact>> byActor = events.stream()
                    .collect(Collectors.groupingBy(EventFact::actor, LinkedHashMap::new, Collectors.toList()));
            List<Map<String, EventFact>> stages = orderedStages(byActor, firstByActor(entry.getValue()));
            int stageIndex = STAGE_KEYS.indexOf(normalizeStage(stage));
            int selected = stages.get(stageIndex).size();
            int previous = stageIndex == 0 ? selected : stages.get(stageIndex - 1).size();
            points.add(linked(
                    "cohort", entry.getKey(),
                    "distinctUsers", selected,
                    "cvrFromPrev", stageIndex == 0 ? null : percent(selected, previous)));
        }
        return points;
    }

    private static List<Map<String, EventFact>> orderedStages(
            Map<String, List<EventFact>> byActor, Map<String, EventFact> registered) {
        Map<String, EventFact> verified = nextStage(byActor, registered, "kyc.express_verified");
        Map<String, EventFact> purchased = nextStage(byActor, verified, "checkout.completed");
        Map<String, EventFact> repurchased = repurchaseStage(byActor, purchased);
        Map<String, EventFact> withdrew = nextStage(byActor, repurchased, "withdraw.submitted");
        return List.of(registered, verified, purchased, repurchased, withdrew);
    }

    private static Map<String, Object> auxMetrics(
            List<EventFact> events, Map<String, EventFact> registered) {
        Set<String> day0Actors = new LinkedHashSet<>();
        Set<String> matureActors = new LinkedHashSet<>();
        Set<String> day7Actors = new LinkedHashSet<>();
        LocalDate today = LocalDate.now();
        for (Map.Entry<String, EventFact> entry : registered.entrySet()) {
            String actor = entry.getKey();
            EventFact registration = entry.getValue();
            events.stream()
                    .filter(event -> event.actor().equals(actor))
                    .filter(event -> event.name().equals("device.first_yield_received"))
                    .filter(event -> !event.at().isBefore(registration.at()))
                    .filter(event -> event.latencySec() != null && event.latencySec() <= 90)
                    .findFirst()
                    .ifPresent(event -> day0Actors.add(actor));
            long ageDays = ChronoUnit.DAYS.between(registration.at().toLocalDate(), today);
            if (ageDays >= 7) {
                matureActors.add(actor);
                boolean retained = events.stream()
                        .filter(event -> event.actor().equals(actor))
                        .filter(event -> event.name().equals("app.dau"))
                        .anyMatch(event -> ChronoUnit.DAYS.between(
                                registration.at().toLocalDate(), event.at().toLocalDate()) == 7);
                if (retained) day7Actors.add(actor);
            }
        }
        return linked(
                "day0AccessRate", percent(day0Actors.size(), registered.size()),
                "day0Numerator", day0Actors.size(),
                "day0Denominator", registered.size(),
                "day0Target", 95,
                "day7Retention", matureActors.isEmpty() ? null : percent(day7Actors.size(), matureActors.size()),
                "day7Numerator", day7Actors.size(),
                "day7Denominator", matureActors.size(),
                "day7Target", 60,
                "day7Mature", !matureActors.isEmpty());
    }

    private static Map<String, Object> filters(String cohort, String phase, String ref) {
        return linked(
                "cohort", blank(cohort) ? "ALL" : cohort,
                "phase", blank(phase) ? "ALL" : phase,
                "ref", blank(ref) ? "ALL" : ref);
    }

    private static List<EventFact> facts(List<Map<String, Object>> rawRows) {
        if (rawRows == null) return List.of();
        return rawRows.stream()
                .map(B3FunnelAnalytics::eventFact)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(EventFact::at))
                .toList();
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
                defaultText(text(row, "refCode", "ref_code", "ref"), "direct"),
                integer(value(row, "latencySec", "latency_sec")));
    }

    private static Map<String, EventFact> firstByActor(List<EventFact> events) {
        Map<String, EventFact> result = new LinkedHashMap<>();
        events.stream().sorted(Comparator.comparing(EventFact::at))
                .forEach(event -> result.putIfAbsent(event.actor(), event));
        return result;
    }

    private static Map<String, EventFact> nextStage(
            Map<String, List<EventFact>> byActor, Map<String, EventFact> previous, String eventName) {
        Map<String, EventFact> result = new LinkedHashMap<>();
        previous.forEach((actor, prior) -> byActor.getOrDefault(actor, List.of()).stream()
                .filter(event -> event.name().equals(eventName))
                .filter(event -> event.at().isAfter(prior.at()))
                .findFirst()
                .ifPresent(event -> result.put(actor, event)));
        return result;
    }

    private static Map<String, EventFact> repurchaseStage(
            Map<String, List<EventFact>> byActor, Map<String, EventFact> purchased) {
        Map<String, EventFact> result = new LinkedHashMap<>();
        purchased.forEach((actor, firstPurchase) -> byActor.getOrDefault(actor, List.of()).stream()
                .filter(event -> event.at().isAfter(firstPurchase.at()))
                .filter(event -> event.name().equals("wallet.reinvest")
                        || event.name().equals("checkout.completed"))
                .findFirst()
                .ifPresent(event -> result.put(actor, event)));
        return result;
    }

    private static List<EventFact> named(List<EventFact> events, String name) {
        return events.stream().filter(event -> event.name().equals(name)).toList();
    }

    private static String cohortOf(EventFact event) {
        if (event.cohort().matches("\\d{4}-W\\d{2}")) return event.cohort();
        LocalDate date = event.at().toLocalDate();
        return String.format(Locale.ROOT, "%d-W%02d",
                date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private static String normalizeStage(String stage) {
        if (blank(stage)) return "purchase";
        String normalized = stage.trim().toLowerCase(Locale.ROOT);
        if (STAGE_KEYS.contains(normalized)) return normalized;
        return switch (normalized) {
            case "auth.register_completed" -> "register";
            case "kyc.express_verified" -> "kyc";
            case "checkout.completed" -> "purchase";
            case "wallet.reinvest" -> "repurchase";
            case "withdraw.submitted" -> "withdraw";
            default -> "purchase";
        };
    }

    private static Double percent(long numerator, long denominator) {
        if (denominator == 0) return null;
        return BigDecimal.valueOf(numerator * 100D / denominator)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static LocalDateTime dateTime(Object value) {
        if (value instanceof LocalDateTime local) return local;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value == null) return null;
        try {
            return LocalDateTime.parse(String.valueOf(value).trim().replace(' ', 'T'),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Integer integer(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return new BigDecimal(String.valueOf(value)).intValueExact();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Object value(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) return row.get(key);
            String match = row.keySet().stream()
                    .filter(candidate -> candidate.equalsIgnoreCase(key))
                    .findFirst()
                    .orElse(null);
            if (match != null) return row.get(match);
        }
        return null;
    }

    private static String text(Map<String, Object> row, String... keys) {
        Object value = value(row, keys);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String defaultText(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim());
    }

    private static Map<String, Object> linked(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return result;
    }

    private record EventFact(
            String name,
            String actor,
            LocalDateTime at,
            String cohort,
            String phase,
            String ref,
            Integer latencySec) {
    }
}
