package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.finance.cregis.CregisPayoutControl;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WithdrawalPayoutDispatchGuardTest {
    final WithdrawalPayoutMapper mapper=mock(WithdrawalPayoutMapper.class);
    final CregisDepositMapper deposits=mock(CregisDepositMapper.class);
    final CregisPayoutControl control=mock(CregisPayoutControl.class);
    final WithdrawalPayoutDispatchGuard guard=new WithdrawalPayoutDispatchGuard(mapper,deposits,control);
    final LocalDateTime now=LocalDateTime.of(2026,10,22,0,0);
    @BeforeEach void setup(){
        when(deposits.lockProvisionGate()).thenReturn(Map.of("state","IDLE","payoutEnabled",1));
        when(control.enabled()).thenReturn(true);when(control.enabled(any())).thenReturn(true);
        when(mapper.lockNetworkPayoutEnabled(anyString())).thenReturn("true");when(mapper.lockCashCustomer(7L)).thenReturn(7L);
        when(mapper.claimNewPayout("WD-guard",now,now.plusMinutes(2))).thenReturn(1);
        when(mapper.markProviderIntent("WD-guard",now)).thenReturn(1);when(mapper.payout("WD-guard")).thenReturn(row("PROCESSING","provider"));
    }
    @ParameterizedTest @ValueSource(strings={"STOP","BLOCKED","RISK"})
    void committedStopOrRiskCreatesNeitherClaimNorIntent(String reason){
        if("STOP".equals(reason))when(deposits.lockProvisionGate()).thenReturn(Map.of("state","IDLE","payoutEnabled",0));
        else if("BLOCKED".equals(reason))when(deposits.lockProvisionGate()).thenReturn(Map.of("state","BLOCKED","payoutEnabled",1));
        else when(control.enabled()).thenReturn(false);
        assertThat(guard.begin(row("REVIEW_PASSED",null),now)).isNull();
        verify(mapper,never()).claimNewPayout(any(),any(),any());verify(mapper,never()).markProviderIntent(any(),any());
    }
    @Test void gateThenUserThenOrderLocksPersistOneIntent(){
        assertThat(guard.begin(row("REVIEW_PASSED",null),now).payoutSource()).isEqualTo("provider");
        var order=inOrder(deposits,control,mapper);order.verify(deposits).lockProvisionGate();order.verify(control).enabled();
        order.verify(mapper).lockCashCustomer(7L);order.verify(mapper).claimNewPayout("WD-guard",now,now.plusMinutes(2));order.verify(mapper).markProviderIntent("WD-guard",now);
    }
    @Test void lostUserOrClaimNeverWritesIntentAndLostIntentMustRollbackClaim(){
        when(mapper.lockCashCustomer(7L)).thenReturn(null);assertThat(guard.begin(row("REVIEW_PASSED",null),now)).isNull();
        when(mapper.lockCashCustomer(7L)).thenReturn(7L);when(mapper.claimNewPayout("WD-guard",now,now.plusMinutes(2))).thenReturn(0);
        assertThat(guard.begin(row("REVIEW_PASSED",null),now)).isNull();verify(mapper,never()).markProviderIntent(any(),any());
        when(mapper.claimNewPayout("WD-guard",now,now.plusMinutes(2))).thenReturn(1);when(mapper.markProviderIntent("WD-guard",now)).thenReturn(0);
        assertThatThrownBy(()->guard.begin(row("REVIEW_PASSED",null),now)).hasMessage("CREGIS_DISPATCH_INTENT_CONFLICT");
    }
    @Test void erc20NeedsBothNetworkSwitchAndCurrentPayoutPermission(){
        var base=row("REVIEW_PASSED",null);
        var erc=new WithdrawalPayoutMapper.PayoutRow(base.withdrawalNo(),base.userId(),"USDT-ERC20",base.targetAddress(),base.amount(),base.netReceive(),base.nexBurned(),base.status(),null,null,base.providerIdempotencyKey(),null,0);
        when(mapper.lockNetworkPayoutEnabled("withdrawal.erc20.enabled")).thenReturn("false");
        assertThat(guard.begin(erc,now)).isNull();
        when(mapper.lockNetworkPayoutEnabled("withdrawal.erc20.enabled")).thenReturn("true");
        when(control.enabled(ffdd.opsconsole.finance.cregis.CregisNetwork.ERC20)).thenReturn(false);
        assertThat(guard.begin(erc,now)).isNull();
        verify(mapper,never()).claimNewPayout(any(),any(),any());
        when(control.enabled(ffdd.opsconsole.finance.cregis.CregisNetwork.ERC20)).thenReturn(true);
        when(mapper.payout("WD-guard")).thenReturn(new WithdrawalPayoutMapper.PayoutRow(erc.withdrawalNo(),erc.userId(),erc.chain(),erc.targetAddress(),erc.amount(),erc.netReceive(),erc.nexBurned(),"PROCESSING",null,null,erc.providerIdempotencyKey(),"provider",0));
        assertThat(guard.begin(erc,now).chain()).isEqualTo("USDT-ERC20");
    }
    static WithdrawalPayoutMapper.PayoutRow row(String state,String source){return new WithdrawalPayoutMapper.PayoutRow("WD-guard",7L,"USDT-BEP20","0x"+"a".repeat(40),BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ZERO,state,null,null,"WD-guard",source,0);}
}
