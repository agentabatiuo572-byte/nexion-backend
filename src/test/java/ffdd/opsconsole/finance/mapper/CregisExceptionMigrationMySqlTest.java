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

/** Applies the actual three migration files twice in a separately created, fixed test-only schema. */
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
                execute(connection,"INSERT INTO nx_cregis_deposit_event VALUES(1,2,100,7,42,3,'REORG_INVESTIGATING',10,NOW())");
                execute(connection,"INSERT INTO nx_cregis_deposit_incident(event_id,project_id,cid,user_id,kind,held_amount) VALUES(1,42,3,7,'REORG_INVESTIGATING',40)");
                assertThatThrownBy(()->guard(connection)).hasMessage("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED");
                for(int run=0;run<2;run++)for(String migration:List.of("20261022_01_cregis_exception_claim.sql","20261022_02_cregis_exception_refund.sql","20261022_03_cregis_reorg_resolution.sql"))apply(connection,Files.readString(Path.of("scripts/migrations",migration)));
                guard(connection);
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
                execute(connection,"ALTER TABLE nx_cregis_exception_source DROP PRIMARY KEY");
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
