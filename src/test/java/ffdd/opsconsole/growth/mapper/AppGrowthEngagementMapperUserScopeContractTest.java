package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class AppGrowthEngagementMapperUserScopeContractTest {

    @Test
    void wheelReceiptProjectionParsesWithBoundUserAndLocaleParameters() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(AppGrowthEngagementMapper.class);
        var bound = configuration.getMappedStatement(AppGrowthEngagementMapper.class.getName() + ".eventState")
                .getBoundSql(Map.of("userId", 60723153007L, "locale", "zh"));

        assertThat(CCJSqlParserUtil.parse(bound.getSql())).isNotNull();
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("locale", "locale", "locale", "userId", "userId", "userId");
        assertThat(bound.getSql()).doesNotContain("60723153007");
    }

    @Test
    void wheelParticipationUsesTheCurrentUserAndExactEventReceiptWithoutADailyReset() throws Exception {
        String sql = String.join(" ", AppGrowthEngagementMapper.class
                .getMethod("eventState", Long.class, String.class).getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ").toLowerCase();
        String receiptScope = "select 1 from nx_growth_wheel_spin s where s.user_id=#{userid} "
                + "and s.event_id=q.id and s.event_code=q.quest_code and s.is_deleted=0";

        assertThat(sql.split(java.util.regex.Pattern.quote(receiptScope), -1)).hasSize(3);
        assertThat(sql).contains("case when lower(q.target_type)='wheel' then 1 else q.target_value end targetvalue")
                .contains("when u.id is not null or (lower(q.target_type)='wheel' and exists (")
                .contains("lower(q.target_type)<>'wheel'")
                .doesNotContain("spin_date", "insert ", "update ");
        int claimed = sql.indexOf("then 'claimed'");
        int claimable = sql.indexOf("then 'claimable'");
        int joined = sql.indexOf("then 'joined'");
        assertThat(claimed).isNotNegative().isLessThan(claimable);
        assertThat(claimable).isLessThan(joined);
    }

    @Test
    void wheelUsesTheSameExclusiveDeadlineAsEventClaims() throws Exception {
        for (String method : new String[] {"lockOpenWheelEvent", "findOpenWheelEvent"}) {
            String sql = String.join(" ", AppGrowthWheelMapper.class.getMethod(method, String.class)
                    .getAnnotation(Select.class).value());
            assertThat(sql).contains("starts_at<=UTC_TIMESTAMP()", "ends_at>UTC_TIMESTAMP()")
                    .doesNotContain("ends_at>=");
        }
    }

    @Test
    void eventClaimEnforcesTheUtcWindowInBothLockAndFinalWrite() throws Exception {
        String lock = String.join(" ", AppGrowthEngagementMapper.class
                .getMethod("lockClaimableEvent", Long.class, String.class).getAnnotation(Select.class).value());
        String update = String.join(" ", AppGrowthEngagementMapper.class
                .getMethod("claimEvent", Long.class, String.class).getAnnotation(Update.class).value());
        assertThat(lock).contains("q.starts_at<=UTC_TIMESTAMP()", "q.ends_at>UTC_TIMESTAMP()");
        assertThat(update).contains("starts_at<=UTC_TIMESTAMP()", "ends_at>UTC_TIMESTAMP()", "status=1");
    }

    @Test
    void canonicalQuestReadsAcceptAnyActiveDevelopmentAccount() throws Exception {
        Method method = AppGrowthEngagementMapper.class.getMethod("findActiveUser", Long.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ").toLowerCase();

        assertThat(sql)
                .contains("status='active'")
                .contains("is_deleted=0")
                .doesNotContain("sandbox");
    }

    @Test
    void contentProjectionUsesRequestedLocaleAndFallsBackToExistingBusinessText() throws Exception {
        Method quest = AppGrowthEngagementMapper.class.getMethod("questState", Long.class, String.class);
        Method event = AppGrowthEngagementMapper.class.getMethod("eventState", Long.class, String.class);
        String questSql = String.join(" ", quest.getAnnotation(Select.class).value()).replaceAll("\\s+", " ").toLowerCase();
        String eventSql = String.join(" ", event.getAnnotation(Select.class).value()).replaceAll("\\s+", " ").toLowerCase();

        assertThat(questSql).contains("growth.content.localized", "json_valid(config_value)", "#{locale}", "q.mission_name");
        assertThat(eventSql).contains("growth.content.localized", "json_valid(config_value)", "#{locale}", "q.quest_name", "q.description", "q.reward_name");
    }

    @Test
    void questReadAndClaimAreScopedToTheCurrentEligibilityInstance() throws Exception {
        Method state = AppGrowthEngagementMapper.class.getMethod("questState", Long.class, String.class);
        Method lock = AppGrowthEngagementMapper.class.getMethod("lockClaimableQuest", Long.class, String.class, String.class);
        Method claim = AppGrowthEngagementMapper.class.getMethod("claimQuest", Long.class, Long.class, String.class);
        String stateSql = String.join(" ", state.getAnnotation(Select.class).value()).replaceAll("\\s+", " ").toLowerCase();
        String lockSql = String.join(" ", lock.getAnnotation(Select.class).value()).replaceAll("\\s+", " ").toLowerCase();
        String claimSql = String.join(" ", claim.getAnnotation(Update.class).value()).replaceAll("\\s+", " ").toLowerCase();

        assertThat(stateSql)
                .contains("day_one:", "week:", "growth.quest.day_one.eligibility_hours")
                .contains("um.instance_key=q.instance_key")
                .contains("then 'expired'")
                .doesNotContain("where now()<q.eligible_until");
        assertThat(lockSql)
                .contains("um.instance_key=#{instancekey}")
                .contains("um.instance_key=case")
                .contains("now()<date_add(u.created_at")
                .contains("for update");
        assertThat(claimSql).contains("instance_key=#{instancekey}");
    }

    @Test
    void disabledMissionKeepsItsLatestUserInstanceAsReadOnlyHistory() throws Exception {
        Method state = AppGrowthEngagementMapper.class.getMethod("questState", Long.class, String.class);
        String sql = String.join(" ", state.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ").toLowerCase();

        assertThat(sql)
                .contains("left join nx_user_mission historical")
                .contains("order by (candidate.instance_key=current_week.instance_key)")
                .contains("m.status=1 or historical.id is not null")
                .contains("when q.definition_status<>1 then 0")
                .contains("when q.definition_status<>1 then case")
                .contains("when 'claimed' then 'claimed'")
                .contains("else 'expired'");
    }

    @Test
    void pausedWeeklyClaimRequiresSameWeekAuthoritativeCompletionFact() throws Exception {
        String state = String.join(" ", AppGrowthEngagementMapper.class
                .getMethod("questState", Long.class, String.class).getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ").toLowerCase();
        String lock = String.join(" ", AppGrowthEngagementMapper.class
                .getMethod("lockClaimableQuest", Long.class, String.class, String.class)
                .getAnnotation(Select.class).value()).replaceAll("\\s+", " ").toLowerCase();
        for (String sql : new String[] {state, lock}) {
            assertThat(sql).contains("nx_growth_quest_completion_fact cf", "nx_event_outbox o",
                    "o.is_server_authoritative=1", "proof_period.user_id=cf.user_id",
                    "proof_period.instance_key=cf.instance_key", "proof_week.eligible_from<=o.event_ts", "proof_week.eligible_until>o.event_ts",
                    "cf.producer='system'", "cf.quest_code=m.mission_code");
        }
        assertThat(lock).contains("m.status=0", "um.instance_key=case",
                "upper(um.mission_status) in ('completed','claimable')").doesNotContain("b.status=1");
        assertThat(state).contains("q.paused_claim_verified=1", "then 'claimable'");
    }

    @Test
    void dayOneSnapshotStateAndClaimNeverRejoinLiveMissionDefinitions() throws Exception {
        Method state = AppGrowthEngagementMapper.class.getMethod("dayOneSnapshotState", Long.class, Long.class);
        Method lock = AppGrowthEngagementMapper.class.getMethod(
                "lockDayOneSnapshotGroup", Long.class, Long.class, String.class);
        Method claim = AppGrowthEngagementMapper.class.getMethod(
                "claimDayOneSnapshotGroup", Long.class, Long.class, String.class);
        String stateSql = String.join(" ", state.getAnnotation(Select.class).value()).replaceAll("\\s+", " ").toLowerCase();
        String lockSql = String.join(" ", lock.getAnnotation(Select.class).value()).replaceAll("\\s+", " ").toLowerCase();
        String claimSql = String.join(" ", claim.getAnnotation(Update.class).value()).replaceAll("\\s+", " ").toLowerCase();

        assertThat(stateSql).contains("nx_growth_day_one_instance_item", "item.name", "item.category", "item.action_route", "item.reward_points")
                .doesNotContain("join nx_mission");
        assertThat(lockSql).contains("nx_growth_day_one_instance_item", "for update")
                .doesNotContain("join nx_mission");
        assertThat(claimSql).contains("nx_growth_day_one_instance_item", "upper(um.mission_status) in ('completed','claimable')")
                .doesNotContain("join nx_mission");
    }
}
