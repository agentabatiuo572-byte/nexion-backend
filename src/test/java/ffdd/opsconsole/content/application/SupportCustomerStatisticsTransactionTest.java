package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.CustomerProfileRepository;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.finance.application.*;
import ffdd.opsconsole.finance.facade.*;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.team.application.SupportInvitationReadService;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import ffdd.opsconsole.team.mapper.SupportInvitationReadMapper;
import ffdd.opsconsole.user.application.OpsUserService;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Real Spring proxies and JDBC transaction bookkeeping; no database connections are opened. */
class SupportCustomerStatisticsTransactionTest {
    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target,DataSourceTransactionManager transactions) {
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }

    @ParameterizedTest @ValueSource(strings={"root","finance","invitation"})
    void unavailableStatisticsKeepOtherProfileSectionsAndCommitOneReadOnlySnapshot(String failure) throws Exception {
        var connection=mock(Connection.class);var dataSource=mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        var transactions=new DataSourceTransactionManager(dataSource);
        var ownership=mock(SupportOwnershipService.class);var scope=new ReadScope(7L,ReadMode.PERSONAL,null,null);
        when(ownership.customerQueryScope(1L)).thenReturn(scope);
        var mapper=mock(SupportAnalyticsMapper.class);
        when(mapper.currentCustomer(scope,1L)).thenAnswer(i->{
            assertSnapshot(dataSource,connection);
            return List.of(new SupportAnalyticsMapper.CurrentCustomer("root".equals(failure)?2L:1L,"BOUND","UNGROUPED",0,7L,null));
        });
        FinanceSupportPaymentFactsFacade payments;
        if("finance".equals(failure)) {
            var sourceMapper=mock(SupportPaymentSourceMapper.class);
            when(sourceMapper.historyNowUtc()).thenAnswer(i->{assertSnapshot(dataSource,connection);throw new IllegalStateException("source unavailable");});
            payments=proxy(new SupportPaymentSourceService(sourceMapper,mock(SupportPaymentFactService.class),dataSource,
                new com.fasterxml.jackson.databind.ObjectMapper(),mock(SupportPaymentCaptureHistoryFacade.class),null,null,null),transactions);
        } else {
            payments=mock(FinanceSupportPaymentFactsFacade.class);
            when(payments.readHistory(any())).thenReturn(new SupportPaymentFacts.Snapshot(List.of(),List.of(),List.of(),"Asia/Shanghai",Instant.now()));
        }
        SupportInvitationReadFacade invitations;
        if("invitation".equals(failure)) {
            var invitationMapper=mock(SupportInvitationReadMapper.class);
            when(invitationMapper.readUsers(any())).thenAnswer(i->{assertSnapshot(dataSource,connection);throw new IllegalStateException("tree unavailable");});
            invitations=proxy(new SupportInvitationReadService(invitationMapper),transactions);
        } else invitations=mock(SupportInvitationReadFacade.class);
        var analytics=proxy(new SupportAnalyticsService(ownership,payments,mapper,invitations,mock(SupportDeviceReadFacade.class)),transactions);
        var finance=mock(FinanceSupportReadService.class);
        when(finance.totals(1L)).thenAnswer(i->{assertSnapshot(dataSource,connection);return Map.of("byCurrency",List.of());});
        var profile=proxy(new SupportCustomerProfileService(ownership,mock(SupportBindingMapper.class),mock(SupportCustomerProfileMapper.class),
            mock(OpsUserService.class),finance,mock(OpsDeviceService.class),mock(OpsRiskService.class),mock(CustomerProfileRepository.class),
            mock(SupportMaintenanceService.class),mock(SupportWorkbenchMapper.class),mock(ObjectStorageService.class),null,null,analytics),transactions);
        var result=profile.profile(1L);
        assertThat(((Map<?,?>)result.get("statistics")).get("status")).isEqualTo("ERROR");
        assertThat(((Map<?,?>)result.get("statistics")).get("data")).isNull();
        assertThat(((Map<?,?>)result.get("finance")).get("status")).isEqualTo("READY");
        verify(dataSource,times(1)).getConnection();verify(connection).setReadOnly(true);verify(connection).commit();
        verify(connection,never()).rollback();verify(connection).close();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    private static void assertSnapshot(DataSource dataSource,Connection connection) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(((ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource)).getConnection()).isSameAs(connection);
    }
}
