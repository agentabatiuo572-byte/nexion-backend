package ffdd.opsconsole.bi.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BiReportMapperSqlContractTest {

    @Test
    void downloadableReadyCountIncludesImplementedAggregateNetworkAndRegulatoryTypes() throws Exception {
        Select select = BiReportMapper.class.getMethod("countReadyReports").getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toUpperCase();

        assertThat(sql)
                .contains("STATUS = 'READY'")
                .contains("SNAPSHOT_CSV IS NOT NULL")
                .contains("REPORT_TYPE IN ('KPI_SERIES', 'FUNNEL_COHORT', 'FINANCE_AGG', 'OPERATIONS_AGG', 'NETWORK_TREE', 'KYC_REGULATORY', 'REGULATORY')");
    }
}
