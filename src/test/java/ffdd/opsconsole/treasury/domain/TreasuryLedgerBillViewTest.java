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
    void exposesOnlyTheSevenCanonicalBillTypesAndPreservesRawSubtype() {
        List<String> rawTypes = List.of(
                "EXCHANGE", "CARD_TOPUP", "WITHDRAWAL", "ADJUSTMENT",
                "COMMISSION", "CHARGEBACK_RECOVERY", "TRIAL_BONUS");
        Set<String> canonical = new LinkedHashSet<>();
        for (int index = 0; index < rawTypes.size(); index++) {
            TreasuryLedgerBillView row = new TreasuryLedgerBillView(
                    (long) index + 1, 1L, "U00000001", "user", "B-" + index, rawTypes.get(index),
                    "USDT", "IN", BigDecimal.ONE, BigDecimal.ONE, "POSTED", "remark",
                    LocalDateTime.MIN, LocalDateTime.MIN);
            canonical.add(row.billType());
            assertThat(row.subtype()).isEqualTo(rawTypes.get(index).toLowerCase());
        }

        assertThat(canonical).containsExactly(
                "swap", "topup", "withdraw", "earning", "commission", "refund", "bonus");
    }

    @Test
    void classifiesRefundsBeforeWithdrawalsAndRewardsAsBonus() {
        TreasuryLedgerBillView refund = row("WITHDRAW_FEE_OFFSET_REFUND");
        TreasuryLedgerBillView referralReward = row("REFERRAL_REWARD");
        TreasuryLedgerBillView questReward = row("QUEST_REWARD");

        assertThat(refund.billType()).isEqualTo("refund");
        assertThat(referralReward.billType()).isEqualTo("bonus");
        assertThat(questReward.billType()).isEqualTo("bonus");
    }

    private static TreasuryLedgerBillView row(String bizType) {
        return new TreasuryLedgerBillView(
                1L, 1L, "U00000001", "user", "B-1", bizType,
                "USDT", "IN", BigDecimal.ONE, BigDecimal.ONE, "POSTED", "remark",
                LocalDateTime.MIN, LocalDateTime.MIN);
    }
}
