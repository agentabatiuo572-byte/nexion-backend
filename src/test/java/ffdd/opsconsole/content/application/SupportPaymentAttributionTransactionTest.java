package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade.Prepared;
import ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper;
import ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper.Customer;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade.BeforeSource;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade.FreshLedgerReceipt;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real Spring resource lifecycle with mock connections; no database connection is opened. */
class SupportPaymentAttributionTransactionTest {
    @Test void realManagerSuspendsOuterHolderResumesItAndInvalidatesCompletedChild() throws Exception {
        DataSource ds=mock(DataSource.class);Connection outer=mock(Connection.class),inner=mock(Connection.class);
        when(outer.getAutoCommit()).thenReturn(true);when(inner.getAutoCommit()).thenReturn(true);
        when(ds.getConnection()).thenReturn(outer,inner);
        var service=service(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var child=new TransactionTemplate(tx.getTransactionManager());child.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicReference<Prepared> childToken=new AtomicReference<>();
        tx.executeWithoutResult(status->{
            Object original=TransactionSynchronizationManager.getResource(ds);assertThat(original).isInstanceOf(ConnectionHolder.class);
            Prepared parent=service.prepare(11,Source.WALLET_ORDER,"O-11");
            child.executeWithoutResult(inside->{
                assertThat(TransactionSynchronizationManager.getResource(ds)).isNotSameAs(original);
                assertThatThrownBy(()->service.record(parent)).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
                assertThatThrownBy(()->service.insertLedger(parent,BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
                childToken.set(service.prepare(11,Source.WALLET_ORDER,"O-11"));
            });
            assertThat(TransactionSynchronizationManager.getResource(ds)).isSameAs(original);
            assertThatThrownBy(()->service.record(childToken.get())).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
            assertThatThrownBy(()->service.insertLedger(childToken.get(),BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
            service.insertLedger(parent,BigDecimal.ONE,BigDecimal.TEN,"paid");
            service.record(parent);
        });
        assertThat(TransactionSynchronizationManager.getResource(ds)).isNull();verify(outer).commit();verify(inner).commit();
    }
    @Test void completedTokenIsRejectedWhenPoolReturnsTheSamePhysicalConnectionAgain() throws Exception {
        DataSource ds=mock(DataSource.class);Connection connection=mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);when(ds.getConnection()).thenReturn(connection);
        var service=service(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));AtomicReference<Prepared> saved=new AtomicReference<>();
        tx.executeWithoutResult(status->saved.set(service.prepare(11,Source.WALLET_ORDER,"O-11")));
        tx.executeWithoutResult(status->{
            assertThatThrownBy(()->service.record(saved.get())).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
            assertThatThrownBy(()->service.insertLedger(saved.get(),BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
        });
        verify(connection,times(2)).commit();
    }
    @Test void rollbackAlsoInvalidatesPreparedAndAllEntriesAreMandatory() throws Exception {
        DataSource ds=mock(DataSource.class);Connection connection=mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);when(ds.getConnection()).thenReturn(connection);
        var service=service(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));AtomicReference<Prepared> saved=new AtomicReference<>();
        tx.executeWithoutResult(status->{ saved.set(service.prepare(11,Source.WALLET_ORDER,"O-11"));status.setRollbackOnly(); });
        tx.executeWithoutResult(status->{
            assertThatThrownBy(()->service.record(saved.get())).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
            assertThatThrownBy(()->service.insertLedger(saved.get(),BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
        });
        verify(connection).rollback();
        for(String name:List.of("prepare","insertLedger","record"))for(var method:SupportPaymentAttributionService.class.getDeclaredMethods())if(method.getName().equals(name)) {
            assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        }
    }
    @Test void requiredAuditFailureRollsBackTheOriginalSpringTransactionAndInvalidatesItsToken() throws Exception {
        DataSource ds=mock(DataSource.class);Connection connection=mock(Connection.class);var audit=mock(AuditLogService.class);
        when(connection.getAutoCommit()).thenReturn(true);when(ds.getConnection()).thenReturn(connection);
        var service=service(ds,audit);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));AtomicReference<Prepared> saved=new AtomicReference<>();
        var failure=new IllegalStateException("required-audit-failed");doThrow(failure).when(audit).recordRequired(any());
        assertThatThrownBy(()->tx.executeWithoutResult(status->{
            saved.set(service.prepare(11,Source.WALLET_ORDER,"O-11"));
            service.insertLedger(saved.get(),BigDecimal.ONE,BigDecimal.TEN,"paid");service.record(saved.get());
        })).isSameAs(failure);
        verify(connection).rollback();verify(connection,never()).commit();
        tx.executeWithoutResult(status->assertThatThrownBy(()->service.record(saved.get())).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID"));
    }
    private SupportPaymentAttributionService service(DataSource ds) {
        return service(ds,mock(AuditLogService.class));
    }
    private SupportPaymentAttributionService service(DataSource ds,AuditLogService audit) {
        var mapper=mock(SupportPaymentAttributionMapper.class);var finance=mock(FinanceSupportPaymentFactsFacade.class);var before=mock(BeforeSource.class);
        var receipt=mock(FreshLedgerReceipt.class);when(receipt.ledgerId()).thenReturn(88L);
        var at=LocalDateTime.of(2026,10,8,1,2,3);
        when(mapper.lockCustomer(11)).thenReturn(new Customer(11L,0,"ACTIVE",0));when(mapper.databaseUtc()).thenReturn(at);when(mapper.insert(any())).thenReturn(1);
        when(finance.beforeSource(11,Source.WALLET_ORDER,"O-11",null)).thenReturn(before);
        when(before.customerId()).thenReturn(11L);when(before.source()).thenReturn(Source.WALLET_ORDER);when(before.stableBusinessKey()).thenReturn("O-11");when(before.businessZone()).thenReturn("Asia/Shanghai");
        when(finance.insertFreshPaymentLedger(eq(before),any(BigDecimal.class),any(BigDecimal.class),anyString())).thenReturn(receipt);
        when(finance.readSettled(before,receipt)).thenReturn(Optional.of(new Fact("PURCHASE:O-11",Kind.DEVICE_PURCHASE,Source.WALLET_ORDER,List.of("O-11"),11,88,"O-11","O-11","BUY",null,"USDT",BigDecimal.ONE,at,"paid_at",0,null,at,at,"v1",Status.UNKNOWN)));
        return new SupportPaymentAttributionService(mapper,finance,audit,ds,new ObjectMapper());
    }
}
