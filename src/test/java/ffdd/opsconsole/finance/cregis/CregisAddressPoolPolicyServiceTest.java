package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CregisAddressPoolPolicyServiceTest {
    final CregisDepositMapper db=mock(CregisDepositMapper.class);
    final CregisExceptionAdminGuard guard=mock(CregisExceptionAdminGuard.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
    final CregisAddressPoolPolicyService service=new CregisAddressPoolPolicyService(db,guard,audit,tx);
    final CregisAddressPoolPolicy policy=new CregisAddressPoolPolicy(true,1000,300,50,2,4);
    final String reason="Increase reserve for concurrent first visits";
    CregisAddressPoolPolicyServiceTest() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(guard.require(true)).thenReturn(7L);
        when(db.updatePoolPolicy(true,1000,300,50,2,4)).thenReturn(1);
        when(db.poolPolicy()).thenReturn(Map.of("enabled",1,"target",1000,"lowWatermark",300,
                "batchSize",50,"concurrency",2,"version",5L));
    }
    @Test void readRequiresLiveFinancePermission() {
        doThrow(new IllegalStateException("DENIED")).when(guard).require(false);
        assertThatThrownBy(service::read).hasMessage("DENIED");verifyNoInteractions(db);
    }
    @Test void writeRequiresLiveManagePermission() {
        doThrow(new IllegalStateException("DENIED")).when(guard).require(true);
        assertThatThrownBy(()->service.save(policy,reason)).hasMessage("DENIED");verifyNoInteractions(db,audit);
    }
    @ParameterizedTest @CsvSource({"9,1,1,1","100001,1,1,1","1000,1000,50,2","1000,0,50,2",
            "1000,300,101,2","1000,300,0,1","1000,300,50,11","1000,300,1,2"})
    void invalidCapacityNeverWrites(int target,int low,int batch,int concurrency) {
        assertThatThrownBy(()->service.save(new CregisAddressPoolPolicy(true,target,low,batch,concurrency,4),reason))
                .hasMessage("CREGIS_POOL_POLICY_INVALID");verifyNoInteractions(db,audit);
    }
    @Test void staleVersionRollsBackWithoutAudit() {
        when(db.updatePoolPolicy(true,1000,300,50,2,4)).thenReturn(0);
        assertThatThrownBy(()->service.save(policy,reason)).hasMessage("CREGIS_POOL_POLICY_VERSION_CONFLICT");
        verify(tx).rollback(any());verify(tx,never()).commit(any());verifyNoInteractions(audit);
    }
    @Test void requiredAuditFailureRollsBackConfiguration() {
        doThrow(new IllegalStateException("AUDIT_DOWN")).when(audit).recordRequired(any());
        assertThatThrownBy(()->service.save(policy,reason)).hasMessage("AUDIT_DOWN");
        verify(tx).rollback(any());verify(tx,never()).commit(any());
    }
    @Test void saveReturnsNewVersionAndAuditsInsideTransaction() {
        assertThat(service.save(policy,reason).version()).isEqualTo(5);
        var order=inOrder(db,audit,tx);
        order.verify(db).lockProvisionGate();order.verify(db).updatePoolPolicy(true,1000,300,50,2,4);
        order.verify(audit).recordRequired(any());order.verify(db).poolPolicy();order.verify(tx).commit(any());
    }
}
