package ffdd.opsconsole.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class L2FunnelAnalyticsTest {

    @Test
    void keepsImmatureRetentionWindowsNullWithoutUnboxingFailures() {
        Map<String, Object> result = L2FunnelAnalytics.calculate(List.of(
                event("auth.register_completed", "future-user", LocalDateTime.now().minusHours(1))));

        assertThat(result).containsEntry("available", true);
        Map<String, Object> curves = map(result.get("curves"));
        List<List<Object>> points = points(curves.values().iterator().next());
        assertThat(points).containsExactly(List.of(0, 100D));

        Map<String, Object> cohort = rows(result.get("cohorts")).get(0);
        assertThat(cohort.get("d1")).isNull();
        assertThat(cohort.get("d7")).isNull();
        assertThat(cohort.get("d60")).isNull();
    }

    @Test
    void appliesCohortPhaseLocaleAndRefToTheRegistrationActorSet() {
        LocalDateTime registeredAt = LocalDateTime.now().minusDays(40);
        Map<String, Object> selected = event(
                "auth.register_completed", "selected", registeredAt, "2026-W20", "P3", "vi", "campaign-a");
        Map<String, Object> other = event(
                "auth.register_completed", "other", registeredAt, "2026-W20", "P2", "zh", "campaign-b");
        Map<String, Object> result = L2FunnelAnalytics.calculate(List.of(
                selected,
                event("checkout.completed", "selected", registeredAt.plusHours(1), "2026-W20", "P3", "vi", "campaign-a"),
                event("wallet.reinvest", "selected", registeredAt.plusHours(2), "2026-W20", "P3", "vi", "campaign-a"),
                other,
                event("checkout.completed", "other", registeredAt.plusHours(1), "2026-W20", "P2", "zh", "campaign-b")),
                "2026-W20", "P3", "vi", "campaign-a");

        assertThat(result).containsEntry("available", true);
        assertThat(rows(result.get("funnel")))
                .extracting(row -> row.get("users"))
                .containsExactly(1, 1, 1, 0);
        assertThat(map(result.get("filters")))
                .containsEntry("cohort", "2026-W20")
                .containsEntry("phase", "P3")
                .containsEntry("locale", "vi")
                .containsEntry("ref", "campaign-a");
    }

    @Test
    void deduplicatesRepeatedFactsAndExposesAllRealLocalesAndChannelPhaseGroups() {
        LocalDateTime registeredAt = LocalDateTime.now().minusDays(40);
        List<Map<String, Object>> facts = new java.util.ArrayList<>();
        for (int index = 0; index < 6; index++) {
            String actor = "user-" + index;
            String locale = index % 3 == 0 ? "vi" : index % 3 == 1 ? "zh" : "en";
            String ref = "channel-" + index;
            facts.add(event("auth.register_completed", actor, registeredAt, "2026-W20", "P3", locale, ref));
            facts.add(event("auth.register_completed", actor, registeredAt.plusSeconds(1), "2026-W20", "P3", locale, ref));
            facts.add(event("checkout.completed", actor, registeredAt.plusHours(1), "2026-W20", "P3", locale, ref));
            facts.add(event("wallet.reinvest", actor, registeredAt.plusHours(2), "2026-W20", "P3", locale, ref));
        }

        Map<String, Object> result = L2FunnelAnalytics.calculate(facts);
        assertThat(rows(result.get("funnel")).get(0).get("users")).isEqualTo(6);
        Map<String, Object> cross = map(result.get("crossAnalysis"));
        Map<String, Object> cvr = map(cross.get("cvr"));
        assertThat(cvr.get("columns")).asList().containsExactly("en", "vi", "zh");
        assertThat(cvr.get("rows")).asList().hasSize(6);
    }

    @Test
    void reportsTheFixedBusinessTimezoneAndIncludesLateFactsOnTheNextCalculation() {
        LocalDateTime registeredAt = LocalDateTime.now().minusDays(40);
        Map<String, Object> before = L2FunnelAnalytics.calculate(List.of(
                event("auth.register_completed", "late-user", registeredAt)));
        Map<String, Object> after = L2FunnelAnalytics.calculate(List.of(
                event("auth.register_completed", "late-user", registeredAt),
                event("app.dau", "late-user", registeredAt.plusDays(7).plusMinutes(5))));

        assertThat(map(before.get("quality")))
                .containsEntry("businessTimeZone", "UTC+08:00")
                .containsEntry("lateArrivals", "included_on_next_query");
        assertThat(rows(before.get("cohorts")).get(0).get("d7")).isEqualTo(0D);
        assertThat(rows(after.get("cohorts")).get(0).get("d7")).isEqualTo(100D);
    }

    private static Map<String, Object> event(String name, String actor, LocalDateTime at) {
        return event(name, actor, at, "", "P3", "zh", "direct");
    }

    private static Map<String, Object> event(
            String name, String actor, LocalDateTime at, String cohort, String phase, String locale, String ref) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventName", name);
        row.put("actorId", actor);
        row.put("eventTs", at);
        row.put("cohort", cohort);
        row.put("phase", phase);
        row.put("locale", locale);
        row.put("refCode", ref);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> points(Object value) {
        return (List<List<Object>>) value;
    }
}
