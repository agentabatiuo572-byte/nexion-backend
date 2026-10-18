package ffdd.opsconsole.shared.canonical.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

class AppBundleOrderMapperRecordTest {
    @Test
    void actualMapperProjectionMapsChineseDatacenterAndAllProductRecordFields() throws Exception {
        var expected = new AppBundleOrderMapper.ProductRow(5L, "stellarbox-pro-v2", "UVELBox Pro v2",
                new BigDecimal("1200.000000"), 2, "{\"rankMin\":2,\"mode\":\"all\",\"enforce\":true}",
                "2", "DEVICE", "FINITE", "RTX 5090", 12, "240W", "美国·弗吉尼亚");
        var configuration = new Configuration();
        configuration.addMapper(AppBundleOrderMapper.class);
        var mapped = configuration.getMappedStatement(AppBundleOrderMapper.class.getName() + ".lockProducts");
        var bound = mapped.getBoundSql(Map.of("productNos", List.of(expected.productNo())));

        // A JDBC driver returns SELECT columns in projection order. Preserve the actual
        // mapper order, including scalar subqueries, instead of inventing a result map.
        String sql = bound.getSql();
        String projection = sql.substring(sql.indexOf("SELECT") + 6, sql.indexOf("FROM nx_product p"))
                .replaceAll("(?s)\\(SELECT.*?\\)", "scalar");
        List<String> labels = Arrays.stream(projection.split(","))
                .map(String::trim).map(column -> column.replaceFirst("(?s).*\\s", ""))
                .map(column -> column.substring(column.lastIndexOf('.') + 1)).toList();
        Map<String, Object> values = Map.ofEntries(
                Map.entry("id", expected.id()), Map.entry("productNo", expected.productNo()),
                Map.entry("name", expected.name()), Map.entry("priceUsdt", expected.priceUsdt()),
                Map.entry("stock", expected.stock()), Map.entry("purchaseGateJson", expected.purchaseGateJson()),
                Map.entry("unlockPhase", expected.unlockPhase()), Map.entry("productType", expected.productType()),
                Map.entry("inventoryMode", expected.inventoryMode()), Map.entry("gpuModel", expected.gpuModel()),
                Map.entry("vramTotalGb", expected.vramTotalGb()), Map.entry("power", expected.power()),
                Map.entry("datacenter", expected.datacenter()));

        try (var rows = RowSetProvider.newFactory().createCachedRowSet()) {
            var metadata = new RowSetMetaDataImpl();
            metadata.setColumnCount(labels.size());
            for (int i = 0; i < labels.size(); i++) {
                String label = labels.get(i);
                metadata.setColumnName(i + 1, label);
                metadata.setColumnLabel(i + 1, label);
                Object value = values.get(label);
                metadata.setColumnType(i + 1, value instanceof Long ? Types.BIGINT
                        : value instanceof Integer ? Types.INTEGER : value instanceof BigDecimal ? Types.DECIMAL : Types.VARCHAR);
            }
            rows.setMetaData(metadata);
            rows.moveToInsertRow();
            for (int i = 0; i < labels.size(); i++) rows.updateObject(i + 1, values.get(labels.get(i)));
            rows.insertRow();
            rows.moveToCurrentRow();
            rows.beforeFirst();
            ResultSet resultSet = mock(ResultSet.class, delegatesTo(rows));
            // CachedRowSet has real JDBC conversions but does not implement isClosed.
            doReturn(false).when(resultSet).isClosed();
            Statement statement = mock(Statement.class);
            when(statement.getResultSet()).thenReturn(resultSet);
            when(statement.getUpdateCount()).thenReturn(-1);
            var handler = new DefaultResultSetHandler(null, mapped, null, null, bound, RowBounds.DEFAULT);
            List<Object> mappedRows = handler.handleResultSets(statement);
            assertThat(mappedRows).containsExactly(expected);
        }
    }
}
