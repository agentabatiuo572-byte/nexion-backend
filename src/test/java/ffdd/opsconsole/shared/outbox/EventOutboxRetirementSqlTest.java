package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/**
 * Renders the record-only retirement statement through the same MyBatis
 * annotation driver Spring uses at runtime.
 *
 * <p>The earlier coverage mocked {@link EventOutboxService}, so this SQL was
 * never built. A {@code <foreach>} outside a {@code <script>} block is not
 * dynamic SQL: the driver ships it as literal text, MySQL rejects the statement
 * on every tick, the scheduler catches the exception and logs a warning, and
 * the backlog silently stays in PENDING. Parsing the statement is what proves
 * the tags are interpreted and every recorded type is bound.
 */
class EventOutboxRetirementSqlTest {

    @Test
    void retirementSqlBindsEveryRecordedEventTypeIntoAnInClause() throws Exception {
        List<String> eventTypes = List.copyOf(EventOutboxService.RECORD_ONLY_EVENT_TYPES);
        String raw = renderRetirementSql(eventTypes);

        assertThat(raw)
                .as("dynamic tags must be parsed by the script driver, never shipped as SQL text")
                .doesNotContain("<foreach")
                .doesNotContain("</foreach>")
                .doesNotContain("<script>");

        String sql = compact(raw);
        String boundTypes = String.join(",", Collections.nCopies(eventTypes.size(), "?"));
        assertThat(sql)
                .as("every recorded type must reach the exact-match IN clause")
                .contains("event_typeIN(" + boundTypes + ")");
        assertThat(raw.chars().filter(ch -> ch == '?').count())
                .as("SET status, SET last_error, status IN (2), event_type IN (N), INTERVAL, LIMIT")
                .isEqualTo(eventTypes.size() + 6);
        assertThat(sql).contains(
                "UPDATEnx_event_outbox",
                "status=?",
                "last_error=?",
                "created_at<DATE_SUB(NOW(),INTERVAL?MINUTE)",
                "LIMIT?");
    }

    @Test
    void retirementSqlMatchesEventTypesExactlyRatherThanByPrefix() throws Exception {
        String sql = compact(renderRetirementSql(List.of("JANUS_STRATEGY_PUBLISH")));

        // IN is an exact comparison: a bare prefix such as "JANUS_STRATEGY_" would
        // bind a value no row can equal, so the fact would never be retired.
        assertThat(sql).contains("event_typeIN(?)");
        assertThat(sql).doesNotContain("LIKE");
    }

    /**
     * Legacy C1 profile facts without their audit link are refused by the
     * dispatch scan and are not retryable, so only this sweep can end them. It
     * must stay byte-exact on the audit link (an alias row must not mask a real
     * gap) and must never touch a linked row.
     */
    @Test
    void unlinkedProfileSweepBindsTheAuditLinkByteExact() throws Exception {
        var method = EventOutboxMapper.class.getDeclaredMethod("retireUnlinkedC1ProfileEvidence",
                String.class, int.class, int.class, String.class, String.class, String.class, String.class);
        String script = String.join("\n", method.getAnnotation(Update.class).value());
        Map<String, Object> params = new HashMap<>();
        params.put("eventType", "ADMIN_USER_PROFILE_VIEWED");
        params.put("graceMinutes", 15);
        params.put("limit", 100);
        params.put("reason", "C1_AUDIT_EVIDENCE_UNLINKED");
        params.put("recordedStatus", "RECORDED");
        params.put("pendingStatus", "PENDING");
        params.put("failedStatus", "FAILED");
        String raw = new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class)
                .getBoundSql(params).getSql();
        String sql = compact(raw);

        assertThat(raw).doesNotContain("<script>").doesNotContain("&lt;");
        assertThat(sql).contains(
                "SETstatus=?",
                "last_error=?",
                "statusIN(?,?)",
                "BINARYo.event_type=BINARY?",
                "created_at<DATE_SUB(NOW(),INTERVAL?MINUTE)",
                "LIMIT?",
                // The link predicate must be byte-exact on both sides, matching A4.
                "a.biz_no=CONCAT('C1-VIEW-',o.event_id)",
                "BINARYa.biz_no=BINARYCONCAT('C1-VIEW-',o.event_id)",
                "NOTEXISTS");
        assertThat(sql).doesNotContain("LIKE");
    }

    /** Tag bodies carry their own newlines and indentation; compare on whitespace-free text. */
    private static String compact(String sql) {
        return sql.replaceAll("\\s+", "");
    }

    private static String renderRetirementSql(List<String> eventTypes) throws Exception {
        var method = EventOutboxMapper.class.getDeclaredMethod("retireRecordOnlyPending",
                List.class, int.class, int.class, String.class, String.class, String.class, String.class);
        String script = String.join("\n", method.getAnnotation(Update.class).value());

        Map<String, Object> params = new HashMap<>();
        params.put("eventTypes", eventTypes);
        params.put("graceMinutes", EventOutboxService.RECORD_ONLY_GRACE_MINUTES);
        params.put("limit", 100);
        params.put("reason", "EVENT_RECORDED_NO_BUS_CONSUMER");
        params.put("recordedStatus", "RECORDED");
        params.put("pendingStatus", "PENDING");
        params.put("failedStatus", "FAILED");

        BoundSql bound = new XMLLanguageDriver()
                .createSqlSource(new Configuration(), script, Map.class)
                .getBoundSql(params);
        return bound.getSql();
    }
}
