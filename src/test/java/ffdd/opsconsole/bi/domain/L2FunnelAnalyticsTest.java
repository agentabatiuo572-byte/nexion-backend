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

    private static Map<String, Object> event(String name, String actor, LocalDateTime at) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventName", name);
        row.put("actorId", actor);
        row.put("eventTs", at);
        row.put("cohort", "");
        row.put("phase", "P3");
        row.put("locale", "zh");
        row.put("refCode", "direct");
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
