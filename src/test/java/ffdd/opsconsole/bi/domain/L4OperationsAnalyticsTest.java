package ffdd.opsconsole.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class L4OperationsAnalyticsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 12, 0);

    @Test
    void attributesNetworkCommissionToTheReferredSourceUserAndDeduplicatesReplay() {
        List<Map<String, Object>> facts = new ArrayList<>();
        facts.add(fact("ref-1", "referral.bound", "200", null, "2026-07-25T10:00:00", Map.of()));
        facts.add(fact("com-1", "commission.paid", "100", "200", "2026-07-25T11:00:00",
                Map.of("amountUsdt", "5", "tier", "network")));
        facts.add(fact("com-1", "commission.paid", "100", "200", "2026-07-25T11:00:00",
                Map.of("amountUsdt", "5", "tier", "network")));

        Map<String, Object> result = L4OperationsAnalytics.calculate(
                facts, "week", "ALL", null, null, NOW);
        Map<String, Object> network = map(result.get("network"));
        Map<String, Object> summary = map(network.get("summary"));

        assertThat(summary.get("directRefs")).isEqualTo(1);
        assertThat(summary.get("commissionEvents")).isEqualTo(1);
        assertThat(summary.get("commissionPaidUsdt")).isEqualTo(5.0);
        assertThat(summary.get("commissionTriggerRate")).isEqualTo(100.0);
        assertThat(map(result.get("quality")).get("duplicateEventsIgnored")).isEqualTo(1);
    }

    @Test
    void aSinglePhaseSliceReturnsOnlyThatPhaseAndDoesNotBridgeMissingPhaseSteps() {
        List<Map<String, Object>> facts = List.of(
                fact("p1-store", "store.viewed", "1", null, "2026-07-25T08:00:00", Map.of("phase", "P1")),
                fact("p1-buy", "checkout.completed", "1", null, "2026-07-25T09:00:00", Map.of("phase", "P1")),
                fact("p3-store", "store.viewed", "2", null, "2026-07-25T10:00:00", Map.of("phase", "P3")),
                fact("p3-buy", "checkout.completed", "2", null, "2026-07-25T11:00:00", Map.of("phase", "P3")));

        Map<String, Object> all = L4OperationsAnalytics.calculate(
                facts, "week", "ALL", null, null, NOW);
        List<Map<String, Object>> allRows = maps(all.get("phaseEffect"));
        assertThat(allRows).hasSize(6);
        assertThat(allRows.get(2).get("conversionStepPct")).isNull();

        Map<String, Object> p3 = L4OperationsAnalytics.calculate(
                facts, "week", "P3", null, null, NOW);
        List<Map<String, Object>> p3Rows = maps(p3.get("phaseEffect"));
        assertThat(p3Rows).singleElement().extracting(row -> row.get("phase")).isEqualTo("P3");
    }

    @Test
    void malformedCanonicalFactsFailClosedInsteadOfBecomingZeroValueEvents() {
        Map<String, Object> malformed = fact(
                "bad-money", "commission.paid", "100", "200", "2026-07-25T11:00:00",
                Map.of("amountUsdt", "not-a-number", "tier", "network"));

        assertThatThrownBy(() -> L4OperationsAnalytics.calculate(
                List.of(malformed), "week", "ALL", null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("L4_EVENT_FACT_INVALID");
    }

    @Test
    void missingNetworkCommissionSourceKeepsTriggerRateUnknownInsteadOfInventingZero() {
        List<Map<String, Object>> facts = List.of(
                fact("ref-2", "referral.bound", "200", null, "2026-07-25T10:00:00", Map.of()),
                fact("com-2", "commission.paid", "100", null, "2026-07-25T11:00:00",
                        Map.of("amountUsdt", "5", "tier", "network")));

        Map<String, Object> result = L4OperationsAnalytics.calculate(
                facts, "week", "ALL", null, null, NOW);

        assertThat(map(map(result.get("network")).get("summary")).get("commissionTriggerRate")).isNull();
    }

    private static Map<String, Object> fact(
            String eventId,
            String eventName,
            String actorId,
            String sourceActorId,
            String eventTs,
            Map<String, Object> extra) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("eventId", eventId);
        row.put("eventName", eventName);
        row.put("actorId", actorId);
        row.put("sourceActorId", sourceActorId);
        row.put("eventTs", eventTs);
        row.put("phase", extra.getOrDefault("phase", "P1"));
        row.putAll(extra);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
