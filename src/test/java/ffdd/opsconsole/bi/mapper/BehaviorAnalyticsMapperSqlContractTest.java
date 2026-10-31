package ffdd.opsconsole.bi.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class BehaviorAnalyticsMapperSqlContractTest {

    @Test
    void currentCatalogExcludesRetiredPagesWhileHistoricalMetadataRetainsThem() throws Exception {
        String current = String.join(" ", BehaviorAnalyticsMapper.class.getMethod("listCatalog")
                .getAnnotation(Select.class).value()).replaceAll("\\s+", " ");
        String historical = String.join(" ", BehaviorAnalyticsMapper.class.getMethod("listHistoricalCatalog")
                .getAnnotation(Select.class).value()).replaceAll("\\s+", " ");

        assertThat(current).contains("WHERE tracked=1 AND is_deleted=0");
        assertThat(historical).contains("title_zh AS titleZh", "page_level AS pageLevel", "WHERE is_deleted=0")
                .doesNotContain("tracked=1");
    }

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
