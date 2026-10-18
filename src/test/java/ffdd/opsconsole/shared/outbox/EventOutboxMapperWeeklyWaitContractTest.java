package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class EventOutboxMapperWeeklyWaitContractTest {
    @Test
    void regionalWeeklyWaitUsesAccountAssignmentAndOriginalEnvelopeIdentity() throws Exception {
        String sql = sql();
        assertThat(sql).contains("d.status = #{pendingBindingStatus}",
                "FROM nx_growth_weekly_definition w", "LEFT JOIN nx_growth_account_weekly_period a",
                "a.instance_key=w.instance_key", "a.user_id=CASE",
                "BINARY w.instance_key=BINARY COALESCE(", "'$.instanceKey'", "'$.instance_key'",
                "o.is_server_authoritative=1");
        assertThat(sql).doesNotContain("DATE_FORMAT", "CONCAT('WEEK:'", "o.user_id", "a.is_deleted");
    }

    @Test
    void frozenBindingSelectsItsExactBeneficiarySlotRatherThanEitherPayloadAccount() throws Exception {
        String sql = sql();
        String ownership = sql.substring(sql.indexOf("AND a.user_id=CASE"), sql.indexOf("WHERE (a.user_id"));
        assertThat(ownership).contains(
                "WHEN wb.user_id_field='user_id' THEN JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.user_id'))",
                "WHEN wb.user_id_field='inviter_user_id' THEN JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.inviter_user_id'))",
                "ELSE NULL END");
        String threshold = ownership.substring(0, ownership.indexOf("WHEN wb.user_id_field"));
        assertThat(threshold).contains("H3_STOREFRONT_THREE_PRODUCTS_VIEWED", "H3_GENESIS_SECONDARY_MARKET_VIEWED",
                "H3_COMPUTE_COMPLETED_50", "H3_REFERRAL_REGISTERED", "H3_EXCHANGE_COMPLETED",
                "THEN JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.user_id'))");
        assertThat(threshold).doesNotContain("inviter_user_id");
        assertThat(sql).contains("OR wb.user_id_field='user_id'", "AND wb.instance_key IS NOT NULL",
                "AND wb.user_id_field IN ('user_id','inviter_user_id')");
        assertThat(ownership).doesNotContain("a.user_id IN", " OR ");
    }

    @Test
    void originalOccurrenceAndSourceTimesUseFrozenHalfOpenBoundsAndMillisecondPrecision() throws Exception {
        assertThat(sql()).contains("o.event_ts>=w.eligible_from AND o.event_ts<w.eligible_until",
                "'$.sourceOccurredAt'", "'$.source_occurred_at'", "CAST(REPLACE(LEFT(COALESCE(",
                "'null')),23), 'T',' ') AS DATETIME(3))>=w.eligible_from",
                "'null')),23), 'T',' ') AS DATETIME(3))<w.eligible_until",
                "CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')>=w.eligible_from",
                "CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')<w.eligible_until",
                "LEFT JOIN nx_growth_weekly_definition_binding wb",
                "wb.instance_key=w.instance_key AND wb.event_type=o.event_type");
    }

    @Test
    void expiredThresholdWaitDoesNotRequireCurrentMissionOrBindingToReachTerminalSkip() throws Exception {
        String sql = sql();
        String expiredBranch = sql.substring(sql.indexOf("AND ((w.eligible_until<="),
                sql.indexOf("OR (o.event_ts>=w.eligible_from"));
        assertThat(expiredBranch).contains("CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')",
                "H3_STOREFRONT_THREE_PRODUCTS_VIEWED", "H3_GENESIS_SECONDARY_MARKET_VIEWED",
                "H3_COMPUTE_COMPLETED_50", "H3_REFERRAL_REGISTERED", "H3_EXCHANGE_COMPLETED");
        assertThat(expiredBranch).doesNotContain("nx_mission", "definition_binding", "event_ts", "sourceOccurredAt");
        assertThat(sql).contains("o.status = #{publishedStatus}", "d.status = #{pendingBindingStatus}");
    }

    @Test
    void legacyWindowsAndDayOneRecoveryRemainAvailableWithoutInventedRegionalAssignments() throws Exception {
        String sql = sql();
        assertThat(sql).contains("a.user_id IS NOT NULL OR (w.calendar_version IS NULL",
                "w.instance_key REGEXP '^WEEK:[0-9]{4}-W[0-9]{2}$'",
                "'$.instance_key')),'null')) IS NULL AND o.event_ts>=w.eligible_from",
                "m.id IS NOT NULL AND o.event_type NOT IN (");
        assertThat(sql.substring(sql.indexOf("SET o.status"), sql.indexOf("WHERE o.event_type")))
                .doesNotContain("o.payload =", "o.event_ts =", "o.aggregate_id =", "o.retry_count =");
    }

    private String sql() throws Exception {
        return String.join(" ", EventOutboxMapper.class
                .getMethod("requeuePublishedPendingBinding", String.class, String.class,
                        String.class, String.class, String.class)
                .getAnnotation(Update.class).value()).replaceAll("\\s+", " ");
    }
}
