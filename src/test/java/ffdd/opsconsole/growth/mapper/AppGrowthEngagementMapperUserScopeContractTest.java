package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class AppGrowthEngagementMapperUserScopeContractTest {

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
        Method lock = AppGrowthEngagementMapper.class.getMethod("lockClaimableQuest", Long.class, String.class);
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
}
