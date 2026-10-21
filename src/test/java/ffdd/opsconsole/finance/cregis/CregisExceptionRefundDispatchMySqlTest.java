package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.finance.mapper.CregisExceptionRefundMapper;
import java.math.BigDecimal;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Real InnoDB tables in the one fixed refund-only test schema; never use business tables. */
@EnabledIfEnvironmentVariable(named="CREGIS_EXCEPTION_MYSQL_IT",matches="true")
@ResourceLock("cregis_exception_refund_test")
class CregisExceptionRefundDispatchMySqlTest {
    SingleConnectionDataSource data;JdbcTemplate jdbc;CregisExceptionRefundMapper mapper;
    static final String SCHEMA="cregis_exception_refund_test";
    boolean schemaOwned;
    final long project=4242;
    final String address1="0x"+"a".repeat(40),address2="0x"+"b".repeat(40),tx1="0x"+"c".repeat(64),tx2="0x"+"d".repeat(64);
    @BeforeEach void setup(){
        String url=System.getenv("CREGIS_EXCEPTION_MYSQL_JDBC");
        assertThat(url).matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/cregis_exception_test(?:\\?.*)?");
        data=new SingleConnectionDataSource(url,System.getenv("CREGIS_EXCEPTION_MYSQL_USER"),System.getenv("CREGIS_EXCEPTION_MYSQL_PASSWORD"),true);jdbc=new JdbcTemplate(data);
        jdbc.execute("CREATE DATABASE "+SCHEMA);schemaOwned=true;jdbc.execute("USE "+SCHEMA);
        jdbc.execute("CREATE TABLE nx_cregis_exception_refund(id BIGINT PRIMARY KEY,case_no VARCHAR(64),project_id BIGINT,txid VARCHAR(66),log_index INT,deposit_cid BIGINT,deposit_address VARCHAR(42),gross_amount DECIMAL(18,6),fee_amount DECIMAL(18,6),net_amount DECIMAL(18,6),state VARCHAR(32),maker_id BIGINT,checker_id BIGINT,ownership_receipt VARCHAR(128),ownership_reference VARCHAR(64),source_evidence_hash CHAR(64),return_address VARCHAR(42)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_cregis_exception_source(project_id BIGINT,txid VARCHAR(66),log_index INT,cid BIGINT,address VARCHAR(42),gross_amount DECIMAL(18,6),state VARCHAR(24),operation_kind VARCHAR(16),operation_ref VARCHAR(96)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_cregis_ownership_challenge(challenge_id VARCHAR(128),project_id BIGINT,action VARCHAR(32),target_user_id BIGINT,consumed_ref VARCHAR(96),evidence_hash CHAR(64),consumed_signature VARCHAR(132),signer VARCHAR(42),consumed_at DATETIME,expires_at DATETIME) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_cregis_chain_observation(project_id BIGINT,txid VARCHAR(66),log_index INT,status VARCHAR(32)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_cregis_deposit_address(project_id BIGINT,address VARCHAR(42),state VARCHAR(24)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_cregis_deposit_delivery(accepted INT,processed_at DATETIME,cid BIGINT,txid VARCHAR(66),address VARCHAR(42),gross_amount DECIMAL(18,6),signature_valid INT,timestamp_valid INT,ip_valid INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_cregis_risk_alert(project_id BIGINT,severity VARCHAR(4),resolved_at DATETIME,kind VARCHAR(32),alert_key VARCHAR(128)) ENGINE=InnoDB");
        var configuration=new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("refund-isolated-schema",new SpringManagedTransactionFactory(),data));configuration.addMapper(CregisExceptionRefundMapper.class);
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(CregisExceptionRefundMapper.class);
        seed(1,901,tx1,address1);seed(2,902,tx2,address2);
    }
    @AfterEach void close(){try{if(schemaOwned)jdbc.execute("DROP DATABASE "+SCHEMA);}finally{if(data!=null)data.destroy();}}
    void seed(long id,long cid,String tx,String address){
        String no="CR-RF-"+id;
        jdbc.update("INSERT INTO nx_cregis_exception_refund VALUES(?,?,?, ?,3,?,?,5,1,4,'APPROVED',7,8,?,?,?,?)",id,no,project,tx,cid,address,"challenge-"+id,no,"e".repeat(64),address);
        jdbc.update("INSERT INTO nx_cregis_exception_source VALUES(?,?,3,?,?,5,'RESERVED','REFUND',?)",project,tx,cid,address,no);
        jdbc.update("INSERT INTO nx_cregis_ownership_challenge VALUES(?,?,'REFUND_ORIGIN',NULL,?,?,?,?,NOW(),DATE_ADD(NOW(),INTERVAL 1 MINUTE))","challenge-"+id,project,no,"e".repeat(64),"0x"+"1".repeat(130),address);
        jdbc.update("INSERT INTO nx_cregis_chain_observation VALUES(?,?,3,'UNATTRIBUTED_HOLD')",project,tx);
        jdbc.update("INSERT INTO nx_cregis_deposit_address VALUES(?,?,'UNATTRIBUTED_HOLD')",project,address);
        jdbc.update("INSERT INTO nx_cregis_deposit_delivery VALUES(1,NULL,?,?,?,5,1,1,1)",cid,tx,address);
        jdbc.update("INSERT INTO nx_cregis_risk_alert VALUES(?,'P0',NULL,'PROVIDER_ORPHAN',?)",project,"orphan-cid-"+cid);
    }
    @Test void twoValidApprovalsCanProgressSequentiallyButUnknownFirstBlocksSecond(){
        assertThat(mapper.otherUnresolved(project,901,tx1,3,address1,new BigDecimal("5"))).isZero();
        assertThat(mapper.otherCriticalAlerts(project,901)).isZero();assertThat(mapper.otherActiveRefunds(project,1)).isZero();
        jdbc.update("UPDATE nx_cregis_exception_refund SET state='SUBMITTING' WHERE id=1");assertThat(mapper.otherActiveRefunds(project,2)).isEqualTo(1);
        jdbc.update("UPDATE nx_cregis_exception_refund SET state='UNKNOWN' WHERE id=1");assertThat(mapper.otherActiveRefunds(project,2)).isEqualTo(1);
        jdbc.update("UPDATE nx_cregis_exception_refund SET state='COMPLETED' WHERE id=1");
        jdbc.update("UPDATE nx_cregis_exception_source SET state='REFUNDED' WHERE cid=901");jdbc.update("UPDATE nx_cregis_chain_observation SET status='REFUNDED' WHERE txid=?",tx1);
        jdbc.update("UPDATE nx_cregis_deposit_address SET state='RETIRED' WHERE address=?",address1);jdbc.update("UPDATE nx_cregis_deposit_delivery SET processed_at=NOW() WHERE cid=901");jdbc.update("UPDATE nx_cregis_risk_alert SET resolved_at=NOW() WHERE alert_key='orphan-cid-901'");
        assertThat(mapper.otherActiveRefunds(project,2)).isZero();assertThat(mapper.otherUnresolved(project,902,tx2,3,address2,new BigDecimal("5"))).isZero();
    }
    @Test void forgedOrUnapprovedOtherSourceAndUnrelatedP0RemainBlocked(){
        jdbc.update("UPDATE nx_cregis_exception_refund SET checker_id=maker_id WHERE id=2");
        assertThat(mapper.otherUnresolved(project,901,tx1,3,address1,new BigDecimal("5"))).isGreaterThan(0);
        jdbc.update("UPDATE nx_cregis_exception_refund SET checker_id=8 WHERE id=2");jdbc.update("UPDATE nx_cregis_exception_source SET operation_ref='wrong' WHERE cid=902");
        assertThat(mapper.otherUnresolved(project,901,tx1,3,address1,new BigDecimal("5"))).isGreaterThan(0);
        jdbc.update("UPDATE nx_cregis_exception_source SET operation_ref='CR-RF-2' WHERE cid=902");
        jdbc.update("UPDATE nx_cregis_ownership_challenge SET consumed_signature=NULL WHERE challenge_id='challenge-2'");
        assertThat(mapper.otherUnresolved(project,901,tx1,3,address1,new BigDecimal("5"))).isGreaterThan(0);
        jdbc.update("UPDATE nx_cregis_ownership_challenge SET consumed_signature=?,signer=? WHERE challenge_id='challenge-2'","0x"+"1".repeat(130),address1);
        assertThat(mapper.otherUnresolved(project,901,tx1,3,address1,new BigDecimal("5"))).isGreaterThan(0);
        jdbc.update("INSERT INTO nx_cregis_risk_alert VALUES(?,'P0',NULL,'MANUAL_EMERGENCY_OFF','manual-emergency')",project);
        assertThat(mapper.otherCriticalAlerts(project,901)).isGreaterThan(0);
    }
    @Test void aggregateFiveHundredHoldCanBeReducedButOtherP0CannotBeBypassed(){
        jdbc.update("INSERT INTO nx_cregis_risk_alert VALUES(?,'P0',NULL,'UNRESOLVED_EXPOSURE','unresolved-exposure-500')",project);
        assertThat(mapper.otherCriticalAlerts(project,901)).isZero();
        assertThat(mapper.otherUnresolved(project,901,tx1,3,address1,new BigDecimal("5"))).isZero();
        jdbc.update("INSERT INTO nx_cregis_risk_alert VALUES(?,'P0',NULL,'MANUAL_EMERGENCY_OFF','manual-emergency')",project);
        assertThat(mapper.otherCriticalAlerts(project,901)).isEqualTo(1);
        jdbc.update("INSERT INTO nx_cregis_risk_alert VALUES(?,'P0',NULL,'UNRESOLVED_EXPOSURE','different-key')",project);
        assertThat(mapper.otherCriticalAlerts(project,901)).isEqualTo(2);
    }
}
