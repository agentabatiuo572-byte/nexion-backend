package ffdd.opsconsole.promotion;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import ffdd.opsconsole.NexionOpsConsoleApplication;
import ffdd.opsconsole.promotion.infrastructure.PromotionSqlConfiguration;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PromotionMapperBindingTest {
    @Test
    void productionMapperScansInjectPrivateSessionWithoutChangingGlobalSettings() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class))
                .withUserConfiguration(ProductionMapperScanning.class,PromotionSqlConfiguration.class)
                .withBean(DataSource.class,()->mock(DataSource.class))
                .run(context->{
                    assertThat(context).hasNotFailed();
                    var selected=context.getBean(PromotionMapper.class);
                    assertThat(context.getBean(MapperConsumer.class).mapper()).isSameAs(selected);
                    assertThat(context.getBean("promotionRawMapper")).isSameAs(selected);
                    assertThat(context.getBean("promotionMapper")).isNotSameAs(selected);
                    var privateSession=(SqlSessionTemplate)ReflectionTestUtils.getField(Proxy.getInvocationHandler(selected),"sqlSession");
                    var globalSession=context.getBean(SqlSessionTemplate.class);
                    assertThat(privateSession).isNotSameAs(globalSession);
                    assertThat(privateSession.getConfiguration().isCallSettersOnNulls()).isTrue();
                    assertThat(privateSession.getConfiguration().getLocalCacheScope()).isEqualTo(LocalCacheScope.STATEMENT);
                    assertThat(globalSession.getConfiguration().isCallSettersOnNulls()).isFalse();
                    assertThat(globalSession.getConfiguration().getLocalCacheScope()).isEqualTo(LocalCacheScope.SESSION);
                });
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @Import(ProductionMapperScans.class)
    static class ProductionMapperScanning {
        @Bean MapperConsumer mapperConsumer(PromotionMapper mapper){return new MapperConsumer(mapper);}
    }
    record MapperConsumer(PromotionMapper mapper) {}
    static class ProductionMapperScans implements ImportBeanDefinitionRegistrar {
        @Override public void registerBeanDefinitions(AnnotationMetadata metadata,BeanDefinitionRegistry registry){
            var registrar=(ImportBeanDefinitionRegistrar)BeanUtils.instantiateClass(MapperScans.class.getAnnotation(Import.class).value()[0]);
            registrar.registerBeanDefinitions(AnnotationMetadata.introspect(NexionOpsConsoleApplication.class),registry);
        }
    }

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
