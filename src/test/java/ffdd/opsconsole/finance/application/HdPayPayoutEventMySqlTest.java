package ffdd.opsconsole.finance.application;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.hdpay.HdPayPayoutCallbackVerifier;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.treasury.infrastructure.MybatisTreasuryLedgerRepository;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static ffdd.opsconsole.finance.application.BankWithdrawalMySqlTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Production A4 schemas, real outbox/ledger SQL and Spring transactions on the owned isolated MySQL. */
@EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT", matches="true")
class HdPayPayoutEventMySqlTest {
    static final String MIGRATION = "scripts/migrations/20260917_hdpay_payout_settlement_event_contract.sql";
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test void callbackAndQuerySettleOnceAfterTheRealSchemaMigration() throws Exception {
        isolated(f -> {
            EventOutboxService events = realEvents(f);
            f.seed(); f.transactions.prepare(NO); f.transactions.reconcile(NO, f.response(2));
            var callback = new HdPayPayoutCallbackVerifier.Callback(NO,123L,3,bd("2475000"),"a".repeat(64));
            assertEquals("success", f.transactions.accept(callback));
            assertEquals(java.util.List.of(NO), f.bank.queryDue(NOW));
            f.wallet("900","100"); // Durable callback receipt alone never changes money.
            var error = assertThrows(BizException.class, () -> f.transactions.reconcile(NO,f.response(3)));
            assertEquals("A4_SCHEMA_PROPERTY_NOT_REGISTERED",error.getMessage());
            unchanged(f);
            migrate(f); migrate(f);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var a = pool.submit(() -> f.transactions.reconcile(NO,f.response(3)));
                var b = pool.submit(() -> f.transactions.reconcile(NO,f.response(3)));
                a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            f.transactions.accept(callback); f.transactions.reconcile(NO,f.response(3));
            assertEquals("CONFIRMED", f.payouts.payout(NO).status());
            assertEquals("PAID", f.bank.order(NO).state());
            assertTrue(f.bank.queryDue(NOW.plusDays(1)).isEmpty());
            f.proofComplement(true);
            f.wallet("900","0");
            assertEquals(0,bd("900").compareTo(reserve(f)));
            assertEquals(1,count(f,"nx_withdrawal_payout_ledger WHERE status='CONFIRMED'"));
            assertEquals(1,count(f,"nx_event_outbox WHERE event_name='withdraw.confirmed'"));
            assertEquals(1,count(f,"nx_hdpay_payout_callback"));
            JsonNode payload = payload(f,"withdraw.confirmed");
            assertEquals("123",payload.path("provider_order_id").asText());
            assertEquals(NOW.toString(),payload.path("confirmed_at").asText());
            assertFalse(payload.has("chain_tx_hash"));
            assertFalse(payload.toString().contains("0123456789"));
            // The formerly accepted malformed payload must still fail after the migration.
            assertThrows(BizException.class, () -> events.publish("WITHDRAWAL","old","withdraw.confirmed",
                    Map.of("withdrawal_id","old","amount",100,"currency","USDT","state","CONFIRMED",
                            "amount_vnd",2475000,"provider","HDPAY")));
        });
    }

    @Test void providerFailureRefundsOnceWithActualRiskOrExplicitlyUnavailableRisk() throws Exception {
        for (Integer score : new Integer[]{null,37}) isolated(f -> {
            realEvents(f); migrate(f); f.seed();
            f.jdbc.update("UPDATE nx_withdrawal_order SET d2_k4_risk_score=?",score);
            f.transactions.prepare(NO);
            f.transactions.reconcile(NO,f.response(score == null ? 4 : 5));
            f.transactions.reconcile(NO,f.response(score == null ? 4 : 5));
            assertEquals("FAILED",f.payouts.payout(NO).status());
            assertEquals("FAILED",f.bank.order(NO).state());
            assertTrue(f.bank.queryDue(NOW.plusDays(1)).isEmpty());
            f.proofComplement(true);
            f.wallet("1000","0");
            assertEquals(0,bd("1000").compareTo(reserve(f)));
            assertEquals(1,count(f,"nx_wallet_ledger WHERE biz_type='WITHDRAW_PAYOUT_REFUND'"));
            assertEquals(0,bd("1000").compareTo(f.jdbc.queryForObject(
                    "SELECT balance_after FROM nx_wallet_ledger WHERE biz_type='WITHDRAW_PAYOUT_REFUND'",java.math.BigDecimal.class)));
            assertEquals(1,count(f,"nx_event_outbox WHERE event_name='withdraw.refunded'"));
            assertEquals(1,count(f,"nx_event_outbox WHERE event_name='wallet.ledger_posted'"));
            JsonNode payload=payload(f,"withdraw.refunded");
            if (score==null) {
                assertFalse(payload.has("risk_score"));
                assertEquals("UNAVAILABLE",payload.path("risk_score_status").asText());
            } else assertEquals(score.intValue(),payload.path("risk_score").asInt());
            assertEquals(ffdd.opsconsole.finance.hdpay.HdPayPayoutDigest.sha("BANK-VND:BNK-fixture"),
                    payload.path("address_hash").asText());
        });
    }

    @Test void outboxWriteFailureRollsBackMoneyAndRetryUsesOriginalOrder() throws Exception {
        for (int terminalStatus : new int[]{3,5}) isolated(f -> {
            realEvents(f); migrate(f); f.seed(); f.transactions.prepare(NO); f.transactions.reconcile(NO,f.response(2));
            f.jdbc.execute("""
                    CREATE TRIGGER reject_outbox BEFORE INSERT ON nx_event_outbox FOR EACH ROW
                    BEGIN IF NEW.aggregate_type='WITHDRAWAL' THEN
                      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture outage';
                    END IF; END
                    """);
            assertThrows(RuntimeException.class,()->f.transactions.reconcile(NO,f.response(terminalStatus)));
            unchanged(f);
            assertEquals(0,count(f,"nx_wallet_ledger WHERE biz_type='WITHDRAW_PAYOUT_REFUND'"));
            f.transactions.deferReconciliation(NO);
            assertEquals("BANK_PAYOUT_RECONCILIATION_RETRY",f.bank.order(NO).lastError());
            assertTrue(f.bank.ready(NOW.plusDays(1)).isEmpty());
            f.jdbc.execute("DROP TRIGGER reject_outbox");
            f.transactions.reconcile(NO,f.response(terminalStatus));
            f.wallet(terminalStatus==3 ? "900" : "1000","0");
            f.proofComplement(true);
            assertEquals(1,count(f,"nx_event_outbox WHERE aggregate_type='WITHDRAWAL'"));
        });
    }

    @Test void updatedRegistryPreservesChainProofAndBankProofBoundaries() throws Exception {
        isolated(f -> {
            var events=realEvents(f); migrate(f);
            var p=new java.util.LinkedHashMap<String,Object>(Map.of("withdrawal_id","CHAIN-1","amount",100,
                    "currency","USDT","state","CONFIRMED","confirmed_at",NOW.toString(),"operator","test","reason","verified"));
            assertThrows(BizException.class,()->events.publish("WITHDRAWAL","CHAIN-1","withdraw.confirmed",p));
            p.put("chain_tx_hash","a".repeat(64));
            assertNotNull(events.publish("WITHDRAWAL","CHAIN-1","withdraw.confirmed",p));
            p.put("rail","BANK-VND"); p.put("provider","HDPAY"); p.put("provider_order_id","123"); p.put("amount_vnd",2475000);
            assertThrows(BizException.class,()->events.publish("WITHDRAWAL","BANK-1","withdraw.confirmed",p));
            p.remove("chain_tx_hash");
            assertNotNull(events.publish("WITHDRAWAL","BANK-1","withdraw.confirmed",p));
            p.remove("provider_order_id");
            assertThrows(BizException.class,()->events.publish("WITHDRAWAL","BANK-1","withdraw.confirmed",p));
            f.jdbc.update("UPDATE nx_admin_event_lifecycle SET lifecycle_state='disabled' WHERE event_name='withdraw.confirmed'");
            p.put("provider_order_id","123");
            assertThrows(BizException.class,()->events.publish("WITHDRAWAL","BANK-1","withdraw.confirmed",p));
        });
    }

    static void unchanged(Fixture f) {
        assertEquals("SENT",f.payouts.payout(NO).status());
        f.wallet("900","100");
        assertEquals(0,bd("1000").compareTo(reserve(f)));
        assertEquals(0,count(f,"nx_event_outbox"));
        assertEquals(0,count(f,"nx_withdrawal_payout_ledger WHERE status IN ('CONFIRMED','FAILED')"));
    }
    static java.math.BigDecimal reserve(Fixture f) {
        return f.jdbc.queryForObject("SELECT SUM(IF(direction='IN',amount_usd,-amount_usd)) FROM nx_treasury_reserve_ledger",java.math.BigDecimal.class);
    }
    static int count(Fixture f,String table) { return f.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    static JsonNode payload(Fixture f,String event) throws Exception {
        return JSON.readTree(f.jdbc.queryForObject("SELECT payload FROM nx_event_outbox WHERE event_name=?",String.class,event));
    }
    static void migrate(Fixture f) throws Exception { script(f,new FileSystemResource(MIGRATION)); }
    static void script(Fixture f,Resource resource) throws Exception {
        try(var c=f.source.getConnection()) { ScriptUtils.executeSqlScript(c,resource); }
    }
    static EventOutboxService realEvents(Fixture f) throws Exception {
        String ddl=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:java.util.List.of("nx_event_schema_revision","nx_event_schema_registry","nx_event_schema_property",
                "nx_event_outbox","nx_admin_operation_mutex")) {
            var match=java.util.regex.Pattern.compile("CREATE TABLE IF NOT EXISTS "+table+"\\s*\\([\\s\\S]*?;").matcher(ddl);
            assertTrue(match.find(),table); f.jdbc.execute(match.group());
        }
        f.jdbc.execute("CREATE TABLE nx_admin_event_lifecycle(event_name VARCHAR(128) PRIMARY KEY,lifecycle_state VARCHAR(32),is_deleted TINYINT DEFAULT 0)");
        f.jdbc.execute("ALTER TABLE nx_wallet_ledger ADD id BIGINT AUTO_INCREMENT UNIQUE,ADD balance_after DECIMAL(24,6),ADD remark VARCHAR(500),ADD updated_at DATETIME");
        f.jdbc.update("INSERT INTO nx_wallet_ledger(biz_no,user_id,biz_type,asset,direction,amount,balance_after,status,created_at) VALUES('FIXTURE-OPENING',71,'FIXTURE','USDT','IN',900,900,'POSTED',?)",NOW.minusDays(1));
        // Execute the exact production event registration section, without unrelated D2 permission changes.
        String d2=Files.readString(Path.of("scripts/migrations/20260720_d2_withdrawal_closure.sql"));
        script(f,new ByteArrayResource(d2.substring(d2.indexOf("INSERT INTO nx_event_schema_registry")).getBytes(StandardCharsets.UTF_8)));
        script(f,new FileSystemResource("scripts/migrations/20260727_l3_withdrawal_confirmed_event_closure.sql"));
        script(f,new FileSystemResource("scripts/migrations/20260727_d4_wallet_ledger_event_schema.sql"));
        f.jdbc.update("INSERT INTO nx_admin_event_lifecycle(event_name,lifecycle_state) VALUES('withdraw.confirmed','full'),('withdraw.refunded','full'),('wallet.ledger_posted','full')");
        var cfg=new Configuration(new Environment("real-bank-events",new SpringManagedTransactionFactory(),f.source));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(EventOutboxMapper.class); cfg.addMapper(TreasuryLedgerMapper.class);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg));
        var events=new EventOutboxService(session.getMapper(EventOutboxMapper.class),JSON,new OutboxProperties(),mock(A4RuntimePolicyService.class));
        var treasury=f.proxy(new MybatisTreasuryLedgerRepository(session.getMapper(TreasuryLedgerMapper.class),events));
        // Delegate existing fixture wiring to real services; validation and all writes remain in the same transaction.
        doAnswer(i->events.publish(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3)))
                .when(f.outbox).publish(anyString(),anyString(),anyString(),any());
        doAnswer(i->{ treasury.postLedgerEntry(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),
                i.getArgument(4),i.getArgument(5),i.getArgument(6),i.getArgument(7)); return null; })
                .when(f.ledger).postLedgerEntry(anyString(),anyLong(),anyString(),anyString(),anyString(),any(),anyString(),anyString());
        return events;
    }
}
