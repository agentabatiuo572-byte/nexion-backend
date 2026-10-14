package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** SQL_ONLY: native column types and real wallet boundaries; not a payment or AUTH birth proof. */
@EnabledIfEnvironmentVariable(named="SUPPORT_FUND_JOURNAL_MYSQL_IT", matches="true")
class SupportFundNativeFlagsMySqlTest {
    static final int EXPECTED_TESTS = 3;
    private static final AtomicLong CUSTOMERS = new AtomicLong(System.currentTimeMillis() * 1000 + 50000);
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private SupportFundJournalMapper mapper;
    private SupportPaymentSourceService payments;
    private SupportWithdrawalFundSourceService withdrawals;

    @BeforeEach void useExactGuardedSqlFixture() throws Exception {
        var fixture = new SupportFundJournalBindingMySqlTest();
        fixture.exclusiveFixtureAndRealSpringMyBatis();
        dataSource = field(fixture, "dataSource", DataSource.class);
        jdbc = field(fixture, "jdbc", JdbcTemplate.class);
        transaction = field(fixture, "transaction", TransactionTemplate.class);
        mapper = field(fixture, "mapper", SupportFundJournalMapper.class);
        var binder = field(fixture, "binder", SupportFundJournalFacade.class);
        payments = new SupportPaymentSourceService(null, null, dataSource, new ObjectMapper(), null, binder, mapper, null);
        withdrawals = new SupportWithdrawalFundSourceService(binder, mapper, null, new ObjectMapper());
    }

    @Test void nativeFalseWalletAndNumericRawJournalReachActualSourceBoundaries() {
        long customer = newWallet();
        long writerConnection = transaction.execute(status -> {
            long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            var wallet = paymentWallet(customer);
            assertThat(wallet.get("sandbox")).isEqualTo(Boolean.FALSE);
            assertThat(wallet.get("isDeleted")).isInstanceOf(Number.class);
            assertThat(wallet.get("id")).isInstanceOf(Number.class);
            assertThat(wallet.get("userId")).isInstanceOf(Number.class);
            assertThat(wallet.get("usdtAvailable")).isInstanceOf(BigDecimal.class);
            assertThat(SupportPaymentFactService.flag(wallet, "sandbox")).isZero();
            var raw = mapper.journal(wallet.get("incarnation").toString(), -1, 0);
            assertThat(raw).hasSize(1);
            assertThat(raw.get(0).get("newSandbox")).isInstanceOf(Number.class);
            assertThat(SupportPaymentFactService.flag(raw.get(0), "newSandbox")).isZero();
            assertThat(binding(withdrawals.begin(customer))).isNotNull();
            assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(connectionId);
            System.out.println("NATIVE_FLAGS false: wallet=Boolean, journal=Number, id=Number, money=BigDecimal; actual payment/withdrawal wallet boundaries accepted");
            return connectionId;
        });
        readBack(customer, writerConnection, false, 0);
    }

    @Test void nativeTrueSandboxRejectsProductionPaymentAndSkipsProductionWithdrawalBinding() {
        long customer = newWallet();
        long writerConnection = transaction.execute(status -> {
            jdbc.update("UPDATE nx_user_wallet SET sandbox=1,version=version+1 WHERE user_id=?", customer);
            assertThat(mapper.lockWallet(customer).get(0).get("sandbox")).isEqualTo(Boolean.TRUE);
            assertThatThrownBy(() -> paymentWallet(customer)).isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYMENT_FUND_WALLET_IDENTITY_INVALID");
            assertThat(binding(withdrawals.begin(customer))).isNull();
            return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
        });
        readBack(customer, writerConnection, true, 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_party WHERE customer_id=?", Long.class, customer)).isZero();
        System.out.println("NATIVE_FLAGS true sandbox: production payment rejected; production fund binding absent after commit and new-connection readback");
    }

    @Test void nativeDeletedWalletIsRejectedWithoutChangingCashOrInventingReceipts() {
        long customer = newWallet();
        long writerConnection = transaction.execute(status -> {
            jdbc.update("UPDATE nx_user_wallet SET is_deleted=1,version=version+1 WHERE user_id=?", customer);
            assertThatThrownBy(() -> paymentWallet(customer)).isInstanceOf(IllegalStateException.class)
                .hasMessage("PAYMENT_FUND_WALLET_IDENTITY_INVALID");
            assertThatThrownBy(() -> withdrawals.begin(customer)).isInstanceOf(IllegalStateException.class)
                .hasMessage("WITHDRAWAL_FUND_WALLET_IDENTITY_MISMATCH");
            return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
        });
        readBack(customer, writerConnection, false, 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_party WHERE customer_id=?", Long.class, customer)).isZero();
        System.out.println("NATIVE_FLAGS deleted: actual wallet boundaries rejected; zero cash and no formal receipts persisted");
    }

    private long newWallet() {
        long customer = CUSTOMERS.incrementAndGet();
        assertThat(jdbc.update("INSERT INTO nx_user_wallet(user_id) VALUES(?)", customer)).isEqualTo(1);
        return customer;
    }

    private void readBack(long customer, long writerConnection, boolean sandbox, int deleted) {
        new JdbcTemplate(dataSource).execute((ConnectionCallback<Void>) connection -> {
            assertThat(connection.getAutoCommit()).isTrue();
            try (var query = connection.prepareStatement("SELECT CONNECTION_ID() connectionId,sandbox,is_deleted,usdt_available,nex_available,pending_withdraw FROM nx_user_wallet WHERE user_id=?")) {
                query.setLong(1, customer);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("connectionId")).isNotEqualTo(writerConnection);
                    assertThat(rows.getObject("sandbox")).isEqualTo(sandbox);
                    assertThat(((Number) rows.getObject("is_deleted")).intValue()).isEqualTo(deleted);
                    for (String bucket : new String[]{"usdt_available", "nex_available", "pending_withdraw"})
                        assertThat(rows.getBigDecimal(bucket)).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(rows.next()).isFalse();
                }
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> paymentWallet(long customer) {
        try {
            var method = SupportPaymentSourceService.class.getDeclaredMethod("paymentWallet", long.class);
            method.setAccessible(true);
            return (Map<String,Object>) method.invoke(payments, customer);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException failure) throw failure;
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private static Object binding(SupportWithdrawalFundSourceService.Before before) {
        try {
            var method = before.getClass().getDeclaredMethod("binding");
            method.setAccessible(true);
            return method.invoke(before);
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private static <T> T field(Object target, String name, Class<T> type) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
