package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class HdPayOrderMapperContractTest {
    @Test
    void pendingClaimDistinguishesANewInsertFromAnIdempotentDuplicate() throws Exception {
        Insert annotation = HdPayOrderMapper.class
                .getMethod("insertPending", String.class, BigDecimal.class, String.class)
                .getAnnotation(Insert.class);
        String sql = String.join(" ", Arrays.asList(annotation.value())).replaceAll("\\s+", " ");

        assertThat(sql).containsIgnoringCase("INSERT IGNORE INTO nx_hdpay_payin_order");
        assertThat(sql).doesNotContainIgnoringCase("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void submissionIsDurablyUnknownBeforeTheNetworkCallAndAllOutcomesConsumeThatState() throws Exception {
        String started = String.join(" ", HdPayOrderMapper.class
                .getMethod("authorizeSubmissionIfIntentPayable", String.class)
                .getAnnotation(Update.class).value()).replaceAll("\\s+", " ");
        String created = String.join(" ", HdPayOrderMapper.class
                .getMethod("markCreated", String.class, String.class)
                .getAnnotation(Update.class).value()).replaceAll("\\s+", " ");
        String rejected = String.join(" ", HdPayOrderMapper.class
                .getMethod("markRejected", String.class, String.class)
                .getAnnotation(Update.class).value()).replaceAll("\\s+", " ");

        assertThat(started).contains("submission_status = 'SUBMIT_UNKNOWN'")
                .contains("submission_status = 'PENDING'")
                .contains("HDPAY_SUBMISSION_STARTED")
                .contains("JOIN nx_vietqr_intent")
                .contains("i.status = 'AWAITING_PAYMENT'")
                .contains("i.expires_at > NOW()");
        assertThat(created).contains("submission_status = 'SUBMIT_UNKNOWN'");
        assertThat(rejected).contains("submission_status = 'SUBMIT_UNKNOWN'");
    }

    @Test
    void delayedPendingCallbackCannotRegressATerminalProviderObservation() throws Exception {
        Update annotation = HdPayOrderMapper.class
                .getMethod("updateCallbackObservation", String.class, String.class, Integer.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", Arrays.asList(annotation.value())).replaceAll("\\s+", " ");

        assertThat(sql)
                .containsIgnoringCase("provider_status = CASE")
                .containsIgnoringCase("provider_status IS NULL OR provider_status = 1")
                .containsIgnoringCase("ELSE provider_status END");
    }

    @Test
    void settlementLocksTheProviderOrderBeforeAnyFinancialMutation() throws Exception {
        Select annotation = HdPayOrderMapper.class
                .getMethod("findByMerchantOrderIdForUpdate", String.class)
                .getAnnotation(Select.class);
        String sql = String.join(" ", Arrays.asList(annotation.value())).replaceAll("\\s+", " ");

        assertThat(sql)
                .containsIgnoringCase("WHERE merchant_order_id = #{merchantOrderId}")
                .containsIgnoringCase("FOR UPDATE");
    }

    @Test
    void creditedSettlementUsesACompareAndSetGuard() throws Exception {
        Update annotation = HdPayOrderMapper.class
                .getMethod("markSettlementCredited", String.class, String.class, Integer.class,
                        BigDecimal.class, String.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", Arrays.asList(annotation.value())).replaceAll("\\s+", " ");

        assertThat(sql)
                .containsIgnoringCase("settlement_status = 'CREDITED'")
                .containsIgnoringCase("AND settlement_status = 'UNSETTLED'")
                .containsIgnoringCase("provider_order_id IS NULL OR provider_order_id = #{providerOrderId}");
    }

    @Test
    void depositNotificationIsIdempotentAtTheDatabaseBoundary() throws Exception {
        Insert annotation = HdPayOrderMapper.class
                .getMethod("insertDepositNotification", String.class, Long.class, BigDecimal.class)
                .getAnnotation(Insert.class);
        String sql = String.join(" ", Arrays.asList(annotation.value())).replaceAll("\\s+", " ");

        assertThat(sql)
                .containsIgnoringCase("INSERT IGNORE INTO nx_notification")
                .containsIgnoringCase("WHERE u.id = #{userId} AND u.is_deleted = 0");
    }

    @Test
    void staleQueryOwnerCannotOverwriteANewerClaimOrTerminalState() throws Exception {
        Update annotation = HdPayOrderMapper.class
                .getMethod("markCallbackProcessedOwned", String.class, String.class,
                        String.class, Integer.class, String.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", Arrays.asList(annotation.value())).replaceAll("\\s+", " ");

        assertThat(sql)
                .containsIgnoringCase("AND processing_status = 'PROCESSING'")
                .containsIgnoringCase("AND claim_token = #{claimToken}")
                .containsIgnoringCase("claim_token = NULL")
                .containsIgnoringCase("claimed_at = NULL");
    }
}
