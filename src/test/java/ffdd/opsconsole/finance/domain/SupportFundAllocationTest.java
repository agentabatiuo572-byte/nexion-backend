package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ffdd.opsconsole.finance.domain.SupportFundAllocation.*;

class SupportFundAllocationTest {
    private final SupportFundAllocation funds = new SupportFundAllocation();
    private final Account a = new Account(1, "USDT", "available");
    private final Account b = new Account(2, "USDT", "available");
    private BigDecimal n(String value) { return new BigDecimal(value); }
    private void money(String expected, BigDecimal actual) { assertEquals(0, n(expected).compareTo(actual)); }
    private Event event(String id, Action action, Account from, Account to, String reference, String amount, String target) {
        return new Event(id, action, from, to, reference, amount == null ? null : n(amount), target == null ? null : n(target));
    }
    private List<Change> apply(String id, Action action, Account from, Account to, String reference, String amount, String target) {
        return funds.apply(event(id, action, from, to, reference, amount, target));
    }
    private void deposit(String id, Account account, String amount) { apply(id, Action.DEPOSIT, null, account, null, amount, null); }
    private void earning(String id, Account account, String amount) { apply(id, Action.EARNINGS, null, account, null, amount, null); }
    private void consume(String id, Account account, String amount) { apply(id, Action.CONSUME, account, null, null, amount, null); }
    private void reserve(String id, Account account, String amount) { apply(id, Action.RESERVE, account, null, null, amount, null); }
    private void confirm(String id, String reservation) { apply(id, Action.CONFIRM, null, null, reservation, null, null); }
    private void refund(String id, String purchase, String amount) { apply(id, Action.REFUND, null, null, purchase, amount, null); }

    @Test void rechargeConsumptionAndPrincipalWithdrawalCountTheMoneyOnce() {
        deposit("d", a, "1000"); consume("p", a, "600"); reserve("w", a, "400");
        money("1000", funds.total("USDT")); money("0", funds.balance(a));
        confirm("wc", "w"); money("600", funds.total("USDT"));
    }
    @Test void releasedEarningsWithdrawalDoesNotPenalizeRecharge() {
        deposit("d", a, "1000"); earning("e", a, "200"); reserve("w", a, "200"); confirm("wc", "w");
        money("1000", funds.total("USDT")); money("1000", funds.balance(a));
    }
    @Test void walletRefundThenPrincipalWithdrawalDoesNotDeductTwice() {
        deposit("d", a, "1000"); consume("p", a, "600"); refund("r", "p", "600");
        money("1000", funds.total("USDT")); money("1000", funds.balance(a));
        reserve("w", a, "1000"); confirm("wc", "w"); money("0", funds.total("USDT"));
    }
    @Test void spendingUncountedEarningsAddsContributionAndRefundReversesOnlyThatAddition() {
        earning("e", a, "600"); consume("p", a, "600"); money("600", funds.total("USDT"));
        refund("r", "p", "600"); money("0", funds.total("USDT"));
        reserve("w", a, "600"); confirm("wc", "w"); money("0", funds.total("USDT"));
    }
    @Test void mixedPartialRefundRestoresTheOriginalSlices() {
        deposit("d", a, "100"); earning("e", a, "100"); consume("p", a, "150");
        money("150", funds.total("USDT")); refund("r1", "p", "80"); money("150", funds.total("USDT"));
        refund("r2", "p", "50"); money("120", funds.total("USDT"));
        refund("r3", "p", "20"); money("100", funds.total("USDT")); money("200", funds.balance(a));
        reserve("w", a, "200"); confirm("wc", "w"); money("0", funds.total("USDT"));
    }
    @Test void laterIncomeCannotReplaceAnAlreadyReservedPrincipalSlice() {
        deposit("d", a, "1000"); reserve("w", a, "400"); earning("later", a, "400");
        confirm("wc", "w"); money("600", funds.total("USDT")); money("1000", funds.balance(a));
    }
    @Test void failedOrRejectedWithdrawalReturnsItsExactOriginalSources() {
        deposit("d", a, "100"); earning("e", a, "100"); reserve("w", a, "150");
        apply("wf", Action.RETURN, null, null, "w", null, null);
        money("100", funds.total("USDT")); money("200", funds.balance(a));
        reserve("w2", a, "100"); confirm("w2c", "w2"); money("100", funds.total("USDT"));
        assertThrows(IllegalArgumentException.class, () -> confirm("late", "w"));
    }
    @Test void duplicateReceiptIsNoOpAndConflictingReceiptCannotChangeBalances() {
        Event d = event("d", Action.DEPOSIT, null, a, null, "100", null);
        funds.apply(d); assertTrue(funds.apply(d).isEmpty()); money("100", funds.balance(a));
        assertThrows(IllegalArgumentException.class, () -> deposit("d", a, "101"));
        money("100", funds.total("USDT")); money("100", funds.balance(a));
        reserve("w", a, "100"); confirm("wc", "w"); confirm("wc", "w"); money("0", funds.total("USDT"));
        assertThrows(IllegalArgumentException.class, () -> confirm("different-confirmation", "w"));
    }
    @Test void feesInTheReservedWithdrawalDoNotCreateExtraContribution() {
        deposit("d", a, "1000"); reserve("w", a, "400");
        List<Change> changes = apply("wc", Action.CONFIRM, null, null, "w", null, null);
        money("-400", changes.get(0).amount()); money("600", funds.total("USDT"));
    }
    @Test void pendingWithdrawalReconcilesTheGrossReservationUntilItsRealTerminalAction() {
        Account nex = new Account(1, "UVT", "available");
        deposit("a-d", a, "1000"); deposit("b-d", b, "100"); deposit("n-d", nex, "4");
        money("0", funds.pendingWithdrawal(a));
        reserve("a-w", a, "400"); reserve("a-w2", a, "100"); reserve("b-w", b, "25"); reserve("n-w", nex, "2");
        money("500", funds.pendingWithdrawal(a)); money("25", funds.pendingWithdrawal(b)); money("2", funds.pendingWithdrawal(nex));
        confirm("a-confirmed", "a-w"); confirm("a-confirmed", "a-w");
        money("100", funds.pendingWithdrawal(a));
        apply("a-failed", Action.RETURN, null, null, "a-w2", null, null);
        money("0", funds.pendingWithdrawal(a)); money("600", funds.balance(a));
        assertThrows(IllegalArgumentException.class, () -> funds.applyBatch(List.of(
                event("partial", Action.RESERVE, a, null, null, "300", null),
                event("insufficient", Action.RESERVE, a, null, null, "500", null))));
        money("0", funds.pendingWithdrawal(a)); money("600", funds.balance(a));
        money("25", funds.pendingWithdrawal(b)); money("2", funds.pendingWithdrawal(nex));
        assertThrows(IllegalArgumentException.class, () -> funds.pendingWithdrawal(null));
        assertThrows(IllegalArgumentException.class, () -> funds.pendingWithdrawal(new Account(1, "USDT", "held:staking")));
    }
    @Test void crossCustomerSecondarySaleKeepsTheOriginalContribution() {
        deposit("d", a, "100"); apply("trade", Action.TRADE, a, b, null, "100", "90");
        money("100", funds.total("USDT")); money("90", funds.balance(b));
        consume("seller-purchase", b, "40"); money("100", funds.total("USDT"));
        reserve("seller-w", b, "50"); List<Change> out = apply("seller-wc", Action.CONFIRM, null, null, "seller-w", null, null);
        money("50", funds.total("USDT")); assertEquals("d", out.get(0).contributionId());
        assertThrows(IllegalArgumentException.class, () -> refund("unsupported-trade-refund", "trade", "100"));
    }
    @Test void earningsPaidSecondarySaleCountsOnceDespiteSellersNextPurchase() {
        earning("e", a, "100"); apply("trade", Action.TRADE, a, b, null, "100", "100");
        money("100", funds.total("USDT")); consume("seller-p", b, "100"); money("100", funds.total("USDT"));
    }
    @Test void ordinaryHoldAndRiskFreezeDoNotCountAsConsumption() {
        Account held = new Account(1, "USDT", "held:staking-1");
        Account risk = new Account(1, "USDT", "risk:cregis-1");
        deposit("d", a, "100"); apply("lock", Action.MOVE, a, held, null, "60", null);
        apply("risk", Action.MOVE, a, risk, null, "40", null); money("100", funds.total("USDT"));
        apply("unlock", Action.MOVE, held, a, null, "60", null);
        apply("unrisk", Action.MOVE, risk, a, null, "40", null);
        reserve("w", a, "100"); confirm("wc", "w"); money("0", funds.total("USDT"));
    }
    @Test void heldPrincipalAndNewInterestKeepDifferentProvenance() {
        Account held = new Account(1, "USDT", "held:repurchase-1");
        deposit("d", a, "100"); apply("lock", Action.MOVE, a, held, null, "100", null);
        apply("return", Action.MOVE, held, a, null, "100", null); earning("interest", a, "10");
        reserve("w", a, "10"); confirm("wc", "w"); money("100", funds.total("USDT"));
    }
    @Test void exchangePreservesOriginalCurrencyCostBasisAndRoundingRemainder() {
        Account nex = new Account(1, "UVT", "available");
        deposit("d", a, "1"); apply("fx", Action.EXCHANGE, a, nex, null, "1", "3");
        money("1", funds.total("USDT")); money("0", funds.total("UVT"));
        reserve("w1", nex, "1"); confirm("wc1", "w1"); money("0.666667", funds.total("USDT"));
        reserve("w2", nex, "1"); confirm("wc2", "w2"); money("0.333334", funds.total("USDT"));
        reserve("w3", nex, "1"); confirm("wc3", "w3"); money("0", funds.total("USDT"));
    }
    @Test void newNexConsumptionAndReversalStayInNexWithoutMixingCurrencies() {
        Account nex = new Account(1, "UVT", "available");
        deposit("d", a, "100"); earning("e", nex, "12.345678"); consume("p", nex, "12.345678");
        money("100", funds.total("USDT")); money("12.345678", funds.total("UVT"));
        refund("r", "p", "0.000001"); money("12.345677", funds.total("UVT"));
    }
    @Test void repeatedPartialReturnConservesExactSixDecimalOrigin() {
        deposit("d", a, "1"); consume("p", a, "1");
        refund("r1", "p", "0.333333"); refund("r2", "p", "0.333333"); refund("r3", "p", "0.333334");
        reserve("w", a, "1"); confirm("wc", "w"); money("0", funds.total("USDT"));
    }
    @Test void protectedEarningsCannotBeSelectedForWithdrawalBeforeRelease() {
        deposit("d", a, "100"); apply("e", Action.PROTECTED_EARNINGS, null, a, null, "200", null);
        reserve("w", a, "100"); confirm("wc", "w"); money("0", funds.total("USDT"));
        assertThrows(IllegalArgumentException.class, () -> reserve("bad", a, "200"));
        apply("release", Action.RELEASE, a, null, "e", null, null);
        reserve("ok", a, "200"); confirm("okc", "ok"); money("0", funds.total("USDT"));
    }
    @Test void refundAfterEarningsReleaseRestoresReleasedFunds() {
        apply("e", Action.PROTECTED_EARNINGS, null, a, null, "100", null); consume("p", a, "100");
        apply("release", Action.RELEASE, a, null, "e", null, null); refund("r", "p", "100");
        reserve("w", a, "100"); confirm("wc", "w"); money("0", funds.total("USDT"));
    }
    @Test void overspendOverRefundWrongCurrencyAndUnknownReferencesFailBeforeMutation() {
        deposit("d", a, "100");
        assertThrows(IllegalArgumentException.class, () -> consume("bad", a, "101")); money("100", funds.balance(a));
        consume("p", a, "50");
        assertThrows(IllegalArgumentException.class, () -> refund("bad-r", "p", "51")); money("50", funds.balance(a));
        assertThrows(IllegalArgumentException.class, () -> confirm("bad-w", "missing"));
        assertThrows(IllegalArgumentException.class, () -> apply("bad-m", Action.MOVE, a, new Account(1, "UVT", "available"), null, "1", null));
        money("50", funds.balance(a)); money("100", funds.total("USDT"));
    }
    @Test void withdrawalFromLockAndMalformedPrecisionAreRejected() {
        Account held = new Account(1, "USDT", "held:1"); deposit("d", held, "100");
        assertThrows(IllegalArgumentException.class, () -> reserve("bad", held, "1"));
        for (String amount : List.of("0", "-1", "0.0000001")) assertThrows(IllegalArgumentException.class, () -> deposit("bad-" + amount, a, amount));
        money("100", funds.balance(held)); money("0", funds.balance(a));
    }
    @Test void conversionTooSmallToPreserveEverySourceCannotSilentlyLoseBasis() {
        Account nex = new Account(1, "UVT", "available"); deposit("d1", a, "0.000001"); deposit("d2", a, "1");
        assertThrows(IllegalArgumentException.class, () -> apply("fx", Action.EXCHANGE, a, nex, null, "1.000001", "0.000001"));
        money("1.000001", funds.balance(a)); money("0", funds.balance(nex)); money("1.000001", funds.total("USDT"));
    }
    @Test void refundKeepsOriginalSourceOrderAndContributionOwner() {
        deposit("old", a, "100"); consume("p", a, "100"); deposit("new", a, "100"); refund("r", "p", "100");
        reserve("w", a, "100");
        assertEquals("old", apply("wc", Action.CONFIRM, null, null, "w", null, null).get(0).contributionId());
    }
    @Test void returnedWithdrawalKeepsOriginalSourceOrder() {
        deposit("old", a, "100"); reserve("w", a, "100"); deposit("new", a, "100");
        apply("return", Action.RETURN, null, null, "w", null, null); reserve("w2", a, "100");
        assertEquals("old", apply("wc", Action.CONFIRM, null, null, "w2", null, null).get(0).contributionId());
    }
    @Test void unlockingDoesNotMakeAnOldSourceNew() {
        Account held = new Account(1, "USDT", "held:staking-1");
        deposit("old", a, "100"); apply("lock", Action.MOVE, a, held, null, "100", null); deposit("new", a, "100");
        apply("unlock", Action.MOVE, held, a, null, "100", null); reserve("w", a, "100");
        assertEquals("old", apply("wc", Action.CONFIRM, null, null, "w", null, null).get(0).contributionId());
    }
    @Test void feeFromUncountedEarningsDoesNotCreatePerformanceOrRefundDecrement() {
        earning("e", a, "100"); apply("fee", Action.FEE, a, null, null, "10", null);
        money("0", funds.total("USDT")); money("90", funds.balance(a)); refund("r", "fee", "10");
        money("0", funds.total("USDT")); money("100", funds.balance(a));
    }
    @Test void refundedFeeKeepsOriginalCreditedPrincipal() {
        deposit("d", a, "100"); apply("fee", Action.FEE, a, null, null, "10", null); refund("r", "fee", "10");
        money("100", funds.total("USDT")); reserve("w", a, "100"); confirm("wc", "w"); money("0", funds.total("USDT"));
    }
    @Test void exchangeSeparatesActualPrincipalFromNeutralFee() {
        Account nex = new Account(1, "UVT", "available"); earning("e", a, "100");
        funds.applyBatch(List.of(event("fee", Action.FEE, a, null, null, "10", null), event("fx", Action.EXCHANGE, a, nex, null, "90", "180")));
        money("0", funds.total("USDT")); money("0", funds.total("UVT"));
        consume("p", nex, "180"); money("180", funds.total("UVT"));
    }
    @Test void compoundOperationFailureRollsBackEveryEventAndReceipt() {
        deposit("d", a, "100");
        assertThrows(IllegalArgumentException.class, () -> funds.applyBatch(List.of(
                event("p", Action.CONSUME, a, null, null, "60", null), event("too-much", Action.RESERVE, a, null, null, "41", null))));
        money("100", funds.balance(a)); money("100", funds.total("USDT"));
        consume("p", a, "10"); money("90", funds.balance(a));
    }
    @Test void formalEventValidationRejectsNullAndWrongShapeWithoutReplayingMoney() {
        assertThrows(IllegalArgumentException.class, () -> SupportFundAllocation.validate(null));
        assertThrows(IllegalArgumentException.class, () -> SupportFundAllocation.validate(event("bad", Action.CONFIRM, a, null, "w", null, null)));
        SupportFundAllocation.validate(event("ok", Action.RESERVE, a, null, null, "100", null));
        money("0", funds.balance(a)); money("0", funds.total("USDT"));
    }
    @Test void returnedSlicesOfOneIncomeKeepTheirOriginalCreditOrder() {
        Account held = new Account(2, "USDT", "held:staking-1"); earning("e", a, "200");
        apply("first", Action.TRADE, a, b, null, "100", "100"); apply("lock", Action.MOVE, b, held, null, "100", null);
        apply("second", Action.TRADE, a, b, null, "100", "100"); apply("unlock", Action.MOVE, held, b, null, "100", null);
        reserve("w", b, "100");
        assertEquals("first", apply("wc", Action.CONFIRM, null, null, "w", null, null).get(0).contributionId());
    }
    @Test void refundedSlicesOfOneIncomeKeepEarlierContribution() {
        earning("e", a, "200"); apply("first", Action.TRADE, a, b, null, "100", "100"); consume("p", b, "100");
        apply("second", Action.TRADE, a, b, null, "100", "100"); refund("r", "p", "100"); reserve("w", b, "100");
        assertEquals("first", apply("wc", Action.CONFIRM, null, null, "w", null, null).get(0).contributionId());
    }

    @Test void reorgReverseThenReturnOfCollectedFundsPreservesOriginalLotsAndContribution() {
        deposit("d",a,"100");consume("spent",a,"60");
        var held=new Account(1,"USDT","risk:CR-1");apply("hold",Action.MOVE,a,held,null,"40",null);
        apply("seize",Action.REORG_DEBIT,held,null,"d","40",null);
        apply("reverse",Action.REVOKE_DEPOSIT,null,null,"d","100",null);
        money("0",funds.total("USDT"));earning("later",a,"20");
        apply("collect",Action.REORG_DEBIT,a,null,"d","20",null);money("0",funds.balance(a));
        apply("restore",Action.RESTORE_DEPOSIT,null,null,"d","100",null);
        apply("returnHeld",Action.REORG_REFUND,null,a,"seize","40",null);
        apply("returnLater",Action.REORG_REFUND,null,a,"collect","20",null);
        money("60",funds.balance(a));money("100",funds.total("USDT"));
        assertThrows(IllegalArgumentException.class,()->apply("doubleRestore",Action.RESTORE_DEPOSIT,null,null,"d","100",null));
        assertThrows(IllegalArgumentException.class,()->apply("doubleRefund",Action.REORG_REFUND,null,a,"collect","20",null));
        money("60",funds.balance(a));money("100",funds.total("USDT"));
    }
    @Test void reorgSuppressesOnlyTheRevokedOriginalContributionAndSupportsSecondReorgEpoch() {
        deposit("d",a,"100");reserve("w",a,"40");
        apply("seize",Action.REORG_DEBIT,a,null,"d","60",null);apply("reverse",Action.REVOKE_DEPOSIT,null,null,"d","100",null);
        confirm("wc","w");money("0",funds.total("USDT"));
        apply("restore",Action.RESTORE_DEPOSIT,null,null,"d","100",null);apply("refund",Action.REORG_REFUND,null,a,"seize","60",null);
        money("60",funds.total("USDT"));money("60",funds.balance(a));
        apply("seize2",Action.REORG_DEBIT,a,null,"d","60",null);apply("reverse2",Action.REVOKE_DEPOSIT,null,null,"d","100",null);money("0",funds.total("USDT"));
    }
    @Test void reorgCannotCollectAnotherCustomerOrReturnToAnotherAccount() {
        deposit("d",a,"100");deposit("peer",b,"100");
        assertThrows(IllegalArgumentException.class,()->apply("bad",Action.REORG_DEBIT,b,null,"d","10",null));
        apply("debit",Action.REORG_DEBIT,a,null,"d","10",null);
        assertThrows(IllegalArgumentException.class,()->apply("badReturn",Action.REORG_REFUND,null,b,"debit","10",null));
        money("90",funds.balance(a));money("100",funds.balance(b));
    }
}
