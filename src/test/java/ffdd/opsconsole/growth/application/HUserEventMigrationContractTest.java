package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HUserEventMigrationContractTest {
    private final String sql = readMigration();

    @Test
    void registersAllNineRequestedEventsAndFourH2LifecycleEventsAsServerAuthoritative() {
        assertThat(sql).contains(
                "trial.charge_attempted",
                "quest.claimed",
                "event.joined",
                "event.claimed",
                "daily.lucky_triggered",
                "daily.milestone_claimed",
                "milestone.fired",
                "voucher.claimed",
                "voucher.redeemed",
                "trial.started",
                "trial.cancelled",
                "trial.extended",
                "trial.redeemed",
                "is_server_authoritative=1",
                "INSERT INTO nx_event_domain_extension",
                "('milestone','milestone.fired'",
                "('voucher','voucher.claimed'",
                "current_revision=GREATEST(current_revision,103)");
    }

    @Test
    void registersEveryProducerPayloadFieldAndFailClosedRevision() {
        assertThat(sql).contains(
                "'trigger'", "'result'", "'amount_usdt'", "'payment_rail'",
                "'layer'", "'reward_nex'", "'multiplier'", "'rhythm_month'",
                "'campaign_id'", "'reward_type'", "'reward_amount'", "'badge_code'",
                "'day'", "'milestone_id'", "'threshold_usd'", "'lifetime_earnings_usd'",
                "'voucher_id'", "'surface'", "'audience'", "'order_id'", "'sku'", "'discount_usd'",
                "required_field=1", "registry_revision=VALUES(registry_revision)");
    }

    private static String readMigration() {
        try {
            return Files.readString(Path.of("scripts/migrations/20260722_h_user_event_closure.sql"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
