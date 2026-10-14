package ffdd.opsconsole.auth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.mapper.AuthRegistrationBirthMapper;
import ffdd.opsconsole.auth.mapper.AuthRegistrationBirthMapper.Customer;
import ffdd.opsconsole.auth.mapper.AuthRegistrationBirthMapper.RegistrationEvent;
import ffdd.opsconsole.finance.facade.SupportFundAuthBirthFacade;
import ffdd.opsconsole.finance.facade.SupportFundAuthBirthFacade.Outcome;
import ffdd.opsconsole.user.facade.UserRegistrationWriteFacade;
import ffdd.opsconsole.user.facade.UserRegistrationWriteFacade.NewAccountCommand;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthNewAccountCreatorTest {
    private static final LocalDateTime AT=LocalDateTime.of(2026,10,11,0,0);
    private static final String EVENT="0123456789abcdef0123456789abcdef";
    private final UserRegistrationWriteFacade users=mock(UserRegistrationWriteFacade.class);
    private final AuthRegistrationBirthMapper mapper=mock(AuthRegistrationBirthMapper.class);
    private final SupportFundAuthBirthFacade funds=mock(SupportFundAuthBirthFacade.class);
    private final DataSource ds=mock(DataSource.class);
    private final Connection c=mock(Connection.class);
    private final AuthNewAccountCreator creator=new AuthNewAccountCreator(users,mapper,funds,ds,new ObjectMapper());
    private ConnectionHolder holder;
    private static NewAccountCommand command(int sandbox) {
        return new NewAccountCommand("+84","123456789","private-ip","private-hash","name","CODE",null,null,"L1","V0","ACTIVE",sandbox,"en");
    }
    @BeforeEach void transaction() {
        holder=new ConnectionHolder(c);holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.bindResource(ds,holder);
        when(users.insertNewAccount(any())).thenReturn(7L);when(mapper.customer(7)).thenReturn(new Customer(7L,AT,0,0,"ACTIVE"));
        when(mapper.event(EVENT)).thenReturn(event(payload()));when(funds.record(any())).thenReturn(Outcome.BOUND);
    }
    @AfterEach void clear() {
        if(TransactionSynchronizationManager.hasResource(ds))TransactionSynchronizationManager.unbindResource(ds);
        TransactionSynchronizationManager.clear();
    }
    private static String payload() { return "{\"userId\":7,\"user_id\":7,\"event_id\":\""+EVENT+"\",\"event_name\":\"auth.register_completed\",\"is_server_authoritative\":true,\"schema_revision\":1}"; }
    private static RegistrationEvent event(String p) {return new RegistrationEvent(9L,EVENT,"USER_REGISTRATION","7","auth.register_completed","auth.register_completed",AT,true,true,true,1,p,0);}
    private AuthNewAccountCreator.BirthHandle ready() {var b=creator.insertNew(command(0));creator.ensureWallet(b);return b;}
    @Test void actualInsertProducesOnePhysicalTokenAndVerifiedNoPiiFacts() {
        var b=ready();assertThat(creator.complete(b,EVENT)).isEqualTo(Outcome.BOUND);
        var nonce=ArgumentCaptor.forClass(String.class);verify(users).ensureRegisteredWallet(eq(7L),eq(0),nonce.capture());
        var facts=ArgumentCaptor.forClass(SupportFundAuthBirthFacade.AuthenticatedRegistrationFacts.class);verify(funds).record(facts.capture());
        assertThat(facts.getValue().expectedWalletIncarnation()).isEqualTo(nonce.getValue());
        assertThat(facts.getValue().toString()).doesNotContain("private-ip","private-hash");
        assertThat(java.util.Arrays.stream(SupportFundAuthBirthFacade.AuthenticatedRegistrationFacts.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)).doesNotContain("phone","clientIp","passwordHash");
        assertThatThrownBy(()->creator.complete(b,EVENT)).hasMessage("AUTH_BIRTH_TOKEN_INVALID");
    }
    @Test void claimedCustomerIdCannotMintOrReplaceAnActualInsertToken() {
        var forged=new AuthNewAccountCreator.BirthHandle(){public long customerId(){return 7;}};
        assertThatThrownBy(()->creator.ensureWallet(forged)).hasMessage("AUTH_BIRTH_TOKEN_INVALID");verifyNoInteractions(users,mapper,funds);
    }
    @Test void missingOrChangedInsertedCustomerFailsBeforeTokenAndFunds() {
        when(mapper.customer(7)).thenReturn(new Customer(8L,AT,0,0,"ACTIVE"));
        assertThatThrownBy(()->creator.insertNew(command(0))).hasMessage("AUTH_BIRTH_INSERT_IDENTITY_MISMATCH");verifyNoInteractions(funds);
    }
    @Test void tokenCannotCrossPhysicalHolderOrTransactionCompletion() {
        var b=ready();TransactionSynchronizationManager.unbindResource(ds);
        var other=new ConnectionHolder(c);other.setSynchronizedWithTransaction(true);TransactionSynchronizationManager.bindResource(ds,other);
        assertThatThrownBy(()->creator.complete(b,EVENT)).hasMessage("AUTH_BIRTH_TOKEN_INVALID");
        TransactionSynchronizationManager.unbindResource(ds);TransactionSynchronizationManager.bindResource(ds,holder);
        for(var s:TransactionSynchronizationManager.getSynchronizations())s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThatThrownBy(()->creator.complete(b,EVENT)).hasMessage("AUTH_BIRTH_TOKEN_INVALID");verifyNoInteractions(funds);
    }
    @Test void foreignOwnerAndDifferentThreadCannotEnsureWallet() throws Exception {
        var b=creator.insertNew(command(0));
        var other=new AuthNewAccountCreator(users,mapper,funds,ds,new ObjectMapper());
        assertThatThrownBy(()->other.ensureWallet(b)).hasMessage("AUTH_BIRTH_TOKEN_INVALID");
        var executor=Executors.newSingleThreadExecutor();
        try {assertThat(executor.submit(()->{
            TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.bindResource(ds,holder);
            try{creator.ensureWallet(b);return "accepted";}catch(IllegalStateException ex){return ex.getMessage();}
            finally{TransactionSynchronizationManager.unbindResource(ds);TransactionSynchronizationManager.clear();}
        }).get()).isEqualTo("AUTH_BIRTH_TOKEN_INVALID");} finally {executor.shutdownNow();}
        verify(users,never()).ensureRegisteredWallet(anyLong(),anyInt(),any());
    }
    @Test void walletCanBeEnsuredOnceAndCompletionCannotPrecedeIt() {
        var b=creator.insertNew(command(0));assertThatThrownBy(()->creator.complete(b,EVENT)).hasMessage("AUTH_BIRTH_WALLET_NOT_ENSURED");
        assertThatThrownBy(()->creator.ensureWallet(b)).hasMessage("AUTH_BIRTH_TOKEN_INVALID");
        var next=ready();assertThatThrownBy(()->creator.ensureWallet(next)).hasMessage("AUTH_BIRTH_WALLET_ALREADY_ENSURED");
    }
    @Test void sandboxUsesOriginalWalletRailAndNeverCertifiesFinanceBirth() {
        when(mapper.customer(7)).thenReturn(new Customer(7L,AT,1,0,"ACTIVE"));
        var b=creator.insertNew(command(1));creator.ensureWallet(b);assertThat(creator.complete(b,null)).isEqualTo(Outcome.EXCLUDED);
        verify(users).ensureRegisteredWallet(7,1,null);verifyNoInteractions(funds);verify(mapper,never()).event(any());
    }
    @Test void malformedMissingOrNonProductionOutboxCannotBeCertified() {
        assertThatThrownBy(()->creator.complete(ready(),"wrong")).hasMessage("AUTH_BIRTH_EVENT_ID_INVALID");
        when(mapper.event(EVENT)).thenReturn(null);assertThatThrownBy(()->creator.complete(ready(),EVENT)).hasMessage("AUTH_BIRTH_EVENT_MISMATCH");
        when(mapper.event(EVENT)).thenReturn(new RegistrationEvent(9L,EVENT,"USER_REGISTRATION","8","auth.register_completed","auth.register_completed",AT,true,true,true,1,payload(),0));
        assertThatThrownBy(()->creator.complete(ready(),EVENT)).hasMessage("AUTH_BIRTH_EVENT_MISMATCH");verifyNoInteractions(funds);
    }
    @Test void payloadAliasesDuplicateNamesOrWrongEnvelopeRejectBeforeFinance() {
        for(String p:java.util.List.of(payload().replace("\"user_id\":7","\"user_id\":8"),payload().replace("\"userId\":7","\"userId\":\"7\""),
                payload().replace("\"userId\":7","\"userId\":7,\"userId\":7"),payload()+" {}",
                payload().replace("\"is_server_authoritative\":true","\"is_server_authoritative\":\"true\""))) {
            when(mapper.event(EVENT)).thenReturn(event(p));assertThatThrownBy(()->creator.complete(ready(),EVENT)).isInstanceOf(IllegalStateException.class);
        }verifyNoInteractions(funds);
    }
    @Test void databaseFailureIsNeverConvertedIntoUnknownOrNewBirth() {
        var b=ready();var failure=new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
        when(mapper.event(EVENT)).thenThrow(failure);assertThatThrownBy(()->creator.complete(b,EVENT)).isSameAs(failure);verifyNoInteractions(funds);
    }
    @Test void proxyFailureStillRollsBackWhenTheOuterCallerCatchesIt() throws Exception {
        clear();when(ds.getConnection()).thenReturn(c);when(c.getAutoCommit()).thenReturn(true,false,false,false,false);
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        var proxy=new org.springframework.aop.framework.ProxyFactory(creator);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var guarded=(AuthNewAccountCreator)proxy.getProxy();
        assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(manager).execute(status->{
            var token=guarded.insertNew(command(0));guarded.ensureWallet(token);
            assertThatThrownBy(()->guarded.complete(token,"bad-event")).isInstanceOf(IllegalStateException.class);return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        verify(c).rollback();verify(c,never()).commit();verifyNoInteractions(funds);
    }
}
