package ffdd.opsconsole.finance.cregis;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ffdd.opsconsole.finance.mapper.CregisDepositPolicyMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
class CregisDepositPolicySnapshotTest {
    @Test void firstDiscoveryStoresEffectiveFloorAndNormalizesIdentity() {
        var mapper=mock(CregisDepositPolicyMapper.class);var config=new CregisProperties();config.setDepositConfirmations(20);
        var service=new CregisDepositPolicyService(mapper,mock(AuditLogService.class),config);
        var current=new CregisDepositPolicy(2,BigDecimal.ONE,BigDecimal.ZERO,15);
        var frozen=new CregisDepositPolicy(2,BigDecimal.ONE,BigDecimal.ZERO,20);
        String hash="A".repeat(64),canonical="0x"+"a".repeat(64);
        when(mapper.lockPolicy()).thenReturn(1);when(mapper.lockCurrent()).thenReturn(current);when(mapper.lockSnapshot(CregisNetwork.BEP20,88,canonical)).thenReturn(frozen);
        assertThat(service.discover(88,hash)).isEqualTo(frozen);
        verify(mapper).insertSnapshot(CregisNetwork.BEP20,88,canonical,2,20);
    }
    @Test void rediscoveryReturnsOriginalPolicyEvenAfterCurrentChanges() {
        var mapper=mock(CregisDepositPolicyMapper.class);var config=new CregisProperties();var service=new CregisDepositPolicyService(mapper,mock(AuditLogService.class),config);
        var original=new CregisDepositPolicy(0,BigDecimal.TEN,BigDecimal.ONE,20);
        var next=new CregisDepositPolicy(1,BigDecimal.ONE,BigDecimal.ZERO,15);
        String canonical="0x"+"a".repeat(64);when(mapper.lockPolicy()).thenReturn(1);when(mapper.lockCurrent()).thenReturn(next);when(mapper.lockSnapshot(CregisNetwork.BEP20,88,canonical)).thenReturn(original);
        assertThat(service.discover(88,canonical)).isEqualTo(original);
        config.setDepositConfirmations(25);when(mapper.snapshot(CregisNetwork.BEP20,88,canonical)).thenReturn(original);
        assertThat(service.snapshot(88,canonical).confirmations()).isEqualTo(25);
        config.setDepositConfirmations(15);assertThat(service.snapshot(88,canonical).confirmations()).isEqualTo(20);
    }
}
