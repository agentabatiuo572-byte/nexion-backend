package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class AppGrowthEngagementMapperUserScopeContractTest {

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
                .contains("order by candidate.updated_at desc,candidate.id desc limit 1")
                .contains("m.status=1 or historical.id is not null")
                .contains("when q.definition_status<>1 then 0")
                .contains("when q.definition_status<>1 then case")
                .contains("when 'claimed' then 'claimed'")
                .contains("else 'expired'");
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
