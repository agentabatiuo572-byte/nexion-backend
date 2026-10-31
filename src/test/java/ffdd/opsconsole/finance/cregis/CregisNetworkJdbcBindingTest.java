package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import ffdd.opsconsole.finance.mapper.*;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CregisNetworkJdbcBindingTest {
    @ParameterizedTest @EnumSource(CregisNetwork.class)
    void everyNetworkGetterUsesItsJdbcScalarType(CregisNetwork network) throws Exception {
        var configuration=new MybatisConfiguration();
        var mappers=List.of(CregisDepositMapper.class,CregisDepositPolicyMapper.class,CregisExceptionSourceMapper.class);
        mappers.forEach(configuration::addMapper);
        int statements=0;
        for(var mapper:mappers) for(var method:Arrays.stream(mapper.getDeclaredMethods())
                .sorted(Comparator.comparing(Method::getName)).toList()) {
            if(method.isDefault()) continue;
            String id=mapper.getName()+"."+method.getName();
            if(!configuration.hasStatement(id,false)) continue;
            Object[] arguments=Arrays.stream(method.getParameterTypes()).map(type->argument(type,network)).toArray();
            Object parameters=new ParamNameResolver(configuration,method).getNamedParams(arguments);
            var statement=configuration.getMappedStatement(id);
            var sql=statement.getBoundSql(parameters);
            if(sql.getParameterMappings().stream().noneMatch(p->p.getProperty().contains("network."))) continue;
            var jdbc=mock(PreparedStatement.class);
            var handler=configuration.newParameterHandler(statement,parameters,sql);
            assertThat(handler).isInstanceOf(DefaultParameterHandler.class);
            assertThatCode(()->handler.setParameters(jdbc))
                .as("%s %s binds actual JDBC parameters",network,id).doesNotThrowAnyException();
            for(int index=0;index<sql.getParameterMappings().size();index++) {
                var mapping=sql.getParameterMappings().get(index);
                if(!mapping.getProperty().contains("network.")) continue;
                Object value=configuration.newMetaObject(parameters).getValue(mapping.getProperty());
                if(mapping.getProperty().endsWith("cursorId")) {
                    assertThat(mapping.getJavaType()).as("%s %s",id,mapping.getProperty()).isIn(int.class,Integer.class);
                    verify(jdbc).setInt(index+1,network.cursorId());
                } else {
                    assertThat(mapping.getJavaType()).as("%s %s",id,mapping.getProperty()).isEqualTo(String.class);
                    verify(jdbc).setString(index+1,(String)value);
                }
            }
            statements++;
        }
        assertThat(statements).isGreaterThanOrEqualTo(30);
    }

    private Object argument(Class<?> type,CregisNetwork network) {
        if(type==CregisNetwork.class) return network;
        if(type==CregisExceptionSourceService.Source.class)
            return new CregisExceptionSourceService.Source(88,77,"0x"+"a".repeat(64),2,"0x"+"b".repeat(40),BigDecimal.TEN,network);
        if(type==long.class || type==Long.class) return 88L;
        if(type==int.class || type==Integer.class) return 2;
        if(type==boolean.class || type==Boolean.class) return true;
        if(type==BigDecimal.class) return BigDecimal.TEN;
        return "binding-fixture";
    }
}
