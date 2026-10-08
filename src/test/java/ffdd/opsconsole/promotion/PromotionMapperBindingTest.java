package ffdd.opsconsole.promotion;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.Map;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PromotionMapperBindingTest {
    @Test
    void bindsValuesWithoutInterpretingTheirSqlSyntaxOrQuotedQuestionMarks() throws Exception {
        var configuration=new Configuration();
        var sql="SELECT '?' literal, ? text, ? amount";
        var values=Map.<String,Object>of("sql",sql,"args",new Object[]{"'; DROP TABLE nx_promotion; -- ?",new BigDecimal("0.000001")});
        var source=new PromotionMapper.PreparedSqlDriver().createSqlSource(configuration,sql,Map.class);
        var bound=source.getBoundSql(values);
        assertThat(bound.getSql()).isEqualTo(sql);
        assertThat(bound.getParameterMappings()).extracting(mapping->mapping.getProperty()).containsExactly("args[0]","args[1]");
        var statement=new MappedStatement.Builder(configuration,"promotion-binding",source,SqlCommandType.SELECT).build();
        var prepared=mock(PreparedStatement.class);
        new DefaultParameterHandler(statement,values,bound).setParameters(prepared);
        verify(prepared).setString(1,"'; DROP TABLE nx_promotion; -- ?");
        verify(prepared).setBigDecimal(2,new BigDecimal("0.000001"));
        verifyNoMoreInteractions(prepared);
    }
}
