package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class VietnamPaymentMapperSqlContractTest {

    @Test
    void creditMovesSuspenseToWalletAndLifetimeDepositUsingTheWalletCas() throws Exception {
        Method method = VietnamPaymentMapper.class.getMethod(
                "creditUsdtWallet", Long.class, java.math.BigDecimal.class, Long.class);
        String sql = String.join("\n", method.getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("usdt_available = usdt_available + #{amount}")
                .contains("cumulative_deposit_usdt = cumulative_deposit_usdt + #{amount}")
                .contains("version = #{expectedVersion}")
                .doesNotContain("RETURN");
    }

    @Test
    void reconciliationTerminalWriteIsVersionedAndRejectsAlreadyTerminalRows() throws Exception {
        Method method = VietnamPaymentMapper.class.getMethod(
                "completeVietQrReconciliation", Long.class, Long.class, String.class, String.class,
                Long.class, String.class, java.math.BigDecimal.class, String.class);
        String sql = String.join("\n", method.getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("version = #{expectedVersion}")
                .contains("status = 'OPEN'")
                .contains("version = version + 1");
    }

    @Test
    void bankAccountStoresCiphertextAndNeverReturnsTheFullNumber() throws Exception {
        String insertSql = String.join("\n", VietnamPaymentMapper.class.getMethod(
                "insertVietQrBankAccount", String.class, String.class, String.class, String.class,
                String.class, String.class, java.math.BigDecimal.class)
                .getAnnotation(Insert.class).value());
        String listSql = String.join("\n", VietnamPaymentMapper.class.getMethod("listVietQrBankAccounts")
                .getAnnotation(Select.class).value());

        assertThat(insertSql).contains("account_number_encrypted", "account_number_hash", "account_number_last4");
        assertThat(listSql).contains("account_number_last4 AS accountLast4")
                .doesNotContain("account_number_encrypted", "account_number_hash");
    }

    @Test
    void receiptRegistrationRecordsPhysicalCashAndFusesRoutingWithoutBlockingSettlement() throws Exception {
        String sql = String.join("\n", VietnamPaymentMapper.class.getMethod(
                "addVietQrBankReceivedToday", Long.class, java.math.BigDecimal.class,
                java.time.LocalDate.class).getAnnotation(Update.class).value());
        String pendingSql = String.join("\n", VietnamPaymentMapper.class.getMethod(
                "sumPendingUnverifiedDepositUsdt").getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("received_today_vnd + #{receivedVnd}")
                .contains("DAILY_CAP_EXCEEDED_AFTER_RECEIPT")
                .contains("THEN 'FUSED'")
                .contains("DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))")
                .doesNotContain("CURRENT_DATE")
                .doesNotContain("<= daily_cap_vnd");
        assertThat(pendingSql)
                .contains("status = 'OPEN'")
                .contains("received_vnd > 0")
                .doesNotContain("view_type IN");
    }

    @Test
    void receiptRowsPersistImmutableTimingAndIntentTransitionOwnership() throws Exception {
        String insertSql = String.join("\n", VietnamPaymentMapper.class.getMethod(
                "insertVietQrReceipt",
                String.class, String.class, Long.class, Long.class, String.class,
                java.math.BigDecimal.class, java.math.BigDecimal.class, java.math.BigDecimal.class,
                String.class, String.class, java.time.LocalDateTime.class,
                java.time.LocalDateTime.class, boolean.class)
                .getAnnotation(Insert.class).value());
        String lockSql = String.join("\n", VietnamPaymentMapper.class.getMethod(
                "findVietQrReconciliationForUpdate", Long.class)
                .getAnnotation(Select.class).value());

        assertThat(insertSql)
                .contains("received_at")
                .contains("intent_transition_required");
        assertThat(lockSql)
                .contains("received_at AS receivedAt")
                .contains("intent_transition_required AS intentTransitionRequired")
                .contains("FOR UPDATE");
    }
}
