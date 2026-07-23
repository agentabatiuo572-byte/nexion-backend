package ffdd.opsconsole.bi.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** L1 pure read-model calculator over schema-accepted A4 KPI event facts. */
public final class L1KpiAnalytics {
    private static final List<String> COLORS = List.of(
            "#2dd4bf", "#60a5fa", "#818cf8", "#c084fc",
            "#f472b6", "#fb7185", "#f59e0b", "#84cc16");

    private L1KpiAnalytics() {
    }

    public static Map<String, Object> calculate(
            List<Map<String, Object>> rawRows,
            String window,
            String cohort,
            String phase,
            String locale,
            String ref) {
        String normalizedWindow = normalizeWindow(window);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = switch (normalizedWindow) {
            case "1d" -> now.minusDays(1);
            case "30d" -> now.minusDays(30);
            default -> now.minusDays(7);
        };
        List<EventFact> all = rawRows == null ? List.of() : rawRows.stream()
                .map(L1KpiAnalytics::eventFact)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(EventFact::at))
                .toList();
        List<EventFact> filtered = all.stream()
                .filter(event -> !event.at().isBefore(from) && !event.at().isAfter(now))
                .filter(event -> matches(cohort, event.cohort()))
                .filter(event -> matches(phase, event.phase()))
                .filter(event -> matches(locale, event.locale()))
                .filter(event -> matches(ref, event.ref()))
                .toList();

        List<KpiSpec> specs = specs();
        List<Map<String, Object>> kpis = new ArrayList<>();
        Map<String, Object> plain = new LinkedHashMap<>();
        Map<String, Object> ext = new LinkedHashMap<>();
        for (KpiSpec spec : specs) {
            KpiValue current = value(spec.id(), filtered, now);
            KpiValue previous = value(spec.id(), all.stream()
                    .filter(event -> !event.at().isBefore(from.minusSeconds(ChronoUnit.SECONDS.between(from, now))))
                    .filter(event -> event.at().isBefore(from))
                    .filter(event -> matches(cohort, event.cohort()))
                    .filter(event -> matches(phase, event.phase()))
                    .filter(event -> matches(locale, event.locale()))
                    .filter(event -> matches(ref, event.ref()))
                    .toList(), from);
            List<Double> spark = spark(spec.id(), filtered, from, now);
            String status = status(spec, current.value());
            kpis.add(linked(
                    "n", spec.id(), "kpiId", String.valueOf(spec.id()), "name", spec.name(),
                    "value", current.value(), "target", spec.target(), "unit", spec.unit(),
                    "dir", spec.direction(), "band", spec.band(), "status", status,
                    "available", current.value() != null, "unavailableReason", current.reason(),
                    "momDelta", delta(current.value(), previous.value()), "cohort", defaultText(cohort, "全部 cohort"),
                    "vis", spec.maturity(), "spark", spark,
                    "numerator", current.numerator(), "denominator", current.denominator(),
                    "kpiSpec", spec.formula()));
            plain.put(String.valueOf(spec.id()), spec.plain());
            ext.put(String.valueOf(spec.id()), linked(
                    "fx", spec.formula(), "fxBold", spec.events(),
                    "num", String.valueOf(current.numerator()), "den", String.valueOf(current.denominator()),
                    "delta", deltaLabel(current.value(), previous.value()),
                    "note", current.value() == null ? unavailableMessage(current.reason()) : spec.note(),
                    "jump", spec.jumps()));
        }
        return linked(
                "module", "L1", "window", normalizedWindow,
                "filters", linked("cohort", emptyToNull(cohort), "phase", emptyToNull(phase),
                        "locale", emptyToNull(locale), "ref", emptyToNull(ref)),
                "kpis", kpis, "weeks", sparkLabels(from, now),
                "phaseSwitchIndex", phaseSwitchIndex(filtered, from, now),
                "kpiColors", COLORS, "kpiPlain", plain, "kpiExt", ext,
                "capabilities", linked("eightKpis", true, "drilldown", true, "trend", true,
                        "incompleteRatesAreNull", true),
                "sources", List.of("nx_event_outbox", "nx_event_schema_registry"));
    }

    public static Map<String, Object> drilldown(
            List<Map<String, Object>> rows, int kpiId, String window,
            String cohort, String phase, String locale, String ref) {
        Map<String, Object> dashboard = calculate(rows, window, cohort, phase, locale, ref);
        Object selected = ((List<?>) dashboard.get("kpis")).stream()
                .filter(row -> row instanceof Map<?, ?> map && Objects.equals(map.get("n"), kpiId))
                .findFirst().orElse(null);
        return linked("module", "L1", "kpiId", kpiId, "selected", selected,
                "weeks", dashboard.get("weeks"), "filters", dashboard.get("filters"),
                "sources", dashboard.get("sources"));
    }

    public static Map<String, Object> trend(
            List<Map<String, Object>> rows, int kpiId, String window,
            String cohort, String phase, String locale, String ref) {
        Map<String, Object> dashboard = calculate(rows, window, cohort, phase, locale, ref);
        Object selected = ((List<?>) dashboard.get("kpis")).stream()
                .filter(row -> row instanceof Map<?, ?> map && Objects.equals(map.get("n"), kpiId))
                .findFirst().orElse(null);
        Object values = selected instanceof Map<?, ?> map ? map.get("spark") : List.of();
        return linked("module", "L1", "kpiId", kpiId, "labels", dashboard.get("weeks"),
                "values", values, "filters", dashboard.get("filters"), "sources", dashboard.get("sources"));
    }

    private static KpiValue value(int id, List<EventFact> events, LocalDateTime end) {
        return switch (id) {
            case 1 -> ratio(
                    actors(events, event -> event.name().equals("device.first_yield_received")
                            && event.latencySec() != null && event.latencySec() <= 90),
                    actors(events, named("auth.register_completed")), "NO_REGISTRATION_DENOMINATOR");
            case 2 -> day7(events, end);
            case 3 -> ratio(actors(events, named("store.viewed")),
                    actors(events, named("auth.register_completed")), "NO_REGISTRATION_DENOMINATOR");
            case 4 -> ratio(actors(events, named("checkout.completed")),
                    actors(events, named("store.viewed")), "NO_STORE_VIEW_DENOMINATOR");
            case 5 -> ratio(actors(events, named("referral.invite_sent")),
                    actors(events, event -> event.name().equals("device.purchase_completed")
                            || event.name().equals("device.first_yield_received")), "NO_DEVICE_HOLDER_DENOMINATOR");
            case 6 -> ratio(actors(events, named("nova.push_clicked")),
                    actors(events, named("nova.push_sent")), "NO_PUSH_SENT_DENOMINATOR");
            case 7 -> {
                Set<String> directs = actors(events, named("referral.bound"));
                Set<String> paid = actors(events, named("commission.paid"));
                paid.retainAll(directs);
                yield ratio(paid, directs, "NO_DIRECT_REFERRAL_DENOMINATOR");
            }
            case 8 -> genesis(events);
            default -> new KpiValue(null, 0, 0, "KPI_ID_UNKNOWN");
        };
    }

    private static KpiValue day7(List<EventFact> events, LocalDateTime end) {
        Map<String, EventFact> registrations = firstByActor(events, "auth.register_completed");
        Set<String> mature = new LinkedHashSet<>();
        Set<String> active = new LinkedHashSet<>();
        registrations.forEach((actor, registration) -> {
            if (registration.at().toLocalDate().plusDays(7).isAfter(end.toLocalDate())) return;
            mature.add(actor);
            boolean found = events.stream().anyMatch(event -> event.actor().equals(actor)
                    && event.name().equals("app.dau")
                    && ChronoUnit.DAYS.between(registration.at().toLocalDate(), event.at().toLocalDate()) == 7);
            if (found) active.add(actor);
        });
        return ratio(active, mature, "NO_MATURE_DAY7_COHORT");
    }

    private static KpiValue genesis(List<EventFact> events) {
        List<EventFact> purchases = events.stream().filter(named("genesis.purchased")).toList();
        long sold = purchases.stream().mapToLong(event -> Math.max(0, event.quantity())).sum();
        if (purchases.isEmpty()) return new KpiValue(null, 0, 1000, "NO_GENESIS_PURCHASES");
        if (sold < 1000) return new KpiValue(null, sold, 1000, "GENESIS_NOT_SOLD_OUT");
        LocalDate first = purchases.get(0).at().toLocalDate();
        long cumulative = 0;
        for (EventFact event : purchases) {
            cumulative += Math.max(0, event.quantity());
            if (cumulative >= 1000) {
                return new KpiValue((double) Math.max(1, ChronoUnit.DAYS.between(first, event.at().toLocalDate()) + 1), sold, 1000, null);
            }
        }
        return new KpiValue(null, sold, 1000, "GENESIS_NOT_SOLD_OUT");
    }

    private static KpiValue ratio(Set<String> numerator, Set<String> denominator, String reason) {
        if (denominator.isEmpty()) return new KpiValue(null, numerator.size(), 0, reason);
        Set<String> matched = new LinkedHashSet<>(numerator);
        matched.retainAll(denominator);
        return new KpiValue(round(matched.size() * 100D / denominator.size()), matched.size(), denominator.size(), null);
    }

    private static List<Double> spark(int id, List<EventFact> events, LocalDateTime from, LocalDateTime to) {
        List<Double> result = new ArrayList<>();
        long seconds = Math.max(1, ChronoUnit.SECONDS.between(from, to));
        for (int index = 0; index < 6; index++) {
            LocalDateTime start = from.plusSeconds(seconds * index / 6);
            LocalDateTime end = from.plusSeconds(seconds * (index + 1) / 6);
            KpiValue point = value(id, events.stream()
                    .filter(event -> !event.at().isBefore(start) && event.at().isBefore(end.plusNanos(1)))
                    .toList(), end);
            if (point.value() != null) result.add(point.value());
        }
        return result;
    }

    private static List<String> sparkLabels(LocalDateTime from, LocalDateTime to) {
        List<String> labels = new ArrayList<>();
        long seconds = Math.max(1, ChronoUnit.SECONDS.between(from, to));
        for (int index = 1; index <= 6; index++) {
            labels.add(from.plusSeconds(seconds * index / 6).toLocalDate().toString().substring(5));
        }
        return labels;
    }

    private static int phaseSwitchIndex(List<EventFact> events, LocalDateTime from, LocalDateTime to) {
        List<EventFact> phased = events.stream()
                .filter(event -> !event.phase().isBlank() && !event.phase().equalsIgnoreCase("unknown"))
                .sorted(Comparator.comparing(EventFact::at))
                .toList();
        if (phased.size() < 2) return -1;
        String first = phased.get(0).phase();
        EventFact changed = phased.stream().filter(event -> !event.phase().equalsIgnoreCase(first)).findFirst().orElse(null);
        if (changed == null) return -1;
        long total = Math.max(1, ChronoUnit.SECONDS.between(from, to));
        long elapsed = Math.max(0, ChronoUnit.SECONDS.between(from, changed.at()));
        return Math.min(5, (int) (elapsed * 6 / total));
    }

    private static List<KpiSpec> specs() {
        return List.of(
                spec(1, "Day0 接入", 95, "%", "gte", List.of(), "V1 实时", "90 秒内首笔收益人数 ÷ 注册完成人数", "device.first_yield_received.latency_sec ≤ 90 ÷ auth.register_completed", List.of("device.first_yield_received", "auth.register_completed"), "衡量注册后能否快速感知收益", "/analytics/funnel-cohort"),
                spec(2, "Day7 留存", 60, "%", "gte", List.of(), "V1 实时", "第 7 天活跃人数 ÷ 已成熟注册 cohort", "day7 app.dau ÷ register cohort", List.of("app.dau", "auth.register_completed"), "未满 7 天的 cohort 不进入分母", "/analytics/funnel-cohort"),
                spec(3, "注册→进 store", 30, "%", "gte", List.of(), "V1 实时", "看过商城的去重用户 ÷ 注册完成人数", "store.viewed distinct user ÷ auth.register_completed", List.of("store.viewed", "auth.register_completed"), "客户端浏览事件按锁定 KPI 口径统计", "/analytics/funnel-cohort"),
                spec(4, "购买转化", 5, "%", "band", List.of(5, 10), "V1 实时", "支付完成去重用户 ÷ 看过商城去重用户", "checkout.completed ÷ store.viewed", List.of("checkout.completed", "store.viewed"), "健康带为 5%–10%", "/analytics/funnel-cohort"),
                spec(5, "L4→L5 推广", 40, "%", "gte", List.of(), "V4 完整", "发出邀请的设备持有者 ÷ 设备持有者", "device holder referral.invite_sent ÷ device holders", List.of("referral.invite_sent", "device.purchase_completed"), "推广率不是提现或收入指标", "/analytics/operations"),
                spec(6, "Nova 推送点击", 25, "%", "gte", List.of(), "V1 实时", "推送点击去重用户 ÷ 推送送达去重用户", "nova.push_clicked ÷ nova.push_sent", List.of("nova.push_clicked", "nova.push_sent"), "点击为锁定的客户端 KPI 事件", "/analytics/funnel-cohort"),
                spec(7, "团队佣金触发率", 80, "%", "gte", List.of(), "V4 完整", "直推中产生首单佣金的人数 ÷ 直推人数", "L1 referred first commission.paid ÷ referral.bound", List.of("commission.paid", "referral.bound"), "只计算能与直推关系匹配的佣金事件", "/analytics/operations"),
                spec(8, "Genesis 售罄速度", 14, "d", "lte", List.of(), "V1 实时", "累计售出达到 1,000 的自然日数", "genesis.purchased cumulative 1,000 days", List.of("genesis.purchased"), "未售罄时显示不可计算并保留进度", "/analytics/finance"));
    }

    private static KpiSpec spec(int id, String name, double target, String unit, String direction,
                                List<Integer> band, String maturity, String plain, String formula,
                                List<String> events, String note, String href) {
        return new KpiSpec(id, name, target, unit, direction, band, maturity, plain, formula,
                events, note, List.of(linked("label", "关联下钻", "href", href)));
    }

    private static Predicate<EventFact> named(String name) {
        return event -> event.name().equals(name);
    }

    private static Set<String> actors(List<EventFact> events, Predicate<EventFact> predicate) {
        Set<String> result = new LinkedHashSet<>();
        events.stream().filter(predicate).map(EventFact::actor).filter(actor -> !actor.isBlank()).forEach(result::add);
        return result;
    }

    private static Map<String, EventFact> firstByActor(List<EventFact> events, String name) {
        Map<String, EventFact> result = new LinkedHashMap<>();
        events.stream().filter(named(name)).forEach(event -> result.putIfAbsent(event.actor(), event));
        return result;
    }

    private static EventFact eventFact(Map<String, Object> row) {
        String name = text(row, "eventName", "event_name");
        String actor = text(row, "actorId", "actor_id");
        LocalDateTime at = dateTime(value(row, "eventTs", "event_ts"));
        if (name.isBlank() || at == null) return null;
        return new EventFact(name, actor, at, text(row, "cohort"),
                defaultText(text(row, "phase"), "unknown"), normalizeLocale(text(row, "locale")),
                defaultText(text(row, "refCode", "ref_code", "ref"), "direct"),
                number(row, "latencySec", "latency_sec"), longNumber(row, "quantity", 1));
    }

    private static LocalDateTime dateTime(Object raw) {
        if (raw instanceof LocalDateTime local) return local;
        if (raw instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (raw == null) return null;
        try { return LocalDateTime.parse(String.valueOf(raw).trim().replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String status(KpiSpec spec, Double value) {
        if (value == null) return "UNAVAILABLE";
        if (spec.direction().equals("band")) return value >= spec.band().get(0) && value <= spec.band().get(1) ? "GREEN" : "RED";
        if (spec.direction().equals("lte")) return value <= spec.target() ? "GREEN" : "RED";
        return value >= spec.target() ? "GREEN" : "RED";
    }

    private static Double delta(Double current, Double previous) {
        return current == null || previous == null ? null : round(current - previous);
    }

    private static String deltaLabel(Double current, Double previous) {
        Double value = delta(current, previous);
        return value == null ? "—" : (value >= 0 ? "+" : "") + value;
    }

    private static String unavailableMessage(String reason) {
        return switch (defaultText(reason, "SOURCE_INCOMPLETE")) {
            case "GENESIS_NOT_SOLD_OUT" -> "Genesis 尚未累计售出 1,000，售罄天数不可计算";
            case "NO_MATURE_DAY7_COHORT" -> "当前筛选范围没有已满 7 天的注册 cohort";
            default -> "当前筛选范围缺少权威分母，指标不可计算；不会用 0% 冒充结果";
        };
    }

    private static boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || "all".equalsIgnoreCase(expected) || expected.equalsIgnoreCase(actual);
    }

    private static String normalizeWindow(String value) {
        return Set.of("1d", "7d", "30d").contains(defaultText(value, "7d").toLowerCase(Locale.ROOT))
                ? defaultText(value, "7d").toLowerCase(Locale.ROOT) : "7d";
    }

    private static String normalizeLocale(String value) {
        String normalized = defaultText(value, "und").toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('-');
        return separator > 0 ? normalized.substring(0, separator) : normalized;
    }

    private static Double number(Map<String, Object> row, String... keys) {
        Object raw = value(row, keys);
        if (raw == null) return null;
        try { return Double.valueOf(String.valueOf(raw)); } catch (RuntimeException ignored) { return null; }
    }

    private static long longNumber(Map<String, Object> row, String key, long fallback) {
        Double value = number(row, key);
        return value == null ? fallback : value.longValue();
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
        Object raw = value(row, keys);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static Object emptyToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String defaultText(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static Double round(double value) { return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue(); }

    private static Map<String, Object> linked(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        return result;
    }

    private record EventFact(String name, String actor, LocalDateTime at, String cohort,
                             String phase, String locale, String ref, Double latencySec, long quantity) { }
    private record KpiValue(Double value, long numerator, long denominator, String reason) { }
    private record KpiSpec(int id, String name, double target, String unit, String direction,
                           List<Integer> band, String maturity, String plain, String formula,
                           List<String> events, String note, List<Map<String, Object>> jumps) { }
}
