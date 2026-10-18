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
        assertThat(current).contains("CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')","INTERVAL 1 HOUR","2026-10-19");
        String state=config.getMappedStatement(WeeklyQuestSnapshotMapper.class.getName()+".state").getBoundSql(null).getSql();
        assertThat(state).contains("%Y-%m-%dT%H:%i:%s+08:00");
        String summary=config.getMappedStatement(TeamCommissionMapper.class.getName()+".binarySettlementSummary").getBoundSql(null).getSql();
        assertThat(summary).contains("nx_binary_volume_cursor","matched_volume","2026-10-19");
    }
}
