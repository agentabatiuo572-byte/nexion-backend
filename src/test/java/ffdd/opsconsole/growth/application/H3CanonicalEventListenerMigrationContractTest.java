package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class H3CanonicalEventListenerMigrationContractTest {
    @Test
    void migrationOwnsDurableBindingsAndStableMissionsForAllTrustedProducerFamilies() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts/migrations/20260722_h3_canonical_event_listener.sql"));

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS nx_growth_quest_event_binding");
        assertThat(sql).contains("checkout.started", "H8_REFERRAL_REWARD_SETTLED",
                "LEARNING_COURSE_COMPLETED", "admin.device_activated", "COMMISSION_UNLOCKED");
        assertThat(sql).contains("'ORDER'", "'REFERRAL'", "'LEARNING'", "'DEVICE'", "'COMMISSION'");
        assertThat(sql).contains("H3_FIRST_ORDER_STARTED", "H3_REFERRAL_SETTLED",
                "H3_LEARNING_COMPLETED", "H3_DEVICE_ACTIVATED", "H3_COMMISSION_UNLOCKED");
        assertThat(sql).contains("UNIQUE KEY uk_growth_quest_event_binding");
    }
}
