package ffdd.opsconsole.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import ffdd.opsconsole.growth.mapper.*;
import ffdd.opsconsole.team.mapper.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class VietnamBusinessPeriodMapperTest {
    @Test void changedMapperStatementsBuildWithExplicitStorageBoundsAndKeepPlusEightTransport() {
        var config=new Configuration();
        for (Class<?> mapper : new Class<?>[]{TeamCommissionMapper.class,AppTeamNetworkMapper.class,
                AppTeamInsightsMapper.class,AppGrowthEngagementMapper.class,QuestCompletionFactMapper.class,
                WeeklyQuestSnapshotMapper.class}) config.addMapper(mapper);
        String current=config.getMappedStatement(TeamCommissionMapper.class.getName()+".currentYearWeek").getBoundSql(null).getSql();
        // R-1: the growth/team cut is read from nx_business_calendar (Vietnam from 2026-10-19 is a seed row there).
        assertThat(current).contains("CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')","nx_business_calendar","'GROWTH_TEAM','GLOBAL'");
        String state=config.getMappedStatement(WeeklyQuestSnapshotMapper.class.getName()+".state").getBoundSql(null).getSql();
        assertThat(state).contains("%Y-%m-%dT%H:%i:%s+08:00");
        String summary=config.getMappedStatement(TeamCommissionMapper.class.getName()+".binarySettlementSummary").getBoundSql(null).getSql();
        assertThat(summary).contains("nx_binary_volume_cursor","matched_volume","nx_business_calendar");
    }
}
