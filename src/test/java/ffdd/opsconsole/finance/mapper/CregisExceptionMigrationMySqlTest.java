package ffdd.opsconsole.finance.mapper;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import ffdd.opsconsole.finance.cregis.CregisExceptionSchemaGuard;
import ffdd.opsconsole.finance.cregis.CregisProperties;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Applies the actual exception and ERC20 migrations twice in a separately created test-only schema. */
@EnabledIfEnvironmentVariable(named="CREGIS_EXCEPTION_MYSQL_IT",matches="true")
class CregisExceptionMigrationMySqlTest {
    static final String SCHEMA="cregis_exception_migration_test";
    @Test void actualMigrationScriptsAreRepeatablePreserveHistoryAndAllowAnotherReorgEpoch()throws Exception {
        String url=System.getenv("CREGIS_EXCEPTION_MYSQL_JDBC");assertThat(url).matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/cregis_exception_test(?:\\?.*)?");
        try(var connection=DriverManager.getConnection(url,System.getenv("CREGIS_EXCEPTION_MYSQL_USER"),System.getenv("CREGIS_EXCEPTION_MYSQL_PASSWORD"))) {
            execute(connection,"CREATE DATABASE "+SCHEMA);
            try {
                execute(connection,"USE "+SCHEMA);assertThat(value(connection,"SELECT DATABASE()")).isEqualTo(SCHEMA);
                execute(connection,"CREATE TABLE nx_cregis_deposit_event(id BIGINT PRIMARY KEY,log_index INT,net_amount DECIMAL(18,6),user_id BIGINT,project_id BIGINT,cid BIGINT,status VARCHAR(24),ledger_id BIGINT,credited_at DATETIME)");
                execute(connection,"CREATE TABLE nx_cregis_deposit_incident(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id BIGINT,project_id BIGINT,cid BIGINT,user_id BIGINT,kind VARCHAR(32),held_amount DECIMAL(18,6),UNIQUE KEY uk_cregis_incident_kind(event_id,kind))");
                execute(connection,"CREATE TABLE nx_audit_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,action VARCHAR(96),resource_type VARCHAR(48),resource_id VARCHAR(96),detail_json JSON,result VARCHAR(24),is_deleted INT)");
                // Exact pre-signature-audit challenge shape from 5dda4999; upgrade must add columns before triggers.
                execute(connection,"CREATE TABLE nx_cregis_ownership_challenge(challenge_id VARCHAR(36) PRIMARY KEY,project_id BIGINT NOT NULL,source_type VARCHAR(16) NOT NULL,source_id BIGINT NOT NULL,action VARCHAR(24) NOT NULL,target_user_id BIGINT NULL,message TEXT NOT NULL,evidence_hash VARCHAR(64) NOT NULL,expires_at DATETIME NOT NULL,consumed_ref VARCHAR(96) NULL,created_by BIGINT NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_cregis_challenge_expiry(expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                execute(connection,"INSERT INTO nx_cregis_ownership_challenge(challenge_id,project_id,source_type,source_id,action,message,evidence_hash,expires_at,created_by) VALUES('legacy-unused',42,'OBSERVATION',1,'REFUND_ORIGIN','legacy-issued-message','legacy-evidence',UTC_TIMESTAMP()+INTERVAL 10 MINUTE,1)");
                execute(connection,"INSERT INTO nx_cregis_deposit_event VALUES(1,2,100,7,42,3,'REORG_INVESTIGATING',10,NOW())");
                execute(connection,"INSERT INTO nx_cregis_deposit_incident(event_id,project_id,cid,user_id,kind,held_amount) VALUES(1,42,3,7,'REORG_INVESTIGATING',40)");
                assertThatThrownBy(()->guard(connection)).hasMessage("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED");
                for(int run=0;run<2;run++)for(String migration:List.of("20261022_01_cregis_exception_claim.sql","20261022_02_cregis_exception_refund.sql","20261022_03_cregis_reorg_resolution.sql"))apply(connection,Files.readString(Path.of("scripts/migrations",migration)));
                assertThatThrownBy(()->guard(connection)).hasMessage("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED");
                // Other already-migrated tables touched by the real ERC20 migration; no business data is copied.
                execute(connection,"ALTER TABLE nx_cregis_deposit_event ADD txid VARCHAR(66),ADD UNIQUE KEY uk_cregis_event_log(project_id,txid,log_index)");
                execute(connection,"CREATE TABLE nx_cregis_chain_observation(project_id BIGINT,txid VARCHAR(66),log_index INT,UNIQUE KEY uk_cregis_observation_log(project_id,txid,log_index))");
                execute(connection,"CREATE TABLE nx_cregis_deposit_delivery(id BIGINT PRIMARY KEY)");
                execute(connection,"CREATE TABLE nx_cregis_reconcile_run(run_id VARCHAR(64) PRIMARY KEY)");
                execute(connection,"CREATE TABLE nx_deposit_order(chain_name VARCHAR(32),chain_tx_hash VARCHAR(66),asset VARCHAR(16),chain_log_index INT,UNIQUE KEY uk_deposit_chain_tx_asset_log(chain_tx_hash,asset,chain_log_index))");
                execute(connection,"CREATE TABLE nx_purchase_payment_intent(payment_method VARCHAR(24),address VARCHAR(64),UNIQUE KEY uk_purchase_address(address))");
                execute(connection,"CREATE TABLE nx_purchase_payment_arrival(provider_project BIGINT,txid VARCHAR(66),log_index INT,UNIQUE KEY uk_purchase_arrival(provider_project,txid,log_index))");
                execute(connection,"CREATE TABLE nx_cregis_address_pool_policy(id TINYINT PRIMARY KEY)");
                for(int run=0;run<2;run++)apply(connection,Files.readString(Path.of("scripts/migrations/20261031_z_cregis_erc20_network.sql")));
                guard(connection);
                execute(connection,"INSERT INTO nx_cregis_exception_source(project_id,chain_id,txid,log_index,cid,address,gross_amount) VALUES(42,'2510','same-hash',1,10,'same-address',10),(42,'60','same-hash',1,11,'same-address',10)");
                assertThat(value(connection,"SELECT COUNT(*) FROM nx_cregis_exception_source WHERE txid='same-hash'")).isEqualTo("2");
                assertThatThrownBy(()->execute(connection,"INSERT INTO nx_cregis_exception_source(project_id,chain_id,txid,log_index,cid,address,gross_amount) VALUES(42,'60','same-hash',1,12,'same-address',10)")).isInstanceOf(SQLException.class);
                assertThat(value(connection,"SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_ownership_challenge' AND COLUMN_NAME IN ('consumed_signature','signer','consumed_at')")).isEqualTo("3");
                assertThat(value(connection,"SELECT message FROM nx_cregis_ownership_challenge WHERE challenge_id='legacy-unused'")).isEqualTo("legacy-issued-message");
                assertThat(value(connection,"SELECT COUNT(*) FROM nx_cregis_ownership_challenge WHERE challenge_id='legacy-unused' AND consumed_ref IS NULL AND consumed_signature IS NULL AND signer IS NULL AND consumed_at IS NULL")).isEqualTo("1");
                execute(connection,"INSERT INTO nx_cregis_ownership_challenge(challenge_id,project_id,source_type,source_id,action,message,evidence_hash,expires_at,created_by) VALUES('fixture-proof',42,'OBSERVATION',1,'REFUND_ORIGIN','fixture-message','fixture-hash',UTC_TIMESTAMP()+INTERVAL 10 MINUTE,1)");
                assertThatThrownBy(()->execute(connection,"UPDATE nx_cregis_ownership_challenge SET message='changed' WHERE challenge_id='fixture-proof'")).hasMessageContaining("CREGIS_OWNERSHIP_RECEIPT_IMMUTABLE");
                execute(connection,"UPDATE nx_cregis_ownership_challenge SET consumed_ref='fixture-case',consumed_signature='0xfixture',signer='0xfixture',consumed_at=UTC_TIMESTAMP() WHERE challenge_id='fixture-proof'");
                assertThat(value(connection,"SELECT consumed_ref FROM nx_cregis_ownership_challenge WHERE challenge_id='fixture-proof'")).isEqualTo("fixture-case");
                assertThatThrownBy(()->execute(connection,"UPDATE nx_cregis_ownership_challenge SET consumed_ref='another-case' WHERE challenge_id='fixture-proof'")).hasMessageContaining("CREGIS_OWNERSHIP_RECEIPT_IMMUTABLE");
                assertThatThrownBy(()->execute(connection,"DELETE FROM nx_cregis_ownership_challenge WHERE challenge_id='fixture-proof'")).hasMessageContaining("CREGIS_OWNERSHIP_RECEIPT_IMMUTABLE");
                assertThat(value(connection,"SELECT origin_log_index FROM nx_cregis_deposit_event WHERE id=1")).isEqualTo("2");
                assertThat(value(connection,"SELECT COUNT(*) FROM nx_cregis_reorg_resolution")).isEqualTo("1");assertThat(value(connection,"SELECT held_amount FROM nx_cregis_reorg_resolution WHERE incident_id=1")).isEqualTo("40.000000");
                execute(connection,"INSERT INTO nx_cregis_deposit_incident(event_id,project_id,cid,user_id,kind,held_amount) VALUES(1,42,3,7,'REORG_INVESTIGATING',20)");
                execute(connection,"INSERT INTO nx_cregis_reorg_resolution(incident_id,event_id,project_id,cid,user_id,net_amount,held_amount) VALUES(2,1,42,3,7,100,20)");assertThat(value(connection,"SELECT COUNT(*) FROM nx_cregis_reorg_resolution WHERE event_id=1")).isEqualTo("2");
                execute(connection,"INSERT INTO nx_cregis_reorg_operation VALUES('CR-REORG-1:0',1,0,'HOLD','DEPOSIT:10','NONE','{}','{}','[]',NOW(6))");
                assertThatThrownBy(()->execute(connection,"UPDATE nx_cregis_reorg_operation SET phase='RESTORE' WHERE operation_no='CR-REORG-1:0'")).hasMessageContaining("CREGIS_REORG_OPERATION_IMMUTABLE");
                assertThatThrownBy(()->execute(connection,"DELETE FROM nx_cregis_reorg_operation WHERE operation_no='CR-REORG-1:0'")).hasMessageContaining("CREGIS_REORG_OPERATION_IMMUTABLE");
                execute(connection,"INSERT INTO nx_audit_log(action,resource_type,resource_id,detail_json,result,is_deleted) VALUES('CREGIS_REORG_OPERATION_HOLD','CREGIS_INCIDENT','1','{\"operationNo\":\"CR-REORG-1:0\"}','SUCCESS',0)");
                String sql=SupportPaymentSourceSql.currentMarker(Map.of("marker",SupportPaymentSourceMapper.Marker.CREGIS_EVENT)).replace("#{id}","1");
                try(var statement=connection.createStatement();var result=statement.executeQuery(sql)){assertThat(result.next()).isTrue();assertThat(result.getInt("lifecycleVerified")).isEqualTo(1);}
                execute(connection,"ALTER TABLE nx_cregis_exception_source MODIFY gross_amount DECIMAL(18,5) NOT NULL");
                assertThatThrownBy(()->guard(connection)).hasMessage("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED");
                execute(connection,"ALTER TABLE nx_cregis_exception_source MODIFY gross_amount DECIMAL(18,6) NOT NULL");
                execute(connection,"ALTER TABLE nx_cregis_exception_source DROP COLUMN alias_of_log_index");
                assertThatThrownBy(()->guard(connection)).hasMessage("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED");
                execute(connection,"ALTER TABLE nx_cregis_exception_source ADD COLUMN alias_of_log_index INT NULL");
                execute(connection,"DELETE FROM nx_cregis_exception_source WHERE chain_id='60'");
                execute(connection,"ALTER TABLE nx_cregis_exception_source DROP PRIMARY KEY,ADD PRIMARY KEY(project_id,txid,log_index)");
                assertThatThrownBy(()->guard(connection)).hasMessage("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED");
                execute(connection,"ALTER TABLE nx_cregis_exception_source DROP PRIMARY KEY,DROP COLUMN chain_id");
                assertThatThrownBy(()->guard(connection)).hasMessage("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED");
                execute(connection,"ALTER TABLE nx_cregis_exception_source ADD chain_id VARCHAR(16) NOT NULL DEFAULT '2510',ADD PRIMARY KEY(chain_id,project_id,txid,log_index)");
                assertThatThrownBy(()->guard(connection)).hasMessage("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED");
                execute(connection,"ALTER TABLE nx_cregis_exception_source DROP PRIMARY KEY");
                execute(connection,"ALTER TABLE nx_cregis_exception_source ADD KEY source_log(project_id,chain_id,txid,log_index)");
                assertThatThrownBy(()->guard(connection)).hasMessage("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED");
                execute(connection,"ALTER TABLE nx_cregis_exception_source DROP KEY source_log,ADD UNIQUE KEY source_log(project_id,chain_id,txid,log_index)");
                assertThatThrownBy(()->guard(connection)).hasMessage("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED");
                execute(connection,"ALTER TABLE nx_cregis_exception_source DROP KEY source_log,ADD PRIMARY KEY(project_id,chain_id,txid(8),log_index)");
                assertThatThrownBy(()->guard(connection)).hasMessage("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED");
            } finally {execute(connection,"DROP DATABASE "+SCHEMA);}
        }
    }
    static void guard(Connection c) {
        var configuration=new Configuration(new Environment("migration-guard",new JdbcTransactionFactory(),new SingleConnectionDataSource(c,true)));configuration.addMapper(CregisExceptionSchemaMapper.class);
        try(var session=new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var properties=new CregisProperties();properties.setMode(CregisProperties.Mode.PROVIDER);
            new CregisExceptionSchemaGuard(properties,session.getMapper(CregisExceptionSchemaMapper.class)).run(null);
        }
    }
    static void apply(Connection c,String script)throws Exception {
        String delimiter=";";var statement=new StringBuilder();
        for(String line:script.split("\\R")) {
            String trimmed=line.trim();if(trimmed.isEmpty() || trimmed.startsWith("--"))continue;
            if(trimmed.startsWith("DELIMITER ")){delimiter=trimmed.substring(10).trim();continue;}
            statement.append(line).append('\n');String sql=statement.toString().trim();
            if(sql.endsWith(delimiter)){execute(c,sql.substring(0,sql.length()-delimiter.length()));statement.setLength(0);}
        }
        assertThat(statement.toString().trim()).isEmpty();
    }
    static String value(Connection c,String sql)throws Exception {try(var statement=c.createStatement();var result=statement.executeQuery(sql)){assertThat(result.next()).isTrue();return result.getString(1);}}
    static void execute(Connection c,String sql)throws Exception {try(var statement=c.createStatement()){statement.execute(sql);}}
}
