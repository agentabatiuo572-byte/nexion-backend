package ffdd.opsconsole.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class L1KpiAnalyticsTest {

    @Test
    void alwaysReturnsEightContractsAndKeepsMissingDenominatorsNull() {
        Map<String, Object> result = L1KpiAnalytics.calculate(List.of(), "7d", null, null, null, null);

        List<Map<String, Object>> kpis = rows(result.get("kpis"));
        assertThat(kpis).hasSize(8);
        assertThat(kpis).allSatisfy(kpi -> {
            assertThat(kpi.get("value")).isNull();
            assertThat(kpi.get("status")).isEqualTo("UNAVAILABLE");
            assertThat(kpi.get("available")).isEqualTo(false);
        });
        assertThat(map(result.get("capabilities"))).containsEntry("incompleteRatesAreNull", true);
    }

    @Test
    void calculatesAuthoritativeRatiosAndDoesNotLetUnmatchedActorsInflateNumerators() {
        LocalDateTime now = LocalDateTime.now().minusHours(1);
        List<Map<String, Object>> facts = new ArrayList<>();
        facts.add(event("auth.register_completed", "u1", now, null, 1));
        facts.add(event("auth.register_completed", "u2", now, null, 1));
        facts.add(event("device.first_yield_received", "u1", now.plusMinutes(1), 60D, 1));
        facts.add(event("device.first_yield_received", "outside", now.plusMinutes(1), 30D, 1));
        facts.add(event("store.viewed", "u1", now.plusMinutes(2), null, 1));
        facts.add(event("checkout.completed", "u1", now.plusMinutes(3), null, 1));
        facts.add(event("checkout.completed", "outside", now.plusMinutes(3), null, 1));
        facts.add(event("nova.push_sent", "u1", now.plusMinutes(4), null, 1));
        facts.add(event("nova.push_clicked", "u1", now.plusMinutes(5), null, 1));

        List<Map<String, Object>> kpis = rows(L1KpiAnalytics
                .calculate(facts, "7d", null, null, null, null).get("kpis"));

        assertThat(kpis.get(0)).containsEntry("value", 50D).containsEntry("numerator", 1L).containsEntry("denominator", 2L);
        assertThat(kpis.get(2)).containsEntry("value", 50D);
        assertThat(kpis.get(3)).containsEntry("value", 100D).containsEntry("numerator", 1L).containsEntry("denominator", 1L);
        assertThat(kpis.get(5)).containsEntry("value", 100D);
    }

    @Test
    void genesisDaysRemainNullUntilOneThousandUnitsAreReached() {
        LocalDateTime now = LocalDateTime.now().minusDays(2);
        Map<String, Object> incomplete = L1KpiAnalytics.calculate(
                List.of(event("genesis.purchased", "u1", now, null, 999)),
                "7d", null, null, null, null);
        assertThat(rows(incomplete.get("kpis")).get(7))
                .containsEntry("value", null)
                .containsEntry("unavailableReason", "GENESIS_NOT_SOLD_OUT");

        Map<String, Object> complete = L1KpiAnalytics.calculate(
                List.of(event("genesis.purchased", "u1", now, null, 400),
                        event("genesis.purchased", "u2", now.plusDays(1), null, 600)),
                "7d", null, null, null, null);
        assertThat(rows(complete.get("kpis")).get(7)).containsEntry("value", 2D);
    }

    private static Map<String, Object> event(
            String name, String actor, LocalDateTime at, Double latency, long quantity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventName", name);
        row.put("actorId", actor);
        row.put("eventTs", at);
        row.put("cohort", "2026-W30");
        row.put("phase", "P3");
        row.put("locale", "zh");
        row.put("refCode", "direct");
        row.put("latencySec", latency);
        row.put("quantity", quantity);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
