package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class WeeklyRegionPolicyMapperContractTest {
    @Test void actualPreviewStatementOnlyReadsPersistedOpenLegacyIntervalsAndDetectsOverlap() {
        var configuration = new Configuration();
        configuration.addMapper(WeeklyRegionPolicyMapper.class);
        var statement = configuration.getMappedStatement(WeeklyRegionPolicyMapper.class.getName()+".openLegacy");
        var sql = statement.getBoundSql(Map.of("now",LocalDateTime.of(2026,10,20,20,0)));
        assertThat(sql.getSql()).contains("nx_growth_weekly_definition","^WEEK:[0-9]{4}-W[0-9]{2}$",
                "eligible_from<=?","eligible_until>?","LIMIT 2").doesNotContain("NOW()","INSERT","UPDATE");
        assertThat(sql.getParameterMappings()).extracting(parameter -> parameter.getProperty()).containsExactly("now","now");
    }
}
