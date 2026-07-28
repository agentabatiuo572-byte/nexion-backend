package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class D1TopupChannelParityMigrationContractTest {
    @Test
    void migrationPinsFiveRealRailsAndTheInternationalCardBoundaries() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260728_d1_topup_channel_parity.sql"));

        assertThat(migration)
                .contains("finance.topup.channel.trc20.fee")
                .contains("finance.topup.channel.bep20.fee")
                .contains("finance.topup.channel.erc20.fee")
                .contains("finance.topup.channel.vietqr.fee")
                .contains("finance.topup.channel.card.min_amount','30'")
                .contains("finance.topup.channel.card.max_amount','5000'")
                .contains("finance.topup.channel.btc.%")
                .contains("finance.topup.channel.eth.%")
                .contains("'finance-topup'")
                .contains("config_value=nx_config_item.config_value")
                .contains("status=nx_config_item.status")
                .contains("is_deleted=nx_config_item.is_deleted")
                .doesNotContain("config_value=VALUES(config_value)")
                .doesNotContain("status=1,\n  is_deleted=0");
    }
}
