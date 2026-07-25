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
}
