package ffdd.opsconsole.treasury.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.SupportCommissionCaptureFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Production mapper SQL against connection-local InnoDB fixtures, never business tables. */
@EnabledIfEnvironmentVariable(named="CREGIS_EXCEPTION_MYSQL_IT",matches="true")
class Bep20WithdrawalReserveMySqlTest {
    SingleConnectionDataSource data;
    JdbcTemplate jdbc;
    MybatisTreasuryLedgerRepository repository;
    TransactionTemplate tx;
    final BigDecimal amount=new BigDecimal("100.000000");
    final LocalDateTime now=LocalDateTime.of(2026,10,22,12,0);
    @BeforeEach void setup() {
        String url=System.getenv("CREGIS_EXCEPTION_MYSQL_JDBC");
        assertThat(url).matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/cregis_exception_test(?:\\?.*)?");
        data=new SingleConnectionDataSource(url,System.getenv("CREGIS_EXCEPTION_MYSQL_USER"),System.getenv("CREGIS_EXCEPTION_MYSQL_PASSWORD"),true);
        jdbc=new JdbcTemplate(data);
        jdbc.execute("CREATE TEMPORARY TABLE nx_withdrawal_order(withdrawal_no VARCHAR(64) PRIMARY KEY,user_id BIGINT,chain VARCHAR(24),amount DECIMAL(18,6),status VARCHAR(32),chain_tx_hash VARCHAR(66),d5_payout_source VARCHAR(24),is_deleted INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TEMPORARY TABLE nx_treasury_reserve_ledger(reserve_no VARCHAR(64) PRIMARY KEY,voucher_no VARCHAR(96) UNIQUE,direction VARCHAR(8),amount_usd DECIMAL(18,6),reason VARCHAR(500),operator VARCHAR(64),idempotency_key VARCHAR(128),status VARCHAR(32),created_at DATETIME,updated_at DATETIME,is_deleted INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TEMPORARY TABLE nx_user_wallet(user_id BIGINT PRIMARY KEY,usdt_available DECIMAL(18,6),nex_available DECIMAL(18,6),pending_withdraw DECIMAL(18,6),version INT,updated_at DATETIME,is_deleted INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TEMPORARY TABLE nx_wallet_ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,biz_no VARCHAR(96),biz_type VARCHAR(64),asset VARCHAR(8),direction VARCHAR(8),amount DECIMAL(18,6),balance_after DECIMAL(18,6),status VARCHAR(32),remark VARCHAR(500),created_at DATETIME,updated_at DATETIME,is_deleted INT,UNIQUE KEY uk_wallet_ledger_biz(biz_no,asset,direction)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO nx_withdrawal_order VALUES('WD1',7,'USDT-BEP20',100,'FAILED',NULL,'provider',0)");
        jdbc.update("INSERT INTO nx_treasury_reserve_ledger VALUES('RSV-WD-WD1','WD-WD1','OUT',100,'approved','checker','approval-1','CONFIRMED',NOW(),NOW(),0)");
        jdbc.update("INSERT INTO nx_user_wallet VALUES(7,900,0,200,0,NOW(),0)");
        var configuration=new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("reserve-isolated",new SpringManagedTransactionFactory(),data));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(TreasuryLedgerMapper.class);
        var mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(TreasuryLedgerMapper.class);
        repository=new MybatisTreasuryLedgerRepository(mapper,mock(EventOutboxService.class),mock(SupportCommissionCaptureFacade.class));
        tx=new TransactionTemplate(new DataSourceTransactionManager(data));
    }
    @AfterEach void close(){if(data!=null)data.destroy();}
    void reverse(){repository.reverseBep20WithdrawalReserve("WD1",amount,now);}
    int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM nx_treasury_reserve_ledger",Integer.class);}
    @Test void actualSqlWritesOneBoundCompensationAndRetainsOriginalOut() {
        tx.executeWithoutResult(s->reverse());tx.executeWithoutResult(s->reverse());
        assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM nx_treasury_reserve_ledger WHERE direction='IN'",String.class)).isEqualTo("reverse:WD-WD1");
        assertThat(jdbc.queryForObject("SELECT SUM(CASE WHEN direction='IN' THEN amount_usd ELSE -amount_usd END) FROM nx_treasury_reserve_ledger",BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM nx_treasury_reserve_ledger WHERE direction='OUT'",String.class)).isEqualTo("approval-1");
    }
    @Test void auditFailureRollsBackOrderWalletAndReserveTogether() {
        jdbc.update("UPDATE nx_withdrawal_order SET status='SENT'");
        assertThatThrownBy(()->tx.executeWithoutResult(s->{
            jdbc.update("UPDATE nx_withdrawal_order SET status='FAILED'");reverse();
            jdbc.update("UPDATE nx_user_wallet SET usdt_available=usdt_available+100,pending_withdraw=pending_withdraw-100");
            throw new IllegalStateException("audit unavailable");
        })).hasMessage("audit unavailable");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_withdrawal_order",String.class)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet",BigDecimal.class)).isEqualByComparingTo("900");
        assertThat(jdbc.queryForObject("SELECT pending_withdraw FROM nx_user_wallet",BigDecimal.class)).isEqualByComparingTo("200");
    }
    @Test void missingOrConflictingOriginalCannotCreateMoney() {
        jdbc.update("DELETE FROM nx_treasury_reserve_ledger");
        assertThatThrownBy(()->tx.executeWithoutResult(s->reverse())).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
        jdbc.update("INSERT INTO nx_treasury_reserve_ledger VALUES('RSV-WD-WD1','WD-WD1','OUT',99,'wrong','checker','approval-1','CONFIRMED',NOW(),NOW(),0)");
        assertThatThrownBy(()->tx.executeWithoutResult(s->reverse())).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isEqualTo(1);
    }
    @Test void unknownOrConfirmedOrdersCannotReverseReserve() {
        for(String status:java.util.List.of("TX_ORPHANED","SENT","PROCESSING","CONFIRMED")) {
            jdbc.update("UPDATE nx_withdrawal_order SET status=?",status);
            assertThatThrownBy(()->tx.executeWithoutResult(s->reverse())).hasMessage("BEP20_RESERVE_RETURN_STATE_INVALID");
        }
        jdbc.update("UPDATE nx_withdrawal_order SET status='FAILED',chain_tx_hash=?","0x"+"a".repeat(64));
        assertThatThrownBy(()->tx.executeWithoutResult(s->reverse())).hasMessage("BEP20_RESERVE_RETURN_STATE_INVALID");
        assertThat(count()).isEqualTo(1);
    }
    @Test void manualRefundReturnsGrossAndReserveOnceWithoutTakingAnotherOrdersPending() {
        jdbc.update("UPDATE nx_withdrawal_order SET status='REFUNDED',d5_payout_source=NULL");
        tx.executeWithoutResult(s->repository.refundWithdrawal("WD1",7L,amount,"USDT","manual return"));
        assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet",BigDecimal.class)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT pending_withdraw FROM nx_user_wallet",BigDecimal.class)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->tx.executeWithoutResult(s->repository.refundWithdrawal("WD1",7L,amount,"USDT","manual return")))
                .hasMessage("WITHDRAWAL_ALREADY_REFUNDED");
        assertThat(jdbc.queryForObject("SELECT pending_withdraw FROM nx_user_wallet",BigDecimal.class)).isEqualByComparingTo("100");
        assertThat(count()).isEqualTo(2);
    }
    @Test void actualManualWalletAndLedgerAndReserveAllRollBackOnRequiredAuditFailure() {
        jdbc.update("UPDATE nx_withdrawal_order SET status='TX_FAILED',d5_payout_source=NULL");
        assertThatThrownBy(()->tx.executeWithoutResult(s->{
            jdbc.update("UPDATE nx_withdrawal_order SET status='REFUNDED'");
            repository.refundWithdrawal("WD1",7L,amount,"USDT","manual return");
            throw new IllegalStateException("audit unavailable");
        })).hasMessage("audit unavailable");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_withdrawal_order",String.class)).isEqualTo("TX_FAILED");
        assertThat(jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet",BigDecimal.class)).isEqualByComparingTo("900");
        assertThat(jdbc.queryForObject("SELECT pending_withdraw FROM nx_user_wallet",BigDecimal.class)).isEqualByComparingTo("200");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger",Integer.class)).isZero();
    }
}
