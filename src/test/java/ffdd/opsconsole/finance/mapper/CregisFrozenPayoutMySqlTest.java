package ffdd.opsconsole.finance.mapper;

import java.sql.*;
import java.time.LocalDateTime;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

/** Actual mapper SQL over connection-local temporary tables; no persistent wallet/provider writes. */
@EnabledIfEnvironmentVariable(named="CREGIS_EXCEPTION_MYSQL_IT",matches="true")
class CregisFrozenPayoutMySqlTest {
    Connection connection;SqlSession session;WithdrawalPayoutMapper mapper;
    final LocalDateTime now=LocalDateTime.of(2026,10,22,0,0);
    @BeforeEach void setup()throws Exception {
        String url=System.getenv("CREGIS_EXCEPTION_MYSQL_JDBC");assertThat(url).matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/cregis_exception_test(?:\\?.*)?");
        connection=DriverManager.getConnection(url,System.getenv("CREGIS_EXCEPTION_MYSQL_USER"),System.getenv("CREGIS_EXCEPTION_MYSQL_PASSWORD"));
        execute("CREATE TEMPORARY TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(24),sandbox INT,is_deleted INT)");execute("INSERT INTO nx_user VALUES(7,'FROZEN',0,0)");
        execute("CREATE TEMPORARY TABLE nx_withdrawal_order(id BIGINT PRIMARY KEY,withdrawal_no VARCHAR(96),user_id BIGINT,chain VARCHAR(32),target_address VARCHAR(64),amount DECIMAL(18,6),d2_net_receive DECIMAL(18,6),d2_nex_burned DECIMAL(18,6),status VARCHAR(32),d5_payout_due_at DATETIME,d5_provider_cid BIGINT,d5_provider_idempotency_key VARCHAR(96),d5_payout_source VARCHAR(24),chain_broadcast_attempts INT,c2_frozen_by_user_status INT,c2_previous_status VARCHAR(32),completed_at DATETIME,is_deleted INT,d5_payout_lease_until DATETIME,chain_submitted_at DATETIME,next_broadcast_at DATETIME,last_broadcast_error VARCHAR(255),failure_reason VARCHAR(255),failed_at DATETIME,chain_tx_hash VARCHAR(66),created_at DATETIME,updated_at DATETIME)");
        var configuration=new Configuration(new Environment("temporary-frozen-payout",new JdbcTransactionFactory(),new SingleConnectionDataSource(connection,true)));configuration.addMapper(WithdrawalPayoutMapper.class);session=new SqlSessionFactoryBuilder().build(configuration).openSession(true);mapper=session.getMapper(WithdrawalPayoutMapper.class);
    }
    @AfterEach void close()throws Exception {if(session!=null)session.close();if(connection!=null)connection.close();}
    @Test void sentFrozenProviderIsObservedAndSettlesWithoutResending()throws Exception {
        seed("SENT",91L);assertThat(mapper.payout("WD-test").status()).isEqualTo("SENT");assertThat(mapper.incompleteProviderSubmissions("",50)).hasSize(1);
        assertThat(mapper.claimNewPayout("WD-test",now,now.plusMinutes(2))).isZero();assertThat(mapper.markProviderIntent("WD-test",now)).isZero();
        assertThat(mapper.terminalOrder("WD-test",91,"CONFIRMED","0x"+"a".repeat(64),null,now)).isEqualTo(1);assertThat(mapper.payout("WD-test").status()).isEqualTo("CONFIRMED");assertThat(mapper.incompleteProviderSubmissions("",50)).isEmpty();
    }
    @Test void freezeDuringExternalRequestRetainsReturnedCidAndUnknownRecoveryNeverClaimsNew()throws Exception {
        seed("PROCESSING",null);assertThat(mapper.markSubmitted("WD-test",91,"provider",now)).isEqualTo(1);assertThat(mapper.payout("WD-test").providerCid()).isEqualTo(91L);
        execute("UPDATE nx_withdrawal_order SET status='FROZEN',c2_previous_status='TX_ORPHANED',c2_frozen_by_user_status=1,d5_provider_cid=NULL");assertThat(mapper.recoverOrphanToSent("WD-test",91,"original-key",now)).isEqualTo(1);assertThat(mapper.claimNewPayout("WD-test",now,now.plusMinutes(2))).isZero();
        execute("UPDATE nx_withdrawal_order SET status='PROCESSING',d5_provider_cid=NULL,d5_payout_source=NULL");assertThat(mapper.markProviderIntent("WD-test",now)).isZero();
    }
    void seed(String previous,Long cid)throws Exception {try(var ps=connection.prepareStatement("INSERT INTO nx_withdrawal_order(id,withdrawal_no,user_id,chain,target_address,amount,d2_net_receive,d2_nex_burned,status,d5_provider_cid,d5_provider_idempotency_key,d5_payout_source,chain_broadcast_attempts,c2_frozen_by_user_status,c2_previous_status,is_deleted) VALUES(1,'WD-test',7,'USDT-BEP20','address',100,99,0,'FROZEN',?,'original-key','provider',1,1,?,0)")){ps.setObject(1,cid);ps.setString(2,previous);ps.executeUpdate();}}
    void execute(String sql)throws Exception {try(var statement=connection.createStatement()){statement.execute(sql);}}
}
