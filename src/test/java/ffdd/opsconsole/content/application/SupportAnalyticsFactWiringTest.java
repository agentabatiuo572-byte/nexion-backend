package ffdd.opsconsole.content.application;

import ffdd.opsconsole.NexionOpsConsoleApplication;
import ffdd.opsconsole.content.mapper.SupportAnalyticsFactMapper;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportAnalyticsFactWiringTest {
    @Test void actualMapperScanAndSpringConstructorsRegisterTheSamplerAndPrivateQueryChain() {
        String basePackage=java.util.Arrays.stream(NexionOpsConsoleApplication.class.getAnnotationsByType(MapperScan.class))
            .flatMap(scan->java.util.Arrays.stream(scan.value())).filter(value->value.contains("**.mapper")).findFirst().orElseThrow();
        var config=new Configuration();
        config.setEnvironment(new Environment("wiring",new SpringManagedTransactionFactory(),mock(DataSource.class)));
        try(var context=new AnnotationConfigApplicationContext()) {
            context.registerBean("sqlSessionFactory",SqlSessionFactory.class,()->new DefaultSqlSessionFactory(config));
            context.registerBean("mapperScanner",MapperScannerConfigurer.class,()->{
                var scanner=new MapperScannerConfigurer();scanner.setBasePackage(basePackage);
                scanner.setSqlSessionFactoryBeanName("sqlSessionFactory");scanner.setLazyInitialization("true");return scanner;
            });
            context.registerBean(FinanceSupportPaymentFactsFacade.class,()->mock(FinanceSupportPaymentFactsFacade.class));
            context.registerBean(SupportFundsReadFacade.class,()->mock(SupportFundsReadFacade.class));
            context.registerBean(SupportOwnershipService.class,()->mock(SupportOwnershipService.class));
            context.registerBean(SupportInvitationReadFacade.class,()->mock(SupportInvitationReadFacade.class));
            context.registerBean(SupportDeviceReadFacade.class,()->mock(SupportDeviceReadFacade.class));
            context.registerBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class));
            context.registerBean(ProductionSupportPathGuard.class,()->mock(ProductionSupportPathGuard.class));
            context.registerBean(SupportAnalyticsRedisStore.class,()->mock(SupportAnalyticsRedisStore.class));
            context.registerBean(SupportAnalyticsFactSampler.class);
            context.registerBean(SupportAnalyticsService.class);
            context.registerBean(SupportAnalyticsPrivateQueryService.class);
            context.refresh();
            assertThat(context.getBean(SupportAnalyticsFactMapper.class)).isNotNull();
            assertThat(config.hasMapper(SupportAnalyticsFactMapper.class)).isTrue();
            assertThat(config.hasStatement(SupportAnalyticsFactMapper.class.getName()+".productionCustomers")).isTrue();
            assertThat(ReflectionTestUtils.getField(context.getBean(SupportAnalyticsService.class),"factSampler"))
                .isSameAs(context.getBean(SupportAnalyticsFactSampler.class));
            assertThat(ReflectionTestUtils.getField(context.getBean(SupportAnalyticsPrivateQueryService.class),"stats"))
                .isSameAs(context.getBean(SupportAnalyticsService.class));
        }
    }
}
