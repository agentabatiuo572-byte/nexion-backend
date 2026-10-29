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

    @Test void cacheRoutesUseOneAuthorizationTransactionWithoutSourceTransaction(){
        Harness h=new Harness();h.cache(NOW.minusSeconds(601));ResourceTransactions tx=new ResourceTransactions();
        var service=proxy(new SupportLeaderboardService(h.ownership,h.auth,h.cache,h.avatars,Clock.fixed(NOW,ZoneOffset.UTC)),tx);
        assertEquals(h.cached.snapshot().viewVersion(),service.page(Map.of()).get("viewVersion"));
        assertEquals(1,tx.commits);assertEquals(0,tx.rollbacks);assertEquals(0,tx.suspensions);verifyNoInteractions(h.source);
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
