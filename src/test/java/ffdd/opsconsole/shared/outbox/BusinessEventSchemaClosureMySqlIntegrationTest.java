package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class BusinessEventSchemaClosureMySqlIntegrationTest {
    private CanonicalEventSchemaMySqlFixture db;

    record Contract(String event, Map<String, Object> payload, String numericField) {}
    static List<Contract> contracts() {
        Map<String, Object> task = Map.of("task_id", "TASK-1", "task_no", "CTA-1", "device_id", 7,
                "receipt_no", "CTR-1", "amount_usdt", new java.math.BigDecimal("1.234567"));
        return List.of(
                new Contract("auth.password_reset_completed", Map.of("revoked_session_count", 2), "revoked_session_count"),
                new Contract("capacity_replacement.completed", Map.of("tradein_no", "CPR-1", "source_device_id", 7,
                        "target_device_id", 8, "order_no", "CPO-1", "wallet_debit_usdt", 500,
                        "operation", "CAPACITY_REPLACE"), "wallet_debit_usdt"),
                new Contract("task.completed", task, "amount_usdt"),
                new Contract("earnings.credited", task, "amount_usdt"),
                new Contract("genesis.emission_paid", Map.of("holding_no", "GH-1", "amount_usdt", 2,
                        "rate_applied", 1, "paid_at", "2026-08-31T12:00:00"), "amount_usdt"),
                new Contract("admin.staking_pool_restored", Map.of("tier_key", "T1", "trigger_basis", "INCIDENT_RESOLVED",
                        "review_conclusion", "Verified incident resolved", "reason", "Reviewed restore request",
                        "operator", "test-admin", "restoration_domain", "J1"), null));
    }

    @BeforeEach void setup() throws Exception { db = new CanonicalEventSchemaMySqlFixture(); }
    @AfterEach void cleanup() throws Exception { if (db != null) db.close(); }

    @ParameterizedTest @MethodSource("contracts")
    void migrationRegistersEveryRequiredFieldAndStrictValidationRemainsEnabled(Contract c) throws Exception {
        assertThatThrownBy(() -> publish(c, c.payload())).hasMessage("A4_SCHEMA_NOT_REGISTERED");
        db.migrate();
        db.migrate();
        assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_schema_registry WHERE event_name=?",
                Integer.class, c.event())).isEqualTo(1);
        assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_schema_property p JOIN nx_event_schema_registry s"
                + " ON p.schema_id=s.id WHERE s.event_name=? AND p.registry_revision=316 AND p.required_field=1"
                + " AND p.is_deleted=0", Integer.class, c.event())).isEqualTo(c.payload().size());
        assertThat(publish(c, c.payload())).isNotBlank();
        for (String field : c.payload().keySet()) {
            Map<String, Object> missing = new LinkedHashMap<>(c.payload());
            missing.remove(field);
            assertThatThrownBy(() -> publish(c, missing)).hasMessage("A4_SCHEMA_REQUIRED_PROPERTY_MISSING");
            missing.put(field, null);
            assertThatThrownBy(() -> publish(c, missing)).hasMessage("A4_SCHEMA_REQUIRED_PROPERTY_MISSING");
        }
        Map<String, Object> extra = new LinkedHashMap<>(c.payload());
        extra.put("unknown_business_field", "reject");
        assertThatThrownBy(() -> publish(c, extra)).hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
        Map<String, Object> wrong = new LinkedHashMap<>(c.payload());
        if (c.numericField() != null) wrong.put(c.numericField(), "123.4");
        else wrong.put("review_conclusion", 123);
        assertThatThrownBy(() -> publish(c, wrong)).hasMessage("A4_SCHEMA_PROPERTY_TYPE_MISMATCH");
        assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isEqualTo(1);
    }

    @Test void rerunPreservesOperatorStateAndNeverDowngradesVersions() throws Exception {
        db.migrate();
        db.jdbc().update("UPDATE nx_admin_event_lifecycle SET lifecycle_state='disabled',version=9 WHERE event_name='auth.password_reset_completed'");
        db.jdbc().update("UPDATE nx_admin_event_lifecycle SET lifecycle_state='gray',version=8 WHERE event_name='capacity_replacement.completed'");
        db.jdbc().update("UPDATE nx_event_schema_registry SET status='RETIRED' WHERE event_name='task.completed'");
        db.jdbc().update("UPDATE nx_event_schema_registry SET is_deleted=1 WHERE event_name='earnings.credited'");
        db.jdbc().update("UPDATE nx_event_schema_registry SET current_revision=317,reason='future-version' WHERE event_name='genesis.emission_paid'");
        db.jdbc().update("UPDATE nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id"
                + " SET p.registry_revision=317,p.property_type='json' WHERE s.event_name='genesis.emission_paid'");
        db.jdbc().update("UPDATE nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id"
                + " SET p.is_deleted=1 WHERE s.event_name='admin.staking_pool_restored' AND p.property_name='review_conclusion'");
        db.jdbc().update("UPDATE nx_event_schema_revision SET current_revision=800 WHERE id=1");
        db.migrate();
        assertThat(db.jdbc().queryForObject("SELECT lifecycle_state FROM nx_admin_event_lifecycle WHERE event_name='auth.password_reset_completed'", String.class)).isEqualTo("disabled");
        assertThat(db.jdbc().queryForObject("SELECT version FROM nx_admin_event_lifecycle WHERE event_name='auth.password_reset_completed'", Integer.class)).isEqualTo(9);
        assertThat(db.jdbc().queryForObject("SELECT lifecycle_state FROM nx_admin_event_lifecycle WHERE event_name='capacity_replacement.completed'", String.class)).isEqualTo("gray");
        assertThat(db.jdbc().queryForObject("SELECT status FROM nx_event_schema_registry WHERE event_name='task.completed'", String.class)).isEqualTo("RETIRED");
        assertThat(db.jdbc().queryForObject("SELECT is_deleted FROM nx_event_schema_registry WHERE event_name='earnings.credited'", Integer.class)).isEqualTo(1);
        assertThat(db.jdbc().queryForObject("SELECT current_revision FROM nx_event_schema_registry WHERE event_name='genesis.emission_paid'", Integer.class)).isEqualTo(317);
        assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id WHERE s.event_name='genesis.emission_paid' AND p.property_type='json' AND p.registry_revision=317", Integer.class)).isEqualTo(4);
        assertThat(db.jdbc().queryForObject("SELECT p.is_deleted FROM nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id WHERE s.event_name='admin.staking_pool_restored' AND p.property_name='review_conclusion'", Integer.class)).isEqualTo(1);
        assertThat(db.jdbc().queryForObject("SELECT current_revision FROM nx_event_schema_revision WHERE id=1", Integer.class)).isEqualTo(800);
        assertThatThrownBy(() -> publish(contracts().get(0), contracts().get(0).payload())).hasMessage("A4_EVENT_LIFECYCLE_BLOCKED_DISABLED");
        assertThatThrownBy(() -> publish(contracts().get(5), contracts().get(5).payload())).hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
    }

    @Test void ordinaryStartupRequiresThisMigration() throws Exception {
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(runner).contains(CanonicalEventSchemaMySqlFixture.MIGRATION);
        assertThat(runner.indexOf(CanonicalEventSchemaMySqlFixture.MIGRATION))
                .isGreaterThan(runner.indexOf("20260831_app_device_command_event_schema.sql"));
    }

    @Test void migrationUpgradesOlderActiveContractsButPreservesRestrictedLifecycleRows() throws Exception {
        db.migrate();
        db.jdbc().update("UPDATE nx_event_schema_registry SET current_revision=315 WHERE event_name='task.completed'");
        db.jdbc().update("UPDATE nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id"
                + " SET p.registry_revision=315,p.property_type='string',p.required_field=0 WHERE s.event_name='task.completed'");
        db.jdbc().update("UPDATE nx_admin_event_lifecycle SET lifecycle_state='new',version=4 WHERE event_name='task.completed'");
        db.jdbc().update("UPDATE nx_admin_event_lifecycle SET lifecycle_state='disabled',version=5,is_deleted=1 WHERE event_name='earnings.credited'");
        db.migrate();
        assertThat(db.jdbc().queryForObject("SELECT current_revision FROM nx_event_schema_registry WHERE event_name='task.completed'", Integer.class)).isEqualTo(316);
        assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id WHERE s.event_name='task.completed' AND p.registry_revision=316 AND p.required_field=1", Integer.class)).isEqualTo(5);
        assertThat(db.jdbc().queryForObject("SELECT p.property_type FROM nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id WHERE s.event_name='task.completed' AND p.property_name='amount_usdt'", String.class)).isEqualTo("number");
        assertThat(db.jdbc().queryForObject("SELECT lifecycle_state FROM nx_admin_event_lifecycle WHERE event_name='task.completed'", String.class)).isEqualTo("new");
        assertThat(db.jdbc().queryForObject("SELECT version FROM nx_admin_event_lifecycle WHERE event_name='task.completed'", Integer.class)).isEqualTo(4);
        assertThat(db.jdbc().queryForObject("SELECT is_deleted FROM nx_admin_event_lifecycle WHERE event_name='earnings.credited'", Integer.class)).isEqualTo(1);
        assertThat(db.jdbc().queryForObject("SELECT lifecycle_state FROM nx_admin_event_lifecycle WHERE event_name='earnings.credited'", String.class)).isEqualTo("disabled");
    }

    private String publish(Contract c, Map<String, Object> payload) {
        return db.outbox().publishUserEvent("SCHEMA_TEST", "CASE-1", c.event(), 42L, "P1", 0, "2026-W36", payload);
    }
}
