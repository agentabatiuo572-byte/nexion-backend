package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class NovaSocialRuntimeMapperContractTest {
    @Test
    void enqueueRechecksRuntimeStateUsesUserLanguageCooldownIdempotencyAndQueuedStatus() throws Exception {
        Method method = Arrays.stream(NovaSocialRuntimeMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("enqueueNotifications"))
                .findFirst().orElseThrow();
        String sql = String.join("\n", method.getAnnotation(Insert.class).value());

        assertThat(sql)
                .contains("INSERT IGNORE INTO nx_notification")
                .contains("e.status = 'ACTIVE'")
                .contains("e.expires_at > #{now}")
                .contains("c.channel_key = 'social'")
                .contains("c.enabled = 1")
                .contains("t.status = 'PUBLISHED'")
                .contains("LOWER(COALESCE(u.language, '')) LIKE 'vi%'")
                .contains("LOWER(COALESCE(u.language, '')) LIKE 'zh%'")
                .contains("previous.created_at > #{cooldownSince}")
                .contains("'QUEUED'")
                .doesNotContain("${")
                .doesNotContain("DELIVERED");
    }

    @Test
    void dispatchCounterOnlyChangesForStillActiveUnexpiredEvent() throws Exception {
        Method method = NovaSocialRuntimeMapper.class.getMethod(
                "markDispatchedIfStillActive", long.class, LocalDateTime.class);
        String sql = String.join("\n", method.getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("dispatch_count = dispatch_count + 1")
                .contains("status = 'ACTIVE'")
                .contains("expires_at > #{now}")
                .doesNotContain("${");
    }

    @Test
    void deletedSourceEventsRemainPermanentlyDeduplicatedAndWeeklyMetricCountsDispatches() throws Exception {
        Method sourceCount = NovaMapper.class.getMethod(
                "socialEventSourceCount", String.class, String.class, String.class);
        String sourceSql = String.join("\n", sourceCount.getAnnotation(Select.class).value());
        String statsSql = String.join("\n", NovaMapper.class.getMethod("stats")
                .getAnnotation(Select.class).value());

        assertThat(sourceSql)
                .contains("source_event_id = #{sourceEventId}")
                .doesNotContain("is_deleted = 0")
                .doesNotContain("${");
        assertThat(statsSql)
                .contains("SUM(e.dispatch_count)")
                .contains("e.last_dispatched_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)");
    }

    @Test
    void runtimeSlotUsesUniqueInsertAndOnlyExpiredIncompleteLeaseCanBeTakenOver() throws Exception {
        String createSql = String.join("\n", NovaSocialRuntimeMapper.class.getMethod("createRuntimeSlotTable")
                .getAnnotation(Update.class).value());
        String insertSql = String.join("\n", NovaSocialRuntimeMapper.class.getMethod(
                        "insertSlotClaim", String.class, String.class, LocalDateTime.class, LocalDateTime.class)
                .getAnnotation(Insert.class).value());
        String takeoverSql = String.join("\n", NovaSocialRuntimeMapper.class.getMethod(
                        "takeoverExpiredSlot", String.class, String.class, LocalDateTime.class, LocalDateTime.class)
                .getAnnotation(Update.class).value());

        assertThat(createSql).contains("UNIQUE KEY uk_nova_social_runtime_slot (slot_key)");
        assertThat(insertSql).contains("INSERT IGNORE INTO nx_nova_social_runtime_slot").doesNotContain("${");
        assertThat(takeoverSql)
                .contains("completed_at IS NULL")
                .contains("lease_until <= #{now}")
                .doesNotContain("${");
    }
}
