package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.finance.domain.SupportFundAllocation;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.market.facade.SupportStakingReadFacade;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupportStakingFundEventsTest {
    static BigDecimal n(String s){return new BigDecimal(s).setScale(6);}
    Map<String,Object> position(String code,String penalty){return Map.of("customerId",7L,"positionNo","STK-test","productCode",code,"amountUsdt",n("20"),"estimatedInterestUsdt",n("2"),"earlyPenaltyBps",n(penalty));}
    Account a(String currency){return new Account(7,currency,"available");}
    Account held(){return new Account(7,"USDT","held:staking-STK-test");}
    SupportFundAllocation base(){var f=new SupportFundAllocation();f.apply(new Event("usd-origin",Action.DEPOSIT,null,a("USDT"),null,n("20"),null));f.apply(new Event("nex-origin",Action.DEPOSIT,null,a("UVT"),null,n("9"),null));return f;}
    @ParameterizedTest @ValueSource(strings={"USDT_30D","REPURCHASE_90D"})
    void lockedPrincipalAndRealInterestNeverBecomeAnotherContribution(String code){var p=position(code,"500");var f=base();assertThat(f.applyBatch(SupportStakingReadFacade.events(p,"OPEN"))).isEmpty();assertThat(f.balance(held())).isEqualByComparingTo("20");assertThat(f.applyBatch(SupportStakingReadFacade.events(p,"CLAIM"))).isEmpty();assertThat(f.total("USDT")).isEqualByComparingTo("20");assertThat(f.total("UVT")).isEqualByComparingTo("9");assertThat(f.balance(a("UVT"))).isEqualByComparingTo("9");assertThat(f.balance(a("USDT"))).isEqualByComparingTo(code.equals("REPURCHASE_90D")?"22":"20");assertThat(f.heldBalances()).isEmpty();}
    @ParameterizedTest @ValueSource(strings={"0","500","10000"})
    void penaltyConsumesOnlyItsOriginalHoldingEvenWithUnrelatedNewAvailableLots(String penalty){var p=position("REPURCHASE_90D",penalty);var f=base();f.applyBatch(SupportStakingReadFacade.events(p,"OPEN"));f.apply(new Event("later",Action.DEPOSIT,null,a("USDT"),null,n("30"),null));var early=SupportStakingReadFacade.events(p,"EARLY");assertThat(f.applyBatch(early)).isEmpty();assertThat(f.balance(a("USDT"))).isEqualByComparingTo(n("50").subtract(SupportStakingReadFacade.penalty(p)));assertThat(f.total("USDT")).isEqualByComparingTo("50");assertThat(f.total("UVT")).isEqualByComparingTo("9");assertThat(f.heldBalances()).isEmpty();assertThat(early).allMatch(e->e.from().equals(held()));}
    @Test void centralProtectedInterestCannotBeWithdrawnAsReturnedPrincipal(){var p=position("USDT_30D","500");var f=base();f.applyBatch(SupportStakingReadFacade.events(p,"OPEN"));f.applyBatch(SupportStakingReadFacade.events(p,"CLAIM"));f.apply(new Event("actual-central",Action.PROTECTED_EARNINGS,null,a("USDT"),null,n("2"),null));assertThatThrownBy(()->f.apply(new Event("over-withdraw",Action.RESERVE,a("USDT"),null,null,n("21"),null))).isInstanceOf(IllegalArgumentException.class);assertThat(f.total("USDT")).isEqualByComparingTo("20");}
    @Test void absentHoldCannotBeInventedByClaimOrEarly(){var f=base();var p=position("REPURCHASE_90D","500");assertThatThrownBy(()->f.applyBatch(SupportStakingReadFacade.events(p,"CLAIM"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->f.applyBatch(SupportStakingReadFacade.events(p,"EARLY"))).isInstanceOf(IllegalArgumentException.class);}
    @Test void unsupportedPhaseAndBooleanIdentityOrAmountFailClosed(){var p=new LinkedHashMap<>(position("REPURCHASE_90D","500"));assertThatThrownBy(()->SupportStakingReadFacade.events(p,"MATURITY")).isInstanceOf(IllegalArgumentException.class);p.put("customerId",true);assertThatThrownBy(()->SupportStakingReadFacade.events(p,"OPEN")).isInstanceOf(IllegalArgumentException.class);p.put("customerId",7L);p.put("amountUsdt",false);assertThatThrownBy(()->SupportStakingReadFacade.events(p,"OPEN")).isInstanceOf(IllegalArgumentException.class);}
}
