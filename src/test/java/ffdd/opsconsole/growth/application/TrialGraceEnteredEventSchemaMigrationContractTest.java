package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TrialGraceEnteredEventSchemaMigrationContractTest {
    static final String MIGRATION = "20260831_trial_grace_entered_event_schema.sql";

    @Test
    void registersTheExactServerPayloadInOneTransaction() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations", MIGRATION))
                .replaceAll("(?m)^\\s*--.*$", "").replaceAll("\\s+", " ");
        assertThat(sql).contains("START TRANSACTION;", "COMMIT;",
                "'trial.grace_entered'", "'AppTrialLifecycleService'",
                "'grace_days'", "'shadow_usdt'", "'shadow_nex'", "'number'",
                "nx_event_schema_property", "nx_event_schema_revision",
                "nx_admin_event_lifecycle", "'full'", "GREATEST(current_revision,314)");
        assertThat(sql).contains(
                "WHERE event_name='trial.grace_entered' AND current_revision<314 AND status='ACTIVE' AND is_deleted=0",
                "WHERE s.event_name='trial.grace_entered' AND s.current_revision=314 AND s.status='ACTIVE' AND s.is_deleted=0",
                "property_type=IF(nx_event_schema_property.is_deleted=0 AND registry_revision<=314,VALUES(property_type),property_type)",
                "required_field=IF(nx_event_schema_property.is_deleted=0 AND registry_revision<=314,1,required_field)",
                "registry_revision=IF(nx_event_schema_property.is_deleted=0,GREATEST(registry_revision,314),registry_revision)");
        int previous = -1;
        for (String statement : new String[] {"START TRANSACTION;", "INSERT INTO nx_event_schema_registry",
                "UPDATE nx_event_schema_registry", "INSERT INTO nx_event_schema_property",
                "INSERT INTO nx_admin_event_lifecycle", "INSERT INTO nx_event_schema_revision", "COMMIT;"}) {
            int position = sql.indexOf(statement);
            assertThat(position).as("execution order of %s", statement).isGreaterThan(previous);
            previous = position;
        }
    }

    @Test
    void controlledStartupAppliesTheMigrationAfterA4LifecyclePrerequisites() throws Exception {
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(runner).contains(MIGRATION);
        assertThat(runner.indexOf(MIGRATION))
                .isGreaterThan(runner.indexOf("20260810_ab_pending_closure.sql"));
    }
}
