package ffdd.opsconsole.finance.application;

import static ffdd.opsconsole.finance.application.BankWithdrawalMySqlTest.*;
import static ffdd.opsconsole.finance.application.HdPayPayoutEventMySqlTest.*;
import static org.junit.jupiter.api.Assertions.*;

import ffdd.opsconsole.finance.infrastructure.MybatisWithdrawalOrderRepository;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;

/** Real finalizers + authoritative production event registry + isolated InnoDB transactions. */
@EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT", matches="true")
class AppBusinessWithdrawalEventMySqlTest {
    @Test
    void chainProgressAndTerminalEventsUseRealSchemaAndRollbackWithMoney() throws Exception {
        for (String outcome : List.of("CONFIRMED", "FAILED")) isolated(f -> {
            var events = setup(f);
            f.seed();
            f.jdbc.update("UPDATE nx_withdrawal_order SET chain='USDT-TRC20',target_address='test-chain-address',status='PROCESSING'");
            var finalizer = f.proxy(new WithdrawalPayoutFinalizer(f.payouts,f.audit,f.ledger,CLOCK,events));
            assertTrue(finalizer.submitted(f.payouts.payout(NO),123L,"provider"));
            assertEquals(1,count(f,"nx_event_outbox WHERE event_name='withdraw.processing'"));
            f.jdbc.execute("""
                    CREATE TRIGGER reject_terminal BEFORE INSERT ON nx_event_outbox FOR EACH ROW
                    BEGIN IF NEW.event_name IN ('withdraw.confirmed','withdraw.refunded') THEN
                      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test terminal event unavailable';
                    END IF; END
                    """);
            var sent = f.payouts.payout(NO);
            String tx = "CONFIRMED".equals(outcome) ? "a".repeat(64) : null;
            assertThrows(RuntimeException.class, () -> finalizer.terminal(sent,123L,"provider","CHAIN-EVENT","digest",outcome,tx,"test"));
            f.wallet("900","100");
            assertEquals("SENT",f.payouts.payout(NO).status());
            assertEquals(0,count(f,"nx_withdrawal_payout_ledger WHERE event_no='CHAIN-EVENT'"));
            f.jdbc.execute("DROP TRIGGER reject_terminal");
            assertTrue(finalizer.terminal(sent,123L,"provider","CHAIN-EVENT","digest",outcome,tx,"test"));
            assertTrue(finalizer.terminal(sent,123L,"provider","CHAIN-EVENT","digest",outcome,tx,"test"));
            String name = "CONFIRMED".equals(outcome) ? "withdraw.confirmed" : "withdraw.refunded";
            assertEquals(1,count(f,"nx_event_outbox WHERE event_name='"+name+"'"));
            assertEquals(71,payload(f,name).path("user_id").asLong());
            if ("FAILED".equals(outcome)) assertEquals("UNAVAILABLE",payload(f,name).path("risk_score_status").asText());
            f.wallet("CONFIRMED".equals(outcome)?"900":"1000","0");
        });
    }

    @Test
    void orphanHoldAndAccountFreezeRestorePersistOnlyActualTransitions() throws Exception {
        isolated(f -> {
            var events=setup(f);f.seed();
            f.jdbc.execute("ALTER TABLE nx_withdrawal_order ADD c2_previous_status VARCHAR(32),ADD c2_frozen_by_user_status INT DEFAULT 0");
            f.jdbc.update("UPDATE nx_withdrawal_order SET chain='USDT-TRC20',status='PROCESSING'");
            var finalizer=f.proxy(new WithdrawalPayoutFinalizer(f.payouts,f.audit,f.ledger,CLOCK,events));
            assertTrue(finalizer.orphaned(f.payouts.payout(NO),123L,"provider","unknown-provider-state"));
            assertFalse(finalizer.orphaned(f.payouts.payout(NO),123L,"provider","unknown-provider-state"));
            assertEquals(1,count(f,"nx_event_outbox WHERE event_name='withdraw.payout_held'"));
            var control=f.proxy(new FinanceWithdrawalControlFacadeAdapter(new MybatisWithdrawalOrderRepository(f.reviews),f.audit,events));
            assertEquals(1,control.freezePendingWithdrawalsForUser(71L,"fixture-freeze","admin"));
            assertEquals(0,control.freezePendingWithdrawalsForUser(71L,"fixture-freeze","admin"));
            assertEquals("FROZEN",f.payouts.payout(NO).status());
            assertEquals(1,control.restoreWithdrawalsFrozenByUserStatus(71L,"fixture-restore","admin"));
            assertEquals("TX_ORPHANED",f.payouts.payout(NO).status());
            assertEquals(1,count(f,"nx_event_outbox WHERE event_name='withdraw.account_frozen'"));
            assertEquals(1,count(f,"nx_event_outbox WHERE event_name='withdraw.account_restored'"));
            f.wallet("900","100");
        });
    }

    private static EventOutboxService setup(Fixture f) throws Exception {
        var events=realEvents(f);
        migrate(f);
        f.jdbc.execute("ALTER TABLE nx_user ADD language VARCHAR(16) DEFAULT 'en'");
        f.jdbc.execute("CREATE TABLE nx_user_preference(user_id BIGINT PRIMARY KEY,notify_system INT,updated_at DATETIME,is_deleted INT)");
        String schema=Files.readString(Path.of("scripts/schema.sql"));
        var notification=java.util.regex.Pattern.compile("CREATE TABLE IF NOT EXISTS nx_notification\\s*\\([\\s\\S]*?;").matcher(schema);
        assertTrue(notification.find());f.jdbc.execute(notification.group());
        script(f,new FileSystemResource("scripts/migrations/20261002_app_business_notifications.sql"));
        return events;
    }
}
