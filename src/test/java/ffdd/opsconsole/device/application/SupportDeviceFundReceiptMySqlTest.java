package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade.FundEarnings;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Real mapper and service, disposable schema only; never reads or changes business rows. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
class SupportDeviceFundReceiptMySqlTest {
    static final String NO="CTR-EXACT",TASK="CTA-EXACT",DAY="CLOUD_SHARE_DAILY:11:2026-10-11";
    @ParameterizedTest @ValueSource(strings={"utf8mb4_0900_ai_ci","utf8mb4_unicode_ci"})
    void indexedReceiptEvidenceMatchesTheLegacySelectionAcrossHistoryAndCorruptOriginals(String collation) throws Exception {
        String source=System.getenv().getOrDefault("NEXION_TEST_DB_URL","jdbc:mysql://127.0.0.1:3306/mysql?useSSL=false&allowPublicKeyRetrieval=true");
        String user=System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root"),password=System.getenv("NEXION_TEST_DB_PASSWORD");
        assertThat(source).matches("^jdbc:mysql://(127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?/[^?]+(?:\\?.*)?$");
        String database="nx_device_receipt_"+UUID.randomUUID().toString().replace("-","");
        int slash=source.indexOf('/',"jdbc:mysql://".length()),query=source.indexOf('?',slash);
        String url=source.substring(0,slash+1)+database+(query<0?"":source.substring(query));
        try(var admin=DriverManager.getConnection(source,user,password)){
            admin.createStatement().execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE "+collation);
            try(var connection=DriverManager.getConnection(url,user,password)){
                fixture(connection);
                var config=new Configuration(new Environment("device-receipt",new JdbcTransactionFactory(),new SingleConnectionDataSource(connection,true)));
                config.addMapper(SupportDeviceReadMapper.class);
                try(var session=new SqlSessionFactoryBuilder().build(config).openSession(true)){
                    var mapper=session.getMapper(SupportDeviceReadMapper.class);var service=new SupportDeviceReadService(mapper);
                    var before=service.readFundEarning(7,NO);assertShape(before,1,1,2,2);compareLegacy(service);
                    history(connection);
                    var after=service.readFundEarning(7,NO);assertThat(after).isEqualTo(before);compareLegacy(service);
                    for(String method:List.of("fundReceipt","fundReceiptTasks","fundReceiptLedgers","fundReceiptEvents"))assertIndexed(connection,config,method);
                    assertThatThrownBy(()->service.readFundEarning(8,NO)).isInstanceOf(IllegalStateException.class).hasMessage("Device receipt missing or ambiguous");
                    assertThatThrownBy(()->service.readFundEarning(7,"ctr-exact")).isInstanceOf(IllegalStateException.class).hasMessage("Device receipt missing or ambiguous");
                    // UTC midnight is 08:00 in the business zone; fractional seconds must not move the day.
                    sql(connection,"UPDATE nx_compute_receipt SET completed_at='2026-10-12 08:00:00.000000' WHERE receipt_no='CTR-EXACT'");
                    sql(connection,"UPDATE nx_wallet_ledger SET biz_no='CLOUD_SHARE_DAILY:11:2026-10-12' WHERE biz_no='"+DAY+"'");compareLegacy(service);assertShape(service.readFundEarning(7,NO),1,1,2,2);
                    sql(connection,"UPDATE nx_compute_receipt SET reward_nex=0 WHERE receipt_no='CTR-EXACT'");compareLegacy(service);assertShape(service.readFundEarning(7,NO),1,1,1,2);
                    sql(connection,"UPDATE nx_compute_receipt SET reward_nex=4.25,source_environment='TEST',is_deleted=1 WHERE receipt_no='CTR-EXACT'");
                    sql(connection,"UPDATE nx_compute_task SET status='FAILED',is_deleted=1 WHERE task_no='CTA-EXACT'");
                    sql(connection,"UPDATE nx_wallet_ledger SET status='FAILED',is_deleted=1,asset='usdt' WHERE biz_no='CTA-EXACT'");
                    sql(connection,"UPDATE nx_earning_event SET status='FAILED',is_deleted=1 WHERE receipt_no='CTR-EXACT'");compareLegacy(service);
                    assertThat(service.readFundEarning(7,NO).ledgers().get(0)).containsEntry("asset","usdt").containsEntry("status","FAILED");
                    // Broken uniqueness must remain visible, and a case-near identity must not enter the signed snapshot.
                    sql(connection,"ALTER TABLE nx_compute_receipt DROP INDEX uk_receipt_no, ADD INDEX idx_receipt_no(receipt_no)");
                    sql(connection,"INSERT INTO nx_compute_receipt SELECT id+20000,user_id,user_device_id,task_no,'ctr-exact',task_type,client_name,reward_usdt,reward_nex,earning_status,source_environment,proof_hash,completed_at,created_at,updated_at,is_deleted FROM nx_compute_receipt WHERE id=1");
                    sql(connection,"INSERT INTO nx_compute_receipt SELECT id+25000,user_id,user_device_id,task_no,'CTR-EXACT ',task_type,client_name,reward_usdt,reward_nex,earning_status,source_environment,proof_hash,completed_at,created_at,updated_at,is_deleted FROM nx_compute_receipt WHERE id=1");compareLegacy(service);assertShape(service.readFundEarning(7,NO),1,1,2,2);
                    sql(connection,"INSERT INTO nx_compute_receipt SELECT id+30000,user_id,user_device_id,task_no,receipt_no,task_type,client_name,reward_usdt,reward_nex,earning_status,source_environment,proof_hash,completed_at,created_at,updated_at,is_deleted FROM nx_compute_receipt WHERE id=1");
                    assertThatThrownBy(()->service.readFundEarning(7,NO)).hasMessage("Device receipt missing or ambiguous");sql(connection,"DELETE FROM nx_compute_receipt WHERE id=30001");
                    sql(connection,"ALTER TABLE nx_compute_task DROP INDEX uk_compute_task_no, ADD INDEX idx_task_no(task_no)");
                    for(String[] near:List.of(new String[]{"22000","cta-exact","ctr-exact"},new String[]{"23000","CTA-EXACT ","CTR-EXACT "})){
                        sql(connection,"INSERT INTO nx_compute_task SELECT id+"+near[0]+",user_id,user_device_id,'"+near[1]+"',task_config_id,task_type,reward_usdt,status,source_environment,client_name,started_at,completed_at,proof_consumed_at,is_deleted FROM nx_compute_task WHERE id=1");
                        sql(connection,"INSERT INTO nx_wallet_ledger SELECT id+"+near[0]+",user_id,'"+near[1]+"',biz_type,'NEAR"+near[0]+"',direction,amount,balance_after,status,created_at,updated_at,is_deleted FROM nx_wallet_ledger WHERE id=1");
                        sql(connection,"INSERT INTO nx_earning_event SELECT id+"+near[0]+",event_no,user_id,user_device_id,'"+near[2]+"','NEAR"+near[0]+"',amount,status,wallet_posted_at,created_at,updated_at,is_deleted FROM nx_earning_event WHERE id=1");
                    }
                    compareLegacy(service);assertShape(service.readFundEarning(7,NO),1,1,2,2);
                    sql(connection,"INSERT INTO nx_compute_task SELECT id+20000,user_id,user_device_id,task_no,task_config_id,task_type,reward_usdt,status,source_environment,client_name,started_at,completed_at,proof_consumed_at,is_deleted FROM nx_compute_task WHERE id=1");
                    sql(connection,"INSERT INTO nx_wallet_ledger SELECT id+20000,user_id,biz_no,biz_type,'OTHER',direction,amount,balance_after,status,created_at,updated_at,is_deleted FROM nx_wallet_ledger WHERE id=1");
                    sql(connection,"ALTER TABLE nx_earning_event DROP INDEX uk_earning_receipt_asset, ADD INDEX idx_receipt_asset(receipt_no,asset)");
                    sql(connection,"INSERT INTO nx_earning_event SELECT id+20000,event_no,user_id,user_device_id,receipt_no,'OTHER',amount,status,wallet_posted_at,created_at,updated_at,is_deleted FROM nx_earning_event WHERE id=1");
                    compareLegacy(service);assertShape(service.readFundEarning(7,NO),1,2,3,3);
                    sql(connection,"DELETE FROM nx_compute_task WHERE task_no='CTA-EXACT'");sql(connection,"DELETE FROM nx_wallet_ledger WHERE biz_no='CTA-EXACT'");sql(connection,"DELETE FROM nx_earning_event WHERE receipt_no='CTR-EXACT'");
                    compareLegacy(service);assertShape(service.readFundEarning(7,NO),1,0,1,0);
                    sql(connection,"DELETE FROM nx_compute_receipt WHERE receipt_no='CTR-EXACT'");assertThatThrownBy(()->service.readFundEarning(7,NO)).hasMessage("Device receipt missing or ambiguous");
                }
            }finally{admin.createStatement().execute("DROP DATABASE "+database);}
        }
    }
    static void compareLegacy(SupportDeviceReadService service){
        var all=service.readFundEarnings(List.of(7L));var receipt=all.receipts().stream().filter(r->NO.equals(r.get("receiptNo"))).findFirst().orElseThrow();
        var ledgerNos=new HashSet<String>();ledgerNos.add((String)receipt.get("taskNo"));
        if(((Number)receipt.get("rewardNex")).doubleValue()>0){Object v=receipt.get("completedAt");var at=v instanceof Timestamp t?t.toLocalDateTime():(LocalDateTime)v;ledgerNos.add("CLOUD_SHARE_DAILY:"+receipt.get("deviceId")+":"+at.atZone(ZoneId.of("Asia/Shanghai")).withZoneSameInstant(ZoneOffset.UTC).toLocalDate());}
        var expected=new FundEarnings(List.of(receipt),all.tasks().stream().filter(t->TASK.equals(t.get("taskNo"))).toList(),all.ledgers().stream().filter(l->ledgerNos.contains(l.get("bizNo"))).toList(),all.earningEvents().stream().filter(e->NO.equals(e.get("receiptNo"))).toList());
        assertThat(service.readFundEarning(7,NO)).isEqualTo(expected);
    }
    static void assertShape(FundEarnings value,int receipts,int tasks,int ledgers,int events){assertThat(value.receipts()).hasSize(receipts);assertThat(value.tasks()).hasSize(tasks);assertThat(value.ledgers()).hasSize(ledgers);assertThat(value.earningEvents()).hasSize(events);}
    static void assertIndexed(Connection connection,Configuration config,String method) throws Exception {
        var params=Map.<String,Object>of("customer",7L,"receiptNo",NO,"taskNo",TASK,"ledgerNos",List.of(TASK,DAY));
        var bound=config.getMappedStatement(SupportDeviceReadMapper.class.getName()+"."+method).getBoundSql(params);
        try(var statement=connection.prepareStatement("EXPLAIN "+bound.getSql())){
            for(int i=0;i<bound.getParameterMappings().size();i++){String name=bound.getParameterMappings().get(i).getProperty();statement.setObject(i+1,bound.hasAdditionalParameter(name)?bound.getAdditionalParameter(name):params.get(name));}
            try(var rows=statement.executeQuery()){while(rows.next()){String table=rows.getString("table");if(table==null)continue;assertThat(rows.getString("key")).as(method+" indexed "+table).isNotBlank();assertThat(rows.getLong("rows")).as(method+" bounded "+table).isLessThan(10);}}
        }
    }
    static void sql(Connection c,String sql) throws Exception {try(var statement=c.createStatement()){statement.execute(sql);}}
    static void fixture(Connection c) throws Exception {
        sql(c,"CREATE TABLE nx_user(id BIGINT PRIMARY KEY,sandbox INT,is_deleted INT)");sql(c,"INSERT INTO nx_user VALUES(7,0,0),(8,0,0)");
        sql(c,"CREATE TABLE nx_compute_receipt(id BIGINT PRIMARY KEY,user_id BIGINT,user_device_id BIGINT,task_no VARCHAR(96),receipt_no VARCHAR(96),task_type VARCHAR(64),client_name VARCHAR(128),reward_usdt DECIMAL(18,6),reward_nex DECIMAL(18,6),earning_status VARCHAR(32),source_environment VARCHAR(16),proof_hash VARCHAR(128),completed_at DATETIME(6),created_at DATETIME,updated_at DATETIME,is_deleted INT,UNIQUE KEY uk_receipt_no(receipt_no),KEY idx_receipt_user_time(user_id,completed_at))");
        sql(c,"CREATE TABLE nx_compute_task(id BIGINT PRIMARY KEY,user_id BIGINT,user_device_id BIGINT,task_no VARCHAR(96),task_config_id VARCHAR(64),task_type VARCHAR(64),reward_usdt DECIMAL(18,6),status VARCHAR(32),source_environment VARCHAR(16),client_name VARCHAR(128),started_at DATETIME,completed_at DATETIME(6),proof_consumed_at DATETIME,is_deleted INT,UNIQUE KEY uk_compute_task_no(task_no),KEY idx_compute_task_user_status(user_id,status))");
        sql(c,"CREATE TABLE nx_wallet_ledger(id BIGINT PRIMARY KEY,user_id BIGINT,biz_no VARCHAR(96),biz_type VARCHAR(64),asset VARCHAR(16),direction VARCHAR(16),amount DECIMAL(18,6),balance_after DECIMAL(18,6),status VARCHAR(32),created_at DATETIME,updated_at DATETIME,is_deleted INT,UNIQUE KEY uk_wallet_ledger_biz(biz_no,asset,direction),KEY idx_wallet_ledger_user_time(user_id,created_at))");
        sql(c,"CREATE TABLE nx_earning_event(id BIGINT PRIMARY KEY,event_no VARCHAR(96),user_id BIGINT,user_device_id BIGINT,receipt_no VARCHAR(96),asset VARCHAR(16),amount DECIMAL(18,6),status VARCHAR(32),wallet_posted_at DATETIME,created_at DATETIME,updated_at DATETIME,is_deleted INT,UNIQUE KEY uk_earning_receipt_asset(receipt_no,asset),KEY idx_earning_user_time(user_id,created_at))");
        sql(c,"INSERT INTO nx_compute_receipt VALUES(1,7,11,'CTA-EXACT','CTR-EXACT','IG','fixture',.3,4.25,'CREDITED','PRODUCTION',REPEAT('a',64),'2026-10-12 07:59:59.999999','2026-10-12 07:59:59','2026-10-12 07:59:59',0)");
        sql(c,"INSERT INTO nx_compute_task VALUES(1,7,11,'CTA-EXACT','IG','IG',.3,'COMPLETED','PRODUCTION','fixture','2026-10-12 07:58:59','2026-10-12 07:59:59.999999','2026-10-12 07:59:59',0)");
        sql(c,"INSERT INTO nx_wallet_ledger VALUES(1,7,'CTA-EXACT','COMPUTE_TASK_REWARD','USDT','IN',.3,10.3,'SUCCESS','2026-10-12 07:59:59','2026-10-12 07:59:59',0),(2,7,'"+DAY+"','COMPUTE_TASK_REWARD','UVT','IN',4.25,24.25,'SUCCESS','2026-10-12 07:59:59','2026-10-12 07:59:59',0)");
        sql(c,"INSERT INTO nx_earning_event VALUES(1,'EARN-CTA-EXACT',7,11,'CTR-EXACT','USDT',.3,'POSTED','2026-10-12 07:59:59','2026-10-12 07:59:59','2026-10-12 07:59:59',0),(2,'EARN-NEX-CTR-EXACT',7,11,'CTR-EXACT','UVT',4.25,'POSTED','2026-10-12 07:59:59','2026-10-12 07:59:59','2026-10-12 07:59:59',0)");
    }
    static void history(Connection c) throws Exception {
        sql(c,"SET SESSION cte_max_recursion_depth=10001");
        String seq=" WITH RECURSIVE n AS(SELECT 1 AS i UNION ALL SELECT i+1 FROM n WHERE i<10000) ";
        sql(c,"INSERT INTO nx_compute_receipt"+seq+"SELECT i+100,7,11,CONCAT('H-T-',i),CONCAT('H-R-',i),'IG','fixture',.3,0,'CREDITED','PRODUCTION',REPEAT('a',64),'2026-10-10','2026-10-10','2026-10-10',0 FROM n");
        sql(c,"INSERT INTO nx_compute_task"+seq+"SELECT i+100,7,11,CONCAT('H-T-',i),'IG','IG',.3,'COMPLETED','PRODUCTION','fixture','2026-10-10','2026-10-10','2026-10-10',0 FROM n");
        sql(c,"INSERT INTO nx_wallet_ledger"+seq+"SELECT i+100,7,CONCAT('H-T-',i),'COMPUTE_TASK_REWARD','USDT','IN',.3,10,'SUCCESS','2026-10-10','2026-10-10',0 FROM n");
        sql(c,"INSERT INTO nx_earning_event"+seq+"SELECT i+100,CONCAT('EARN-H-T-',i),7,11,CONCAT('H-R-',i),'USDT',.3,'POSTED','2026-10-10','2026-10-10','2026-10-10',0 FROM n");
        for(String table:List.of("nx_compute_receipt","nx_compute_task","nx_wallet_ledger","nx_earning_event"))sql(c,"ANALYZE TABLE "+table);
    }
}
