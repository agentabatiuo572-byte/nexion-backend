package ffdd.opsconsole.bi.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class BehaviorAnalyticsMapperSqlContractTest {

    @Test
    void activitySelectOrderMatchesTheRecordConstructor() throws Exception {
        Select select = BehaviorAnalyticsMapper.class
                .getMethod("activity", java.time.LocalDateTime.class, java.time.LocalDateTime.class,
                        String.class, String.class, String.class)
                .getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");

        assertThat(sql.indexOf("AS bounceRate"))
                .as("ActivityRow expects dwellMs, bounceRate, pageCount in constructor order")
                .isPositive()
                .isLessThan(sql.indexOf("AS pageCount"));
    }
}
