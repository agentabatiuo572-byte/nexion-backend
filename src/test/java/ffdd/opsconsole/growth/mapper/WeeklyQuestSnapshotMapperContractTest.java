package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class WeeklyQuestSnapshotMapperContractTest {
    @Test
    void regionalNaturalKeyAndAccountMembershipAreBoundParameters() {
        Configuration config=new Configuration();
        config.addMapper(WeeklyQuestSnapshotMapper.class);
        String namespace=WeeklyQuestSnapshotMapper.class.getName()+".";
        var args=Map.of("version","PHONE_REGION_V1","zone","America/New_York","monday",LocalDate.of(2026,10,26),
                "from",LocalDateTime.of(2026,10,26,12,0),"until",LocalDateTime.of(2026,11,2,13,0));
        String sql=config.getMappedStatement(namespace+"regional").getBoundSql(args).getSql();
        assertThat(sql).contains("business_timezone=?","local_week_start=?","eligible_from=?","eligible_until=?","FOR SHARE");
        assertThat(config.getMappedStatement(namespace+"assigned").getBoundSql(Map.of("userId",1L,"key","WEEK:abc")).getSql())
                .contains("user_id=? AND instance_key=?");
        assertThat(config.getMappedStatement(namespace+"state").getBoundSql(Map.of("userId",1L,"key","WEEK:abc","locale","en")).getSql())
                .contains("+08:00","businessTimezone");
        assertThat(config.getMappedStatement(namespace+"weeklyCode").getBoundSql(Map.of("code","weekly_t2_browse_store")).getSql())
                .contains("OR EXISTS(SELECT 1 FROM nx_growth_weekly_definition_item WHERE quest_code=?)");
    }
    @Test
    void schemaIsAdditiveAndRetiredScriptCannotRewriteRewardsOrLegacyDeadline() throws Exception {
        String sql=Files.readString(Path.of("scripts/migrations/20261018_h3_regional_weekly_period.sql"));
        assertThat(sql).contains("PRIMARY KEY(user_id,local_week_start)","UNIQUE KEY uk_account_weekly_instance(user_id,instance_key)",
                "uk_weekly_region_window(calendar_version,business_timezone,local_week_start,eligible_from,eligible_until)",
                "FOREIGN KEY(instance_key)");
        assertThat(sql.toUpperCase()).doesNotContain("UPDATE NX_USER_MISSION","UPDATE NX_GROWTH_WEEKLY_DEFINITION","DELETE FROM");
        assertThat(Files.readAllLines(Path.of("scripts/manual_migrations/20261018_vietnam_business_period_transition.sql")))
                .allMatch(line->line.isBlank() || line.replace("\uFEFF","").startsWith("--"));
    }
}
