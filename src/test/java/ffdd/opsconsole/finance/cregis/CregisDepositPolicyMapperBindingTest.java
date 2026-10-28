package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import ffdd.opsconsole.finance.mapper.CregisDepositPolicyMapper;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class CregisDepositPolicyMapperBindingTest {
    @Test
    void mybatisPlusRegistersPolicyAndBaseMapperStatementsSeparately() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class))
                .withUserConfiguration(MapperConfiguration.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CregisDepositPolicyMapper.class)).isNotNull();
                    var configuration = context.getBean(SqlSessionFactory.class).getConfiguration();
                    String namespace = CregisDepositPolicyMapper.class.getName();
                    assertThat(configuration.hasStatement(namespace + ".insertPolicy")).isTrue();
                    assertThat(configuration.hasStatement(namespace + ".insert")).isTrue();
                });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class MapperConfiguration {
        @Bean
        MapperFactoryBean<CregisDepositPolicyMapper> policyMapper(SqlSessionFactory factory) {
            var mapper = new MapperFactoryBean<>(CregisDepositPolicyMapper.class);
            mapper.setSqlSessionFactory(factory);
            return mapper;
        }
    }
}
