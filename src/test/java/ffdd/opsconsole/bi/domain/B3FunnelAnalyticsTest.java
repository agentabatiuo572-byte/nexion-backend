package ffdd.opsconsole.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class B3FunnelAnalyticsTest {

    @Test
    void buildsFiveStrictSameActorStagesAndCanonicalAuxMetrics() {
        LocalDateTime mature = LocalDateTime.now().minusDays(14);
        Map<String, Object> result = B3FunnelAnalytics.calculate(List.of(
                event("auth.register_completed", "kept", mature, "P3", "partner-a"),
                event("kyc.express_verified", "kept", mature.plusHours(1), "P3", "partner-a"),
                event("checkout.completed", "kept", mature.plusHours(2), "P3", "partner-a"),
                event("wallet.reinvest", "kept", mature.plusHours(3), "P3", "partner-a"),
                event("withdraw.submitted", "kept", mature.plusHours(4), "P3", "partner-a"),
                event("device.first_yield_received", "kept", mature.plusSeconds(80), "P3", "partner-a", 80),
                event("app.dau", "kept", mature.plusDays(7), "P3", "partner-a"),
                event("auth.register_completed", "wrong-phase", mature, "P4", "partner-a"),
                event("withdraw.submitted", "orphan", mature, "P3", "partner-a")),
                null, "P3", "partner-a");

        assertThat(result).containsEntry("available", true);
        assertThat(rows(result.get("stages")))
                .extracting(row -> row.get("stage"))
                .containsExactly("注册", "绑卡", "首购", "复投", "提现");
        assertThat(rows(result.get("stages")))
                .extracting(row -> row.get("distinctUsers"))
                .containsExactly(1, 1, 1, 1, 1);
        assertThat(map(result.get("auxMetrics")))
                .containsEntry("day0AccessRate", 100D)
                .containsEntry("day7Retention", 100D);
        assertThat(map(result.get("filters")))
                .containsEntry("phase", "P3")
                .containsEntry("ref", "partner-a");
    }

    @Test
    void keepsImmatureDay7AndZeroDenominatorRatesUnavailable() {
        Map<String, Object> result = B3FunnelAnalytics.calculate(List.of(
                event("auth.register_completed", "new-user", LocalDateTime.now().minusHours(2), "P2", "direct")),
                null, "P2", "direct");

        assertThat(map(result.get("auxMetrics")).get("day7Retention")).isNull();
        assertThat(rows(result.get("stages")).get(1).get("cvrFromPrev")).isEqualTo(0D);
        assertThat(result.toString()).doesNotContain("NaN", "Infinity");
    }

    @Test
    void supportsSecondCheckoutAsRepurchaseOnlyAfterTheFirstPurchase() {
        LocalDateTime at = LocalDateTime.now().minusDays(20);
        Map<String, Object> result = B3FunnelAnalytics.calculate(List.of(
                event("auth.register_completed", "repeat", at, "P1", "direct"),
                event("kyc.express_verified", "repeat", at.plusHours(1), "P1", "direct"),
                event("checkout.completed", "repeat", at.plusHours(2), "P1", "direct"),
                event("checkout.completed", "repeat", at.plusHours(3), "P1", "direct")),
                null, "P1", "direct");

        assertThat(rows(result.get("stages")).get(3).get("distinctUsers")).isEqualTo(1);
    }

    private static Map<String, Object> event(
            String name, String actor, LocalDateTime at, String phase, String ref) {
        return event(name, actor, at, phase, ref, null);
    }

    private static Map<String, Object> event(
            String name, String actor, LocalDateTime at, String phase, String ref, Integer latencySec) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventName", name);
        row.put("actorId", actor);
        row.put("eventTs", at);
        row.put("cohort", "");
        row.put("phase", phase);
        row.put("refCode", ref);
        row.put("latencySec", latencySec);
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
}
