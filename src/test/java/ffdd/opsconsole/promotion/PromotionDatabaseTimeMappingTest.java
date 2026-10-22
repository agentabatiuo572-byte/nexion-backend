package ffdd.opsconsole.promotion;

import ffdd.opsconsole.promotion.infrastructure.PromotionSqlConfiguration;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static ffdd.opsconsole.promotion.domain.PromotionValues.instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromotionDatabaseTimeMappingTest {
    static final List<String> TIME_COLUMNS=List.of("expires_at","pay_by","ready_at","paid_at","as_of",
        "created_at","updated_at","started_at","finished_at","approved_at","published_at",
        "activated_at","purchased_at","first_failure","resolved_at","snapshot_expires");
    private static final Instant INPUT=Instant.parse("2026-10-22T12:38:56.123456Z");

    @ParameterizedTest
    @ValueSource(strings={"UTC","Asia/Ho_Chi_Minh","Asia/Shanghai"})
    void rawDatetimeColumnsUseJdbcTimestampsInsteadOfReinterpretingLocalWallTime(String zone) throws Exception {
        var expected=new LinkedHashMap<String,Object>();
        TIME_COLUMNS.forEach(column->expected.put(column,Timestamp.from(INPUT)));
        var row=readRow(zone,expected);
        assertEquals(expected.keySet(),row.keySet());
        for(String column:TIME_COLUMNS){
            assertInstanceOf(Timestamp.class,row.get(column),column);
            assertEquals(INPUT,instant(row.get(column)),column+" / "+zone);
        }
    }

    @Test void partialProjectionsMissingTimeColumnsAndNullableValuesRemainRaw() throws Exception {
        assertEquals(Map.of("expires_at",Timestamp.from(INPUT)),readRow("Asia/Ho_Chi_Minh",Map.of("expires_at",Timestamp.from(INPUT))));
        assertEquals(Map.of("unrelated_label","unchanged"),readRow("UTC",Map.of("unrelated_label","unchanged")));
        var nullable=new LinkedHashMap<String,Object>();nullable.put("ready_at",null);
        assertEquals(nullable,readRow("Asia/Ho_Chi_Minh",nullable));
    }

    private static Map<?,?> readRow(String zone,Map<String,Object> values) throws Exception {
        PromotionMapper mapper=new PromotionSqlConfiguration().promotionRawMapper(mock(DataSource.class));
        var session=(SqlSessionTemplate)ReflectionTestUtils.getField(Proxy.getInvocationHandler(mapper),"sqlSession");
        Configuration configuration=Objects.requireNonNull(session).getConfiguration();
        var mapped=configuration.getMappedStatement(PromotionMapper.class.getName()+".list");
        var bound=mapped.getBoundSql(Map.of("sql","SELECT "+String.join(",",values.keySet()),"args",new Object[0]));
        var resultSet=mock(ResultSet.class);var metadata=mock(ResultSetMetaData.class);
        when(resultSet.getMetaData()).thenReturn(metadata);when(resultSet.next()).thenReturn(true,false);
        when(metadata.getColumnCount()).thenReturn(values.size());
        int index=0;
        for(var entry:values.entrySet()){
            String column=entry.getKey();index++;
            boolean temporal=TIME_COLUMNS.contains(column);
            when(metadata.getColumnLabel(index)).thenReturn(column);when(metadata.getColumnName(index)).thenReturn(column);
            when(metadata.getColumnType(index)).thenReturn(temporal?Types.TIMESTAMP:Types.VARCHAR);
            when(metadata.getColumnClassName(index)).thenReturn(temporal?LocalDateTime.class.getName():String.class.getName());
            if(temporal){
                Timestamp timestamp=(Timestamp)entry.getValue();
                when(resultSet.getTimestamp(column)).thenReturn(timestamp);
                when(resultSet.getObject(column,LocalDateTime.class)).thenReturn(timestamp==null?null:LocalDateTime.ofInstant(timestamp.toInstant(),ZoneId.of(zone)));
            }else when(resultSet.getString(column)).thenReturn((String)entry.getValue());
        }
        var statement=mock(Statement.class);var connection=mock(Connection.class);var database=mock(DatabaseMetaData.class);
        when(statement.getResultSet()).thenReturn(resultSet);when(statement.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(database);
        var handler=new DefaultResultSetHandler(mock(Executor.class),mapped,null,null,bound,RowBounds.DEFAULT);
        List<Object> rows=handler.handleResultSets(statement);
        assertEquals(1,rows.size());
        for(String column:values.keySet())if(TIME_COLUMNS.contains(column))verify(resultSet).getTimestamp(column);
        return (Map<?,?>)rows.get(0);
    }
}
