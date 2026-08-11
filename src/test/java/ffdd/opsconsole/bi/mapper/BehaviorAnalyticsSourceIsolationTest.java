package ffdd.opsconsole.bi.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class BehaviorAnalyticsSourceIsolationTest {
    @Test
    void everyProductionAggregateExplicitlyExcludesMockFixtureFacts() throws Exception {
        for(String name:List.of("activity","dailyTrend","weeklyTrend","clickPoints","zones")){
            Method method=java.util.Arrays.stream(BehaviorAnalyticsMapper.class.getDeclaredMethods())
                    .filter(candidate->candidate.getName().equals(name)).findFirst().orElseThrow();
            String sql=String.join("\n",method.getAnnotation(Select.class).value());
            int requiredFilters = "activity".equals(name) ? 3 : 1;
            assertThat(count(sql, "source_environment='PRODUCTION'"))
                    .as(name + " production source filters")
                    .isGreaterThanOrEqualTo(requiredFilters);
        }
    }

    private static int count(String source, String token) {
        return (source.length() - source.replace(token, "").length()) / token.length();
    }
}
