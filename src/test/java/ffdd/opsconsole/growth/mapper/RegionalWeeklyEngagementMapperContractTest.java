package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.Map;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** Actual MyBatis assembly and SQL parsing, with no external database connection. */
class RegionalWeeklyEngagementMapperContractTest {
    private final Configuration configuration = new Configuration();
    private final Map<String,Object> params = Map.of("userId",11L,"questCode","weekly_t2_browse_store",
            "instanceKey","WEEK:0123456789abcdef0123456789abcdef","missionId",29L,"locale","vi",
            "occurredAt",LocalDateTime.of(2026,10,26,0,30));
    private static final String STORAGE_NOW = "CONVERT_TZ(UTC_TIMESTAMP(6),'+00:00','+08:00')";

    RegionalWeeklyEngagementMapperContractTest() {
        configuration.addMapper(AppGrowthEngagementMapper.class);
        configuration.addMapper(QuestCompletionFactMapper.class);
    }
    BoundSql bound(Class<?> mapper,String method) {
        return configuration.getMappedStatement(mapper.getName()+"."+method).getBoundSql(params);
    }
    String sql(Class<?> mapper,String method) { return bound(mapper,method).getSql(); }

    @Test void allProductionWeeklyStatementsParseWithoutComputingGlobalWeekKeys() throws Exception {
        for (String method : new String[]{"questState","lockClaimableQuest","lockQuestClaimState","claimQuest"}) {
            String sql = sql(AppGrowthEngagementMapper.class,method);
            assertThat(sql).contains("nx_growth_account_weekly_period","nx_growth_weekly_definition",STORAGE_NOW)
                    .doesNotContain("CONCAT('WEEK:'", "DATE_SUB(o.event_ts", "INTERVAL 1 HOUR");
            assertThat(CCJSqlParserUtil.parse(sql)).isNotNull();
        }
        for (String method : new String[]{"lockMissionInstance","lockMissionInstanceAt"}) {
            String sql = sql(QuestCompletionFactMapper.class,method);
            assertThat(sql).contains("nx_growth_account_weekly_period","nx_growth_weekly_definition",STORAGE_NOW,
                    "wp.user_id=u.id", "ELSE wh.instance_key", "wi.source_mission_id=m.id", "wi.instance_key IS NOT NULL")
                    .doesNotContain("CONCAT('WEEK:'");
            assertThat(CCJSqlParserUtil.parse(sql)).isNotNull();
        }
    }

    @Test void claimLockDiagnosticAndFinalCasUseSameAccountAndHalfOpenFrozenWindow() {
        for (String method : new String[]{"lockClaimableQuest","lockQuestClaimState","claimQuest"}) {
            String sql = sql(AppGrowthEngagementMapper.class,method);
            assertThat(sql).contains("wh.instance_key=wp.instance_key",
                    "wh.eligible_from<="+STORAGE_NOW,"wh.eligible_until>"+STORAGE_NOW)
                    .doesNotContain("wh.eligible_until>=");
            assertThat(bound(AppGrowthEngagementMapper.class,method).getParameterMappings())
                    .extracting(mapping -> mapping.getProperty()).contains("userId","instanceKey");
        }
        assertThat(sql(AppGrowthEngagementMapper.class,"lockQuestClaimState"))
                .contains("m.mission_type<>'DAY_ONE' AND wh.instance_key IS NULL THEN 1");
        assertThat(sql(AppGrowthEngagementMapper.class,"claimQuest"))
                .contains("wp.user_id=? AND wp.instance_key=?", "UPPER(mission_status) IN ('COMPLETED','CLAIMABLE')");
    }

    @Test void sourceFactMustBelongToAccountWindowAtOccurrenceAndStillBeOpenOnDelivery() {
        String sql = sql(QuestCompletionFactMapper.class,"lockMissionInstanceAt");
        assertThat(sql).contains("wh.eligible_from<=? AND wh.eligible_until>?",
                "?<=CONVERT_TZ(UTC_TIMESTAMP(6),'+00:00','+08:00')",
                "wh.eligible_from<="+STORAGE_NOW,"wh.eligible_until>"+STORAGE_NOW);
        assertThat(bound(QuestCompletionFactMapper.class,"lockMissionInstanceAt").getParameterMappings())
                .extracting(mapping -> mapping.getProperty()).contains("userId","occurredAt","questCode");
    }

    @Test void pausedProofUsesPersistedAccountMembershipAndActualSourceTimeWithoutReplayingHistory() {
        for (String method : new String[]{"questState","lockClaimableQuest"}) {
            assertThat(sql(AppGrowthEngagementMapper.class,method)).contains("proof_period.user_id=cf.user_id",
                    "proof_period.instance_key=cf.instance_key", "proof_week.eligible_from<=o.event_ts",
                    "proof_week.eligible_until>o.event_ts", "o.is_server_authoritative=1", "cf.producer='SYSTEM'")
                    .doesNotContain("cf.instance_key=CONCAT");
        }
        assertThat(sql(AppGrowthEngagementMapper.class,"questState"))
                .contains("ELSE current_week.instance_key", "ELSE current_week.eligible_from", "ELSE current_week.eligible_until",
                        "current_period.user_id=u.id", "historical.instance_key REGEXP '^WEEK:[0-9]{4}-W[0-9]{2}$'",
                        "WHEN m.status<>1 THEN historical.instance_key", "THEN 'CLAIMED'");
    }
}
