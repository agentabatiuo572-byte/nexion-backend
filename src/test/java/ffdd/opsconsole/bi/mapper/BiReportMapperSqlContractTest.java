package ffdd.opsconsole.bi.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BiReportMapperSqlContractTest {

    private static final String CURRENT_L5_REPORT_TYPES =
            "UPPER(REPORT_TYPE) IN ('KPI_SERIES', 'FUNNEL_COHORT', 'FINANCE_AGG', "
                    + "'OPERATIONS_AGG', 'NETWORK_TREE', 'REGULATORY')";

    @Test
    void downloadableReadyCountIncludesImplementedAggregateNetworkAndRegulatoryTypes() throws Exception {
        Select select = BiReportMapper.class.getMethod("countReadyReports").getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toUpperCase();

        assertThat(sql)
                .contains("STATUS = 'READY'")
                .contains("SNAPSHOT_CSV IS NOT NULL")
                .contains(CURRENT_L5_REPORT_TYPES);
    }

    @Test
    void l5OverviewAndTaskQueriesExcludeRetiredOrUnknownReportTypes() throws Exception {
        for (String method : new String[]{
                "countTotalReports",
                "countSensitiveReports",
                "countPendingConfirm",
                "countReadyReportsWithoutSnapshot"
        }) {
            Select select = BiReportMapper.class.getMethod(method).getAnnotation(Select.class);
            String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toUpperCase();
            assertThat(sql)
                    .as(method)
                    .contains(CURRENT_L5_REPORT_TYPES)
                    .doesNotContain("KYC_REGULATORY");
        }

        Select countSelect = BiReportMapper.class
                .getMethod("countReports", String.class, java.util.List.class)
                .getAnnotation(Select.class);
        Select pageSelect = BiReportMapper.class
                .getMethod("reports", String.class, java.util.List.class, int.class, int.class)
                .getAnnotation(Select.class);

        for (Select select : new Select[]{countSelect, pageSelect}) {
            String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toUpperCase();
            assertThat(sql)
                    .contains(CURRENT_L5_REPORT_TYPES)
                    .doesNotContain("KYC_REGULATORY");
        }
    }

    @Test
    void networkTreeDepthExpandsAtoBtoCFromOnlyLevelOneAdjacencyWithoutCyclesOrDuplicates() throws Exception {
        Select select = BiReportMapper.class
                .getMethod("selectL4NetworkTreeRows", String.class, int.class, int.class)
                .getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toUpperCase();

        // With canonical rows A->B(level1) and B->C(level1), depth=2 must derive A->C.
        // Filtering stored `level` alone returns the same two rows for depth 1 and 2.
        assertThat(sql)
                .contains("WITH RECURSIVE TREE_EDGES AS")
                .contains("M.USER_ID AS ROOT_USER_ID")
                .contains("M.MEMBER_USER_ID")
                .contains("1 AS TREE_DEPTH")
                .contains("C.USER_ID = T.MEMBER_USER_ID")
                .contains("C.LEVEL = 1")
                .contains("M.USER_ID <> M.MEMBER_USER_ID")
                .contains("C.USER_ID <> C.MEMBER_USER_ID")
                .contains("T.TREE_DEPTH < #{DEPTH}".toUpperCase())
                .contains("LOCATE(CONCAT(',', CAST(C.MEMBER_USER_ID AS CHAR), ','), T.VISITED_PATH) = 0")
                .contains("ROW_NUMBER() OVER ( PARTITION BY ROOT_USER_ID, MEMBER_USER_ID")
                .contains("WHERE EDGE_RANK = 1 AND JOINED_AT >= CASE LOWER(#{PERIOD})")
                .contains("ORDER BY ROOT_USER_ID ASC, TREE_DEPTH ASC, MEMBER_USER_ID ASC")
                .doesNotContain("M.CREATED_AT >= CASE")
                .doesNotContain("C.CREATED_AT >= CASE")
                .doesNotContain("LEVEL BETWEEN 1 AND #{DEPTH}".toUpperCase());
    }
}
