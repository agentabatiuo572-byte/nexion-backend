package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

class TestCloudTransactionBoundaryTest {
    @Test void independentInferenceRunsWithoutAnySpringTransaction() throws Exception {
        var fixture=new TestCloudExecutorServiceTest(); fixture.setup();
        var manager=new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
        };
        var proxy=new ProxyFactory(fixture.service); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        var service=(TestCloudExecutorService)proxy.getProxy();
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("Independent model HTTP may not retain a transaction/connection").isFalse();
            return TestCloudBgeArtifactTest.expected(0.25);
        }).when(fixture.service).infer(fixture.identity);
        when(fixture.mapper.saveResult(anyString(),anyString(),any(),anyString(),anyString())).thenReturn(1);
        service.complete(fixture.identity,TestCloudExecutorServiceTest.TASK,"cloud-boundary-unit",fixture.request(0.25f));
    }
}
