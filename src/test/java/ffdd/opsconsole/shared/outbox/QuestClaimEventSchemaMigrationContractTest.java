package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QuestClaimEventSchemaMigrationContractTest {
    private static final String MIGRATION = "20260927_h3_quest_claim_event_schema.sql";

    @Test
    void startupRegistersBothClaimShapesWithoutReopeningOperatorRetirement() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations", MIGRATION));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(startup).contains(MIGRATION);
        assertThat(sql).contains(
                "'quest.claimed'", "'layer' property_name,'enum' property_type,1 required_field",
                "'reward_nex','number',1", "'multiplier','number',1",
                "'rhythm_month','number',1", "'instance_key','id',1",
                "'required_task_count','number',0", "current_revision=20260927",
                "status='ACTIVE' AND is_deleted=0", "nx_event_schema_property.is_deleted=0",
                "nx_event_schema_property.registry_revision<=20260927");
    }
}
