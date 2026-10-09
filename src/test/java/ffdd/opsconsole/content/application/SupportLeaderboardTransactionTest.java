package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportLeaderboard.Context;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardAuthorizationMapper.AssetReference;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import static ffdd.opsconsole.content.application.SupportLeaderboardServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Spring interceptors with a test physical resource; no database or mock transaction manager. */
class SupportLeaderboardTransactionTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();TransactionSynchronizationManager.clear();}

    @SuppressWarnings("unchecked")
    static <T> T proxy(T target,ResourceTransactions manager){
        ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }

    public static class LegacyResponse {
        private final SupportLeaderboardSourceService source;
        LegacyResponse(SupportLeaderboardSourceService source){this.source=source;}
        @Transactional(isolation=Isolation.REPEATABLE_READ)
        public String caughtSourceFailure(){
            try {source.readForAuthorizedLeaderboard(context(ffdd.opsconsole.content.domain.SupportLeaderboard.Board.firstPayment,"USDT",NOW));}
            catch(BizException ex){if(ex.getCode()!=503)throw ex;return "old-publication";}
            throw new AssertionError("Expected source failure");
        }
    }
    static class Fixture {
        final Harness h=new Harness();
        final ResourceTransactions manager=new ResourceTransactions();
        final SupportLeaderboardMapper sourceMapper=mock(SupportLeaderboardMapper.class);
        final FinanceSupportPaymentFactsFacade finance=mock(FinanceSupportPaymentFactsFacade.class);
        final SupportLeaderboardSourceService source;
        final SupportLeaderboardService api;
        Fixture(boolean selectedFailure,boolean prior){
            when(h.auth.account(7)).thenAnswer(i->{assertNotNull(manager.current.get());assertEquals(1,manager.current.get().id);
                return new ffdd.opsconsole.content.mapper.SupportLeaderboardAuthorizationMapper.Account(7L,1L);});
            when(sourceMapper.nowUtc()).thenReturn(LocalDateTime.ofInstant(NOW,ZoneOffset.UTC));
            if(selectedFailure)when(sourceMapper.accounts()).thenReturn(List.of()).thenThrow(new DataAccessResourceFailureException("fixture source unavailable"));
            else when(sourceMapper.accounts()).thenThrow(new DataAccessResourceFailureException("fixture source unavailable"));
            source=proxy(new SupportLeaderboardSourceService(sourceMapper,finance),manager);
            if(prior)h.cache(NOW.minusSeconds(400));
            api=proxy(new SupportLeaderboardService(h.ownership,h.auth,h.mapper,source,h.publication,h.publicationMapper,h.config,h.avatars,manager),manager);
        }
    }
    @Test void oldMandatorySourceCounterexampleMarksGlobalRollbackAndBreaksNormalReturn(){
        Fixture f=new Fixture(false,true);
        LegacyResponse legacy=proxy(new LegacyResponse(f.source),f.manager);
        assertThrows(UnexpectedRollbackException.class,legacy::caughtSourceFailure);
        assertEquals(1,f.manager.participationFailures);assertEquals(0,f.manager.commits);assertEquals(1,f.manager.rollbacks);
        assertEquals(Set.of(1),f.manager.markedRollback);assertNull(f.manager.current.get());
    }
    @Test void isolatedPreflightFailureReturnsExistingPageDetailAndAvatarThroughOuterProxy(){
        for(String route:List.of("page","detail","avatar")){
            Fixture f=new Fixture(false,true);assertFallbackRoute(f,route);
            assertEquals(1,f.manager.commits);assertEquals(1,f.manager.rollbacks);assertEquals(1,f.manager.suspensions);
            assertEquals(Set.of(2),f.manager.markedRollback);assertEquals(List.of(1),f.manager.committedIds);
            assertEquals(List.of(2),f.manager.rolledBackIds);assertNull(f.manager.current.get());
            assertEquals(List.of(false,true),f.manager.begunReadOnly);
            verify(f.sourceMapper).accounts();verify(f.h.publication,never()).publish(any());
        }
    }
    @Test void isolatedSelectedFailureReturnsExistingPageDetailAndAvatarThroughOuterProxy(){
        for(String route:List.of("page","detail","avatar")){
            Fixture f=new Fixture(true,true);assertFallbackRoute(f,route);
            assertEquals(2,f.manager.commits);assertEquals(1,f.manager.rollbacks);assertEquals(2,f.manager.suspensions);
            assertEquals(Set.of(3),f.manager.markedRollback);assertEquals(List.of(2,1),f.manager.committedIds);
            assertEquals(List.of(3),f.manager.rolledBackIds);assertNull(f.manager.current.get());
            assertEquals(List.of(false,true,true),f.manager.begunReadOnly);
            verify(f.sourceMapper,times(2)).accounts();verify(f.h.publication,never()).publish(any());
        }
    }
    @Test void noHistoryStillReturnsSource503AndFinalRevocationStillWins403(){
        Fixture absent=new Fixture(false,false);
        assertEquals(503,assertThrows(BizException.class,()->absent.api.page(Map.of())).getCode());
        assertEquals(0,absent.manager.commits);assertEquals(2,absent.manager.rollbacks);
        assertEquals(Set.of(2),absent.manager.markedRollback);
        Fixture revoked=new Fixture(false,true);
        when(revoked.h.auth.grants(7)).thenReturn(List.of(new ffdd.opsconsole.content.mapper.SupportLeaderboardAuthorizationMapper.Grant(1L,1L,1L,1L,"service_m1_read")),List.of());
        assertEquals(403,assertThrows(BizException.class,()->revoked.api.page(Map.of())).getCode());
        assertEquals(0,revoked.manager.commits);assertEquals(2,revoked.manager.rollbacks);
        verify(revoked.h.publication,never()).publish(any());
    }
    @Test void fixedSqlAuthorizationMapperHasExistingArchitectureExceptionWithReason() throws Exception {
        String root=System.getProperty("leaderboard.candidate.root",System.getProperty("user.dir"));
        String source=java.nio.file.Files.readString(java.nio.file.Path.of(root,"src/main/java/ffdd/opsconsole/content/mapper/SupportLeaderboardAuthorizationMapper.java"));
        assertTrue(source.contains("@SuppressWarnings(\"MybatisPlusBaseMapper\")"));
        assertTrue(source.contains("generic CRUD would bypass these authorization predicates"));
    }
    static void assertFallbackRoute(Fixture f,String route){
        Map<String,List<String>> query=Map.of("expectedVersion",List.of(f.h.cached.snapshot().viewVersion()));
        if("avatar".equals(route)){
            when(f.h.auth.avatarReference(7)).thenReturn(new AssetReference("internal-asset",1L));
            var content=new SupportAttachmentService.Content("image/png",new byte[]{1,2});
            when(f.h.avatars.publicLeaderboardContent(any())).thenAnswer(i->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(1,f.manager.current.get().id);return content;});
            assertSame(content,f.api.avatar(query,7));
        }else{
            Map<String,Object> body="page".equals(route)?f.api.page(query):f.api.detail(query,7);
            assertEquals(f.h.cached.snapshot().viewVersion(),body.get("viewVersion"));
            assertEquals(true,body.get("stale"));assertEquals(true,body.get("refreshFailed"));
        }
    }

    /** Implements only resource begin/suspend/resume/commit/rollback; Spring owns all propagation rules. */
    static final class ResourceTransactions extends AbstractPlatformTransactionManager {
        final ThreadLocal<Physical> current=new ThreadLocal<>();
        int nextId,commits,rollbacks,suspensions,participationFailures;
        final Set<Integer> markedRollback=new HashSet<>();
        final List<Integer> committedIds=new ArrayList<>(),rolledBackIds=new ArrayList<>();
        final List<Boolean> begunReadOnly=new ArrayList<>();
        static final class Physical {final int id;boolean rollback;Physical(int id){this.id=id;}}
        static final class Handle implements SmartTransactionObject {
            Physical physical;Handle(Physical p){physical=p;}
            @Override public boolean isRollbackOnly(){return physical!=null && physical.rollback;}
            @Override public void flush(){ }
        }
        @Override protected Object doGetTransaction(){return new Handle(current.get());}
        @Override protected boolean isExistingTransaction(Object transaction){return ((Handle)transaction).physical!=null;}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,definition.getIsolationLevel());
            begunReadOnly.add(definition.isReadOnly());
            Handle h=(Handle)transaction;h.physical=new Physical(++nextId);current.set(h.physical);
        }
        @Override protected Object doSuspend(Object transaction){suspensions++;Physical p=current.get();current.remove();return p;}
        @Override protected void doResume(Object transaction,Object suspended){current.set((Physical)suspended);}
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status){
            Physical p=((Handle)status.getTransaction()).physical;p.rollback=true;participationFailures++;markedRollback.add(p.id);
        }
        @Override protected void doCommit(DefaultTransactionStatus status){
            Physical p=((Handle)status.getTransaction()).physical;assertFalse(p.rollback);commits++;committedIds.add(p.id);
        }
        @Override protected void doRollback(DefaultTransactionStatus status){rollbacks++;rolledBackIds.add(((Handle)status.getTransaction()).physical.id);}
        @Override protected void doCleanupAfterCompletion(Object transaction){if(current.get()==((Handle)transaction).physical)current.remove();}
    }
}
