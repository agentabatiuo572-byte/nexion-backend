package ffdd.opsconsole.finance.domain;

import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportFundEarningsRecoveryTest {
    private static BigDecimal n(int amount) { return BigDecimal.valueOf(amount).setScale(6); }
    private static Account account(String currency) { return new Account(1,currency,"available"); }
    private static Event credit(String id,Action action,String currency,int amount) {
        return new Event(id,action,null,account(currency),null,n(amount),null);
    }
    private static Event recover(String id,String currency,String reference,int amount) {
        return new Event(id,Action.valueOf("RECOVER"),account(currency),null,reference,n(amount),null);
    }
    @Test void partialRecoveryRemovesOriginalEarningsWithoutConsumingEarlierDeposit() {
        var funds=new SupportFundAllocation();funds.apply(credit("deposit",Action.DEPOSIT,"USDT",100));
        funds.apply(credit("reward",Action.EARNINGS,"USDT",20));
        assertTrue(funds.apply(recover("recover","USDT","reward",8)).isEmpty());
        assertEquals(n(112),funds.balance(account("USDT")));assertEquals(n(100),funds.total("USDT"));
        funds.apply(recover("rest","USDT","reward",12));
        assertEquals(n(100),funds.balance(account("USDT")));assertEquals(n(100),funds.total("USDT"));
    }
    @Test void spentRewardCannotBeRecoveredFromAnUnrelatedLaterDeposit() {
        var funds=new SupportFundAllocation();funds.apply(credit("reward",Action.EARNINGS,"USDT",20));
        funds.apply(new Event("spend",Action.CONSUME,account("USDT"),null,null,n(15),null));
        funds.apply(credit("later",Action.DEPOSIT,"USDT",100));
        assertThrows(IllegalArgumentException.class,()->funds.apply(recover("recover","USDT","reward",6)));
        assertEquals(n(105),funds.balance(account("USDT")));assertEquals(n(115),funds.total("USDT"));
        funds.apply(recover("remaining","USDT","reward",5));assertEquals(n(100),funds.balance(account("USDT")));
    }
    @Test void protectedNexCanBeRecoveredWithoutReleaseOrChangingUsdt() {
        var funds=new SupportFundAllocation();funds.apply(credit("deposit",Action.DEPOSIT,"USDT",100));
        funds.apply(credit("reward",Action.PROTECTED_EARNINGS,"UVT",7));
        funds.apply(recover("recover","UVT","reward",3));
        assertEquals(n(4),funds.balance(account("UVT")));assertEquals(n(100),funds.balance(account("USDT")));
        assertThrows(IllegalArgumentException.class,()->funds.apply(new Event("withdraw",Action.RESERVE,account("UVT"),null,null,n(1),null)));
    }
    @Test void wrongAssetOrNonEarningsReferenceCannotRecoverPrincipal() {
        assertDoesNotThrow(()->Action.valueOf("RECOVER"));
        var funds=new SupportFundAllocation();funds.apply(credit("deposit",Action.DEPOSIT,"USDT",100));
        funds.apply(credit("reward",Action.EARNINGS,"UVT",20));
        assertThrows(IllegalArgumentException.class,()->funds.apply(recover("wrong","USDT","reward",1)));
        assertThrows(IllegalArgumentException.class,()->funds.apply(recover("principal","USDT","deposit",1)));
        assertEquals(n(100),funds.balance(account("USDT")));assertEquals(n(20),funds.balance(account("UVT")));
    }
    @Test void nexGrossExchangeThenFeeInDedicatedTargetSlotPreservesOriginalNexBasis() {
        var funds=new SupportFundAllocation();funds.apply(credit("usd",Action.DEPOSIT,"USDT",100));
        funds.apply(credit("nex",Action.DEPOSIT,"UVT",20));var target=new Account(1,"USDT","held:exchange-target-order");
        funds.applyBatch(java.util.List.of(new Event("fx",Action.EXCHANGE,account("UVT"),target,null,n(20),n(7)),
            new Event("fee",Action.FEE,target,null,null,n(1),null),new Event("net",Action.MOVE,target,account("USDT"),null,n(6),null)));
        assertEquals(n(106),funds.balance(account("USDT")));assertTrue(funds.heldBalances().isEmpty());
        assertEquals(n(100),funds.total("USDT"));assertEquals(n(20),funds.total("UVT"));
    }
    @Test void queuedFxMayExchangeItsOwnHeldSourceWithoutDebitingAvailableAgain() {
        var funds=new SupportFundAllocation();funds.apply(credit("deposit",Action.DEPOSIT,"USDT",20));
        var held=new Account(1,"USDT","held:exchange-order");
        funds.apply(new Event("hold",Action.MOVE,account("USDT"),held,null,n(10),null));
        funds.applyBatch(java.util.List.of(new Event("fee",Action.FEE,held,null,null,n(1),null),
            new Event("fx",Action.EXCHANGE,held,account("UVT"),null,n(9),n(3))));
        assertEquals(n(10),funds.balance(account("USDT")));assertEquals(n(3),funds.balance(account("UVT")));
        assertTrue(funds.heldBalances().isEmpty());
    }
    @Test void fxOrFeeCannotBorrowUnverifiedRiskSlots() {
        var risk=new Account(1,"USDT","risk:incident");
        assertThrows(IllegalArgumentException.class,()->SupportFundAllocation.validate(new Event("fee",Action.FEE,risk,null,null,n(1),null)));
        assertThrows(IllegalArgumentException.class,()->SupportFundAllocation.validate(new Event("fx",Action.EXCHANGE,risk,account("UVT"),null,n(1),n(1))));
    }
}
