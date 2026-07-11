package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class ContentAudienceEstimateMapperSqlTest {
    @Test
    void estimateCountsNonDeletedUsersByNormalizedLanguageAndRegistrationAge() throws Exception {
        String sql = String.join("\n", ContentAudienceEstimateMapper.class
                .getMethod("countEstimatedAudience", List.class, Integer.class, Integer.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("FROM nx_user")
                .contains("is_deleted = 0")
                .contains("UPPER(COALESCE(status, 'ACTIVE')) = 'ACTIVE'")
                .contains("SUBSTRING_INDEX")
                .contains("created_at &lt;= DATE_SUB(NOW(), INTERVAL #{registrationDaysMin} DAY)")
                .contains("created_at &gt;= DATE_SUB(NOW(), INTERVAL #{registrationDaysMax} DAY)");
    }
}
