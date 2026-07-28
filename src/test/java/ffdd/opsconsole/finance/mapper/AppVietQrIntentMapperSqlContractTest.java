package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class AppVietQrIntentMapperSqlContractTest {

    @Test
    void expandedSelectAnnotationsKeepWhitespaceBetweenSelectAndSharedColumns() {
        Arrays.stream(AppVietQrIntentMapper.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Select.class))
                .filter(java.util.Objects::nonNull)
                .map(select -> String.join("\n", select.value()))
                .filter(sql -> sql.contains("intent_no AS intentNo"))
                .forEach(sql -> assertThat(sql).startsWith("SELECT "));
    }

    @Test
    void userReadsAreAlwaysScopedByAuthenticatedUserId() throws Exception {
        Method method = AppVietQrIntentMapper.class.getMethod(
                "findIntentForUser", Long.class, String.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("user_id = #{userId}")
                .contains("intent_no = #{intentNo}")
                .doesNotContain("${");
    }

    @Test
    void idempotencyReplayLookupUsesACurrentLockingRead() throws Exception {
        Method method = AppVietQrIntentMapper.class.getMethod(
                "findIntentByCreateKey", Long.class, String.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("create_idempotency_key = #{idempotencyKey}")
                .contains("FOR UPDATE");
    }

    @Test
    void createLocksAnActiveCanonicalUserBeforeCountingIntents() throws Exception {
        Method method = AppVietQrIntentMapper.class.getMethod(
                "lockActiveUserForIntentCreation", Long.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("FROM nx_user")
                .contains("id = #{userId}")
                .contains("status = 'ACTIVE'")
                .contains("FOR UPDATE");
    }

    @Test
    void cancelRequiresAwaitingStateAndExpectedVersion() throws Exception {
        Method method = AppVietQrIntentMapper.class.getMethod(
                "cancelIntent", Long.class, String.class, Long.class, String.class, String.class);
        String sql = String.join("\n", method.getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("status = 'AWAITING_PAYMENT'")
                .contains("version = #{expectedVersion}")
                .contains("user_id = #{userId}")
                .contains("version = version + 1");
    }

    @Test
    void insertPersistsCanonicalQuoteAccountMemoAndIdempotency() throws Exception {
        Method method = AppVietQrIntentMapper.class.getMethod(
                "insertIntent",
                String.class, Long.class, String.class, String.class,
                java.math.BigDecimal.class, java.math.BigDecimal.class,
                java.math.BigDecimal.class, Long.class, Long.class,
                String.class, java.time.LocalDateTime.class);
        String sql = String.join("\n", method.getAnnotation(Insert.class).value());

        assertThat(sql)
                .contains("create_idempotency_key")
                .contains("create_request_hash")
                .contains("locked_fx_rate_vnd_per_usdt")
                .contains("fx_quote_version")
                .contains("bank_account_id")
                .contains("memo_code")
                .contains("'AWAITING_PAYMENT'");
    }

    @Test
    void appIntentCreatesAndClosesThePcVisibleInFlightProjection() throws Exception {
        Method create = AppVietQrIntentMapper.class.getMethod(
                "ensureInFlightReconciliation", String.class);
        String createSql = String.join("\n", create.getAnnotation(Insert.class).value());
        Method close = AppVietQrIntentMapper.class.getMethod(
                "closeInFlightReconciliation", String.class, String.class);
        String closeSql = String.join("\n", close.getAnnotation(Update.class).value());

        assertThat(createSql)
                .contains("nx_vietqr_reconciliation")
                .contains("'INFLIGHT'")
                .contains("CONCAT('APP-', intent_no)")
                .contains("status = 'AWAITING_PAYMENT'")
                .contains("expires_at > NOW()");
        assertThat(closeSql)
                .contains("reconciliation_no = CONCAT('APP-', #{intentNo})")
                .contains("is_deleted = 1")
                .contains("view_type = 'INFLIGHT'")
                .contains("status = 'OPEN'");
    }

    @Test
    void pcOverviewCanExpireStaleIntentsWithoutWaitingForTheAppToPoll() throws Exception {
        Method expire = AppVietQrIntentMapper.class.getMethod("expireAllIntents");
        String expireSql = String.join("\n", expire.getAnnotation(Update.class).value());
        Method close = AppVietQrIntentMapper.class.getMethod(
                "closeAllInactiveInFlightReconciliations");
        String closeSql = String.join("\n", close.getAnnotation(Update.class).value());

        assertThat(expireSql)
                .contains("status = 'EXPIRED'")
                .contains("status = 'AWAITING_PAYMENT'")
                .contains("expires_at <= NOW()")
                .doesNotContain("RECEIPT_REVIEW");
        assertThat(closeSql)
                .contains("'RECEIPT_REVIEW','MISMATCH_REVIEW','LATE_REVIEW'")
                .contains("'EXPIRED','CANCELLED','CREDITED','RETURNED'")
                .contains("r.view_type = 'INFLIGHT'")
                .contains("r.is_deleted = 0");
    }

    @Test
    void historicalIntentReadsSurviveBankAccountSoftDeletion() throws Exception {
        Method read = AppVietQrIntentMapper.class.getMethod(
                "findIntentForUser", Long.class, String.class);
        String sql = String.join("\n", read.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("LEFT JOIN nx_vietqr_bank_account")
                .doesNotContain("b.is_deleted = 0");
        assertThat(AppVietQrIntentMapper.INTENT_COLUMNS)
                .contains("account_number_last4 AS accountNumberLast4")
                .contains("b.status AS bankAccountStatus");
    }

    @Test
    void accountCapacityUsesTheCurrentBusinessDate() throws Exception {
        Method read = AppVietQrIntentMapper.class.getMethod(
                "listActiveBankAccountsForUpdate");
        String sql = String.join("\n", read.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("received_business_date = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))")
                .contains("THEN received_today_vnd ELSE 0")
                .doesNotContain("CURRENT_DATE")
                .contains("FOR UPDATE");
    }

    @Test
    void fusedAccountCancelsOtherAwaitingIntentsAndTheirPcProjections() throws Exception {
        String cancelSql = String.join("\n", AppVietQrIntentMapper.class.getMethod(
                "cancelAwaitingIntentsForFusedAccount", Long.class, String.class)
                .getAnnotation(Update.class).value());
        String closeSql = String.join("\n", AppVietQrIntentMapper.class.getMethod(
                "closeCancelledInFlightReconciliationsForFusedAccount",
                Long.class, String.class)
                .getAnnotation(Update.class).value());

        assertThat(cancelSql)
                .contains("bank_account_id = #{bankAccountId}")
                .contains("status = 'AWAITING_PAYMENT'")
                .contains("intent_no <> #{excludedIntentNo}")
                .contains("status = 'CANCELLED'");
        assertThat(closeSql)
                .contains("i.bank_account_id = #{bankAccountId}")
                .contains("i.status = 'CANCELLED'")
                .contains("r.view_type = 'INFLIGHT'")
                .contains("r.is_deleted = 1");
    }
}
