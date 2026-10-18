package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import ffdd.opsconsole.NexionOpsConsoleApplication;
import ffdd.opsconsole.growth.mapper.WeeklyRegionPolicyMapper;
import ffdd.opsconsole.user.facade.UserWeeklyRegionFacade;
import ffdd.opsconsole.user.infrastructure.UserWeeklyRegionMapper;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

class UserWeeklyRegionMapperBindingTest {
    @Test
    void productionMapperScansRegisterRealMapperAndConstructAccountFacade() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class))
                .withUserConfiguration(ProductionMapperScanning.class, UserWeeklyRegionFacadeAdapter.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UserWeeklyRegionMapper.class)
                            .hasSingleBean(WeeklyRegionPolicyMapper.class)
                            .hasSingleBean(UserWeeklyRegionFacade.class);
                    assertThat(Proxy.isProxyClass(context.getBean(UserWeeklyRegionMapper.class).getClass())).isTrue();
                    assertThat(context.getBean(SqlSessionTemplate.class).getConfiguration()
                            .hasStatement(UserWeeklyRegionMapper.class.getName() + ".lockActive")).isTrue();
                    assertThat(context.getBean(SqlSessionTemplate.class).getConfiguration()
                            .hasStatement(WeeklyRegionPolicyMapper.class.getName() + ".openLegacy")).isTrue();
                });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @Import(ProductionMapperScans.class)
    static class ProductionMapperScanning { }

    static class ProductionMapperScans implements ImportBeanDefinitionRegistrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            var registrar = (ImportBeanDefinitionRegistrar) BeanUtils.instantiateClass(
                    MapperScans.class.getAnnotation(Import.class).value()[0]);
            registrar.registerBeanDefinitions(AnnotationMetadata.introspect(NexionOpsConsoleApplication.class), registry);
        }
    }
}
