package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real-MySQL proof for the F2 order/user/layer/currency replay fence. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class F2NetworkCommissionReplayMySqlAcceptanceTest {
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private String orderNo;
    private long userId;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                System.getenv("NEXION_TEST_DB_URL"),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        orderNo = "F2-RACE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        userId = Long.parseLong(System.getenv("NEXION_TEST_USER_ID"));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM nx_wallet_ledger WHERE remark LIKE ?", "%" + orderNo + "%");
        jdbc.update("DELETE FROM nx_commission_event WHERE order_no=?", orderNo);
    }

    @Test
    void twoConcurrentReplaysCreateOneEventAndLedgerPerCurrency() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return settleReplay(); });
            var second = pool.submit(() -> { start.await(); return settleReplay(); });
            start.countDown();
            List<Integer> inserted = List.of(
                    first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(inserted.stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM nx_commission_event WHERE order_no=?", Long.class, orderNo))
                .isEqualTo(2L);
        assertThat(jdbc.queryForList(
                "SELECT currency FROM nx_commission_event WHERE order_no=? ORDER BY currency", String.class, orderNo))
                .containsExactly("NEX", "USDT");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM nx_wallet_ledger WHERE remark LIKE ?", Long.class, "%" + orderNo + "%"))
                .isEqualTo(2L);
    }

    private int settleReplay() {
        return transaction.execute(status -> {
            assertThat(jdbc.queryForObject(
                    "SELECT id FROM nx_user WHERE id=? AND status='ACTIVE' AND is_deleted=0 FOR UPDATE",
                    Long.class, userId)).isEqualTo(userId);
            return post("USDT", new BigDecimal("10.000000"), BigDecimal.ZERO)
                    + post("NEX", BigDecimal.ZERO, new BigDecimal("500.000000"));
        });
    }

    private int post(String currency, BigDecimal amountUsdt, BigDecimal amountNex) {
        int inserted = jdbc.update("""
                INSERT IGNORE INTO nx_commission_event
                  (user_id,commission_type,source_user_id,layer_no,order_no,order_amount_usd,
                   amount_usdt,amount_nex,currency,status,version,remark,is_deleted)
                VALUES (?,'network',?,1,?,100,?,?,?,'COOLING',0,?,0)
                """, userId, userId, orderNo, amountUsdt, amountNex, currency, orderNo);
        if (inserted == 0) return 0;
        long eventId = jdbc.queryForObject("""
                SELECT id FROM nx_commission_event
                 WHERE commission_type='network' AND order_no=? AND user_id=? AND layer_no=1 AND currency=?
                """, Long.class, orderNo, userId, currency);
        BigDecimal amount = "USDT".equals(currency) ? amountUsdt : amountNex;
        assertThat(jdbc.update("""
                INSERT INTO nx_wallet_ledger
                  (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,is_deleted)
                VALUES (?,?,'TEAM_COMMISSION',?,'IN',?,0,'PENDING',?,0)
                """, userId, "F2-RACE-" + eventId, currency, amount, orderNo)).isEqualTo(1);
        return 1;
    }
}
