package ffdd.opsconsole.finance.cregis;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
final class DepositPolicyFixture {
    static CregisDepositService withPolicy(CregisDepositService service) { return service; }
    static CregisDepositPolicyService policy() {
        var policy=mock(CregisDepositPolicyService.class);
        var baseline=new CregisDepositPolicy(0,BigDecimal.TEN,BigDecimal.ONE,15);
        when(policy.current()).thenReturn(baseline);
        when(policy.snapshot(anyLong(),anyString())).thenReturn(baseline);
        when(policy.discover(anyLong(),anyString())).thenReturn(baseline);
        when(policy.snapshot(eq(CregisNetwork.ERC20),anyLong(),anyString())).thenReturn(baseline);
        when(policy.discover(eq(CregisNetwork.ERC20),anyLong(),anyString())).thenReturn(baseline);
        when(policy.snapshot(org.mockito.ArgumentMatchers.eq(CregisNetwork.BEP20),anyLong(),anyString())).thenReturn(baseline);
        when(policy.discover(org.mockito.ArgumentMatchers.eq(CregisNetwork.BEP20),anyLong(),anyString())).thenReturn(baseline);
        return policy;
    }
}
