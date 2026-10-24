package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.infrastructure.H4EventSqlConfiguration;
import ffdd.opsconsole.growth.mapper.H4EventSqlMapper;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class H4EventTimeMappingTest {
    static final List<String> TIME_COLUMNS=List.of("starts_at","ends_at","activated_at","disabled_at","settled_at",
            "created_at","updated_at","eligible_since","qualified_at","joined_at","notified_at","draw_at",
            "opened_at","drawn_at","last_attempt_at","issued_at","paid_at","locked_at");

    @ParameterizedTest @ValueSource(strings={"java.time.LocalDateTime","java.sql.Timestamp"})
    void activityDatesRetainTheSameWallTimeEvenWhenJdbcInstantsUseAnotherZone(String jdbcClass) throws Exception {
        var mapper=new H4EventSqlConfiguration().h4EventRawMapper(mock(DataSource.class));
        var session=(SqlSessionTemplate)ReflectionTestUtils.getField(Proxy.getInvocationHandler(mapper),"sqlSession");
        var configuration=Objects.requireNonNull(session).getConfiguration();
        assertThat(configuration.getLocalCacheScope()).isEqualTo(LocalCacheScope.STATEMENT);
        var mapped=configuration.getMappedStatement(H4EventSqlMapper.class.getName()+".list");
        var bound=mapped.getBoundSql(Map.of("sql","SELECT * FROM time_probe","args",new Object[0]));
        var resultSet=mock(ResultSet.class);var metadata=mock(ResultSetMetaData.class);
        when(resultSet.getMetaData()).thenReturn(metadata);when(resultSet.next()).thenReturn(true,false);
        when(metadata.getColumnCount()).thenReturn(TIME_COLUMNS.size()+1);
        LocalDateTime wall=LocalDateTime.parse("2026-10-25T12:00:00.123456");
        int index=0;
        for(String column:TIME_COLUMNS){
            index++;when(metadata.getColumnLabel(index)).thenReturn(column);when(metadata.getColumnName(index)).thenReturn(column);
            when(metadata.getColumnType(index)).thenReturn(Types.TIMESTAMP);when(metadata.getColumnClassName(index)).thenReturn(jdbcClass);
            when(resultSet.getObject(column,LocalDateTime.class)).thenReturn(column.equals("disabled_at")?null:wall);
            when(resultSet.getTimestamp(column)).thenReturn(Timestamp.from(wall.toInstant(ZoneOffset.ofHours(8))));
        }
        index++;when(metadata.getColumnLabel(index)).thenReturn("rules_json");when(metadata.getColumnName(index)).thenReturn("rules_json");
        when(metadata.getColumnType(index)).thenReturn(Types.VARCHAR);when(metadata.getColumnClassName(index)).thenReturn(String.class.getName());
        when(resultSet.getString("rules_json")).thenReturn("{\"enabled\":true}");
        var statement=mock(Statement.class);var connection=mock(Connection.class);
        when(statement.getResultSet()).thenReturn(resultSet);when(statement.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(mock(DatabaseMetaData.class));
        var handler=new DefaultResultSetHandler(mock(Executor.class),mapped,null,null,bound,RowBounds.DEFAULT);
        Map<?,?> row=(Map<?,?>)handler.handleResultSets(statement).get(0);
        for(String column:TIME_COLUMNS){
            assertThat(row.containsKey(column)).isTrue();
            assertThat(row.get(column)).isEqualTo(column.equals("disabled_at")?null:wall);
            verify(resultSet,never()).getTimestamp(column);
        }
        assertThat(H4EventRuleService.date(row.get("activated_at"))).isEqualTo(H4EventRuleService.date(row.get("starts_at")));
        assertThat(row.get("rules_json")).isEqualTo("{\"enabled\":true}");
    }
}
