package ffdd.opsconsole.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

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
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(1);
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

    @Test
    void day7UsesMatureRegistrationsBeforeTheObservationWindow() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime registeredAt = now.minusDays(8);
        List<Map<String, Object>> facts = List.of(
                event("auth.register_completed", "mature", registeredAt, null, 1),
                event("app.dau", "mature", registeredAt.plusDays(7), null, 1));

        List<Map<String, Object>> kpis = rows(L1KpiAnalytics
                .calculate(facts, "7d", null, null, null, null).get("kpis"));

        assertThat(kpis.get(1))
                .containsEntry("value", 100D)
                .containsEntry("numerator", 1L)
                .containsEntry("denominator", 1L);
    }

    @Test
    void lifecycleKpisRejectOutOfOrderFactsAndAnchorDimensionsAtTheDenominatorEvent() {
        LocalDateTime now = LocalDateTime.now().minusHours(2);
        Map<String, Object> registration = event("auth.register_completed", "u1", now.plusMinutes(1), null, 1);
        registration.put("phase", "P3");
        Map<String, Object> earlyStore = event("store.viewed", "u1", now, null, 1);
        earlyStore.put("phase", "P4");
        Map<String, Object> laterStore = event("store.viewed", "u1", now.plusMinutes(2), null, 1);
        laterStore.put("phase", "P4");
        Map<String, Object> checkout = event("checkout.completed", "u1", now.plusMinutes(3), null, 1);
        checkout.put("phase", "P5");

        List<Map<String, Object>> kpis = rows(L1KpiAnalytics.calculate(
                List.of(earlyStore, registration, laterStore, checkout),
                "7d", null, "P3", null, null).get("kpis"));

        assertThat(kpis.get(2)).containsEntry("value", 100D);
        assertThat(kpis.get(3)).containsEntry("value", null);
    }

    @Test
    void exactDuplicateEventIdsDoNotInflateGenesisAndMalformedFactsFailClosed() {
        LocalDateTime now = LocalDateTime.now().minusDays(2);
        Map<String, Object> first = event("genesis.purchased", "u1", now, null, 600);
        first.put("eventId", "evt-1");
        Map<String, Object> duplicate = new LinkedHashMap<>(first);
        Map<String, Object> second = event("genesis.purchased", "u2", now.plusDays(1), null, 400);
        second.put("eventId", "evt-2");

        List<Map<String, Object>> kpis = rows(L1KpiAnalytics.calculate(
                List.of(first, duplicate, second), "7d", null, null, null, null).get("kpis"));
        assertThat(kpis.get(7))
                .containsEntry("value", 2D)
                .containsEntry("numerator", 1000L);

        Map<String, Object> malformed = event(
                "device.first_yield_received", "", now, 30D, 1);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> L1KpiAnalytics.calculate(
                        List.of(malformed), "7d", null, null, null, null));
    }

    @Test
    void customWindowIsExactAndInvalidRangesDoNotFallBackToSevenDays() {
        LocalDateTime inside = LocalDateTime.now().minusDays(3);
        LocalDateTime outside = LocalDateTime.now().minusDays(20);
        String from = inside.minusDays(1).toLocalDate().toString();
        String to = inside.plusDays(1).toLocalDate().toString();

        List<Map<String, Object>> kpis = rows(L1KpiAnalytics.calculate(
                List.of(
                        event("auth.register_completed", "inside", inside, null, 1),
                        event("auth.register_completed", "outside", outside, null, 1)),
                "custom|" + from + "|" + to,
                null, null, null, null).get("kpis"));

        assertThat(kpis.get(0)).containsEntry("denominator", 1L);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> L1KpiAnalytics.calculate(
                        List.of(), "custom|2026-07-20|2026-07-01",
                        null, null, null, null));
    }

    @Test
    void kpiSevenMatchesFirstNetworkCommissionBySourceActorAfterReferralBinding() {
        LocalDateTime now = LocalDateTime.now().minusHours(2);
        Map<String, Object> referralOne = event("referral.bound", "buyer-1", now, null, 1);
        Map<String, Object> referralTwo = event("referral.bound", "buyer-2", now, null, 1);
        Map<String, Object> beforeBinding = event(
                "commission.paid", "sponsor-1", now.minusMinutes(1), null, 1);
        beforeBinding.put("sourceActorId", "buyer-1");
        beforeBinding.put("commissionKind", "network");
        Map<String, Object> firstNetwork = event(
                "commission.paid", "sponsor-1", now.plusMinutes(1), null, 1);
        firstNetwork.put("sourceActorId", "buyer-1");
        firstNetwork.put("commissionKind", "network");
        Map<String, Object> replayWithAnotherEventId = new LinkedHashMap<>(firstNetwork);
        firstNetwork.put("eventId", "commission-1");
        replayWithAnotherEventId.put("eventId", "commission-2");
        Map<String, Object> nonNetwork = event(
                "commission.paid", "sponsor-2", now.plusMinutes(1), null, 1);
        nonNetwork.put("sourceActorId", "buyer-2");
        nonNetwork.put("commissionKind", "binary");

        List<Map<String, Object>> kpis = rows(L1KpiAnalytics.calculate(
                List.of(referralOne, referralTwo, beforeBinding, firstNetwork, replayWithAnotherEventId, nonNetwork),
                "7d", null, null, null, null).get("kpis"));

        assertThat(kpis.get(6))
                .containsEntry("value", 50D)
                .containsEntry("numerator", 1L)
                .containsEntry("denominator", 2L);
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
