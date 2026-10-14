package ffdd.opsconsole.finance.domain;

import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SupportFundClawbackTest {
    private static BigDecimal n(int value){return BigDecimal.valueOf(value).setScale(6);}
    private static Account account(String asset){return new Account(1,asset,"available");}
    private static Event credit(String id,Action action,String asset,int amount){return new Event(id,action,null,account(asset),null,n(amount),null);}
    private static Event spend(String id,String asset,int amount){return new Event(id,Action.CONSUME,account(asset),null,null,n(amount),null);}
    private static Event claw(String id,String asset,String original,int amount){return new Event(id,Action.valueOf("CLAWBACK"),account(asset),null,original,n(amount),null);}
    private static void withdraw(SupportFundAllocation funds,String asset,int amount){funds.apply(new Event("reserve",Action.RESERVE,account(asset),null,null,n(amount),null));funds.apply(new Event("success",Action.CONFIRM,null,null,"reserve",null,null));}

    @ParameterizedTest @ValueSource(strings={"USDT","NEX"}) void spentCommissionCanBeRecoveredFromLaterPrincipalWithoutChangingPerformance(String asset){
        var funds=new SupportFundAllocation();funds.apply(credit("commission",Action.EARNINGS,asset,2));funds.apply(spend("purchase",asset,2));funds.apply(credit("later",Action.DEPOSIT,asset,10));
        assertTrue(funds.apply(claw("recovery",asset,"commission",2)).isEmpty());assertEquals(n(8),funds.balance(account(asset)));assertEquals(n(12),funds.total(asset));
        withdraw(funds,asset,8);assertEquals(n(4),funds.total(asset));assertEquals(n(0),funds.balance(account(asset)));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","NEX"}) void originalCommissionIsTakenBeforeAnEarlierPrincipalLot(String asset){
        var funds=new SupportFundAllocation();funds.apply(credit("earlier",Action.DEPOSIT,asset,10));funds.apply(credit("commission",Action.EARNINGS,asset,2));funds.apply(claw("recovery",asset,"commission",2));
        funds.apply(spend("purchase",asset,10));assertEquals(n(10),funds.total(asset));assertEquals(n(0),funds.balance(account(asset)));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","NEX"}) void remainingCommissionIsTakenBeforeOtherLotsAndTheirOriginalBasisIsKept(String asset){
        var funds=new SupportFundAllocation();funds.apply(credit("commission",Action.EARNINGS,asset,4));funds.apply(spend("purchase",asset,2));funds.apply(credit("later",Action.DEPOSIT,asset,10));
        funds.apply(claw("recovery",asset,"commission",3));assertEquals(n(9),funds.balance(account(asset)));assertEquals(n(12),funds.total(asset));
        withdraw(funds,asset,9);assertEquals(n(3),funds.total(asset));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","NEX"}) void otherAvailableLotsAreTakenInTheirOriginalFormationOrder(String asset){
        var funds=new SupportFundAllocation();funds.apply(credit("commission",Action.EARNINGS,asset,6));funds.apply(spend("first",asset,6));
        funds.apply(credit("otherReward",Action.EARNINGS,asset,4));funds.apply(credit("later",Action.DEPOSIT,asset,10));
        funds.apply(claw("recovery",asset,"commission",6));funds.apply(spend("laterPurchase",asset,8));assertEquals(n(16),funds.total(asset));assertEquals(n(0),funds.balance(account(asset)));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","NEX"}) void refundAndLaterWithdrawalRetainTheOriginalConsumptionAttribution(String asset){
        var funds=new SupportFundAllocation();funds.apply(credit("commission",Action.EARNINGS,asset,10));funds.apply(spend("purchase",asset,10));funds.apply(credit("later",Action.DEPOSIT,asset,10));
        var recovery=claw("recovery",asset,"commission",10);funds.apply(recovery);assertEquals(n(20),funds.total(asset));
        funds.apply(new Event("refund",Action.REFUND,null,null,"purchase",n(10),null));assertEquals(n(10),funds.total(asset));assertEquals(n(10),funds.balance(account(asset)));
        assertTrue(funds.apply(recovery).isEmpty());assertThrows(IllegalArgumentException.class,()->funds.apply(claw("retryBeyondOriginal",asset,"commission",1)));
        withdraw(funds,asset,10);assertEquals(n(10),funds.total(asset));assertEquals(n(0),funds.balance(account(asset)));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","NEX"}) void partialRetriesCannotExceedTheOriginalRewardIncludingEarlierConservativeRecovery(String asset){
        var funds=new SupportFundAllocation();funds.apply(credit("commission",Action.EARNINGS,asset,6));funds.apply(credit("principal",Action.DEPOSIT,asset,10));
        funds.apply(new Event("oldRecovery",Action.RECOVER,account(asset),null,"commission",n(2),null));funds.apply(claw("partial",asset,"commission",3));funds.apply(claw("rest",asset,"commission",1));
        assertThrows(IllegalArgumentException.class,()->funds.apply(claw("tooMuch",asset,"commission",1)));assertEquals(n(10),funds.balance(account(asset)));assertEquals(n(10),funds.total(asset));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","NEX"}) void missingOrWrongOriginAndInsufficientAvailableCashRemainAtomic(String asset){
        var funds=new SupportFundAllocation();funds.apply(credit("principal",Action.DEPOSIT,asset,10));funds.apply(credit("commission",Action.EARNINGS,asset,2));funds.apply(spend("all",asset,12));
        assertThrows(IllegalArgumentException.class,()->funds.apply(claw("missing",asset,"unknown",1)));assertThrows(IllegalArgumentException.class,()->funds.apply(claw("principal",asset,"principal",1)));
        assertThrows(IllegalArgumentException.class,()->funds.apply(claw("noCash",asset,"commission",1)));assertEquals(n(0),funds.balance(account(asset)));assertEquals(n(12),funds.total(asset));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","NEX"}) void aRejectedBatchCannotLeaveOneCurrencyPartiallyRecovered(String asset){
        var funds=new SupportFundAllocation();funds.apply(credit("commission",Action.EARNINGS,asset,2));
        assertThrows(IllegalArgumentException.class,()->funds.applyBatch(List.of(claw("first",asset,"commission",1),claw("tooMuch",asset,"commission",2))));
        assertEquals(n(2),funds.balance(account(asset)));assertTrue(funds.apply(claw("first",asset,"commission",1)).isEmpty());assertEquals(n(1),funds.balance(account(asset)));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","NEX"}) void pendingWithdrawalsAndHeldOrRiskFundsCannotRepayAnAvailableCommissionDebt(String asset){
        var funds=new SupportFundAllocation();funds.apply(credit("commission",Action.EARNINGS,asset,6));funds.apply(new Event("reserve",Action.RESERVE,account(asset),null,null,n(2),null));
        var held=new Account(1,asset,"held:staking");var risk=new Account(1,asset,"risk:incident");
        funds.apply(new Event("lock",Action.MOVE,account(asset),held,null,n(2),null));funds.apply(new Event("risk",Action.MOVE,account(asset),risk,null,n(2),null));
        assertThrows(IllegalArgumentException.class,()->funds.apply(claw("noCash",asset,"commission",1)));assertEquals(n(2),funds.pendingWithdrawal(account(asset)));assertEquals(n(2),funds.balance(held));assertEquals(n(2),funds.balance(risk));
    }
    @ParameterizedTest @ValueSource(strings={"USDT","NEX"}) void wrongOwnerCurrencyOrShapeCannotRecoverSomeoneElsesCommission(String asset){
        var funds=new SupportFundAllocation();funds.apply(credit("commission",Action.EARNINGS,asset,2));var action=Action.valueOf("CLAWBACK");
        assertThrows(IllegalArgumentException.class,()->funds.apply(new Event("owner",action,new Account(2,asset,"available"),null,"commission",n(1),null)));
        assertThrows(IllegalArgumentException.class,()->funds.apply(claw("asset",asset.equals("USDT")?"NEX":"USDT","commission",1)));
        assertThrows(IllegalArgumentException.class,()->funds.apply(new Event("risk",action,new Account(1,asset,"risk:incident"),null,"commission",n(1),null)));
        assertThrows(IllegalArgumentException.class,()->funds.apply(new Event("target",action,account(asset),account(asset),"commission",n(1),null)));
        assertThrows(IllegalArgumentException.class,()->funds.apply(new Event("reference",action,account(asset),null,null,n(1),null)));assertEquals(n(2),funds.balance(account(asset)));
    }
}
