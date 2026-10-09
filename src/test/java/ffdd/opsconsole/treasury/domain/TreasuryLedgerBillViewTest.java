package ffdd.opsconsole.treasury.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TreasuryLedgerBillViewTest {

    @Test
    void exposesOnlyTheEightCanonicalBillTypesAndPreservesRawSubtype() {
        List<String> rawTypes = List.of(
                "EXCHANGE", "CARD_TOPUP", "WITHDRAWAL", "ADJUSTMENT",
                "COMMISSION", "CHARGEBACK_RECOVERY", "TRIAL_BONUS", "ORDER_PURCHASE");
        Set<String> canonical = new LinkedHashSet<>();
        for (int index = 0; index < rawTypes.size(); index++) {
            TreasuryLedgerBillView row = new TreasuryLedgerBillView(
                    (long) index + 1, 1L, "U00000001", "user", "B-" + index, rawTypes.get(index),
                    "USDT", index == 7 ? "OUT" : "IN", BigDecimal.ONE, BigDecimal.ONE, "POSTED", "remark",
                    LocalDateTime.MIN, LocalDateTime.MIN);
            canonical.add(row.billType());
            assertThat(row.subtype()).isEqualTo(rawTypes.get(index).toLowerCase());
        }

        assertThat(canonical).containsExactly(
                "swap", "topup", "withdraw", "earning", "commission", "refund", "bonus", "purchase");
    }

    @Test
    void classifiesRefundsBeforeWithdrawalsAndRewardsAsBonus() {
        TreasuryLedgerBillView refund = row("WITHDRAW_FEE_OFFSET_REFUND");
        TreasuryLedgerBillView referralReward = row("REFERRAL_REWARD");
        TreasuryLedgerBillView questReward = row("QUEST_REWARD");

        assertThat(refund.billType()).isEqualTo("refund");
        assertThat(referralReward.billType()).isEqualTo("bonus");
        assertThat(questReward.billType()).isEqualTo("bonus");
        assertThat(row("LEARNING_REWARD").billType()).isEqualTo("bonus");
    }

    @Test
    void classifiesComputeIncomeAndDailyCheckInWithExactNormalizedTypes() {
        assertThat(row("COMPUTE_TASK_REWARD").billType()).isEqualTo("earning");
        assertThat(row(" compute_task_reward ").billType()).isEqualTo("earning");
        assertThat(row("DAILY_CHECK_IN").billType()).isEqualTo("bonus");
        assertThat(row(" daily_check_in ").billType()).isEqualTo("bonus");

        assertThat(row("COMPUTE_TASK_REWARD_REVERSAL").billType()).isEqualTo("refund");
        assertThat(row("COMPUTE_TASK_REWARD_BONUS").billType()).isEqualTo("bonus");
        assertThat(row("DAILY_CHECK_IN_REVERSAL").billType()).isEqualTo("refund");
        assertThat(row("ORDER_PURCHASE").billType()).isEqualTo("earning");
        assertThat(row(null).billType()).isEqualTo("earning");
        assertThat(row("COMPUTE_TASK_REWARD").subtype()).isEqualTo("compute_task_reward");
        assertThat(row("DAILY_CHECK_IN").subtype()).isEqualTo("daily_check_in");
    }

    private static TreasuryLedgerBillView row(String bizType) {
        return row(bizType, "IN");
    }

    @Test
    void purchaseRequiresTheExactKnownBusinessTypeAndDebitDirection() {
        for (String direction : List.of("OUT", "DEBIT", " out ", " debit ")) {
            TreasuryLedgerBillView purchase = row(" order_purchase ", direction);
            assertThat(purchase.billType()).isEqualTo("purchase");
            assertThat(purchase.bizType()).isEqualTo(" order_purchase ");
            assertThat(purchase.direction()).isEqualTo(direction);
            assertThat(purchase.subtype()).isEqualTo("order_purchase");
            assertThat(purchase.amount()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(purchase.balanceAfter()).isEqualByComparingTo(BigDecimal.ONE);
        }
        for (String direction : List.of("IN", "CREDIT", "UNKNOWN", "")) {
            assertThat(row("ORDER_PURCHASE", direction).billType()).isEqualTo("earning");
        }
        assertThat(row("ORDER_PURCHASE", null).billType()).isEqualTo("earning");
        for (String type : List.of("GENESIS_PURCHASE", "UNKNOWN_PURCHASE", "ORDER", "ORDER_PURCHASE_EXTRA")) {
            assertThat(row(type, "OUT").billType()).isEqualTo("earning");
        }
        assertThat(row("ORDER_PURCHASE_REWARD", "OUT").billType()).isEqualTo("bonus");
        assertThat(row("ORDER_PURCHASE_REFUND", "OUT").billType()).isEqualTo("refund");
        assertThat(row("ORDER_PURCHASE_REVERSAL", "OUT").billType()).isEqualTo("refund");
        assertThat(row("COMPUTE_TASK_REWARD", "OUT").billType()).isEqualTo("earning");
    }

    private static TreasuryLedgerBillView row(String bizType, String direction) {
        return new TreasuryLedgerBillView(
                1L, 1L, "U00000001", "user", "B-1", bizType,
                "USDT", direction, BigDecimal.ONE, BigDecimal.ONE, "POSTED", "remark",
                LocalDateTime.MIN, LocalDateTime.MIN);
    }
}
