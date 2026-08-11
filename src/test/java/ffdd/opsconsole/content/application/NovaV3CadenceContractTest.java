package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NovaV3CadenceContractTest {
    @Test
    void v3ChannelsHaveDurableServerCadenceSeedsAndRuntimeScheduling() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/content/mapper/NovaMapper.java"));
        String scheduler = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/content/application/NovaSocialRuntimeScheduler.java"));

        assertThat(mapper)
                .contains("'team_event', '团队事件', 'a4:commission.paid', '90 s', '90 s'")
                .contains("'staking_event', '质押事件', 'a4:staking.opened', '240 s', '300 s'")
                .contains("'market_event', '市场事件', 'a4:market.curve_advanced', '360 s', '420 s'");
        assertThat(scheduler).contains("businessRuntimeService.channelKeys()")
                .contains("businessRuntimeService.runScheduledChannel(channel)");
    }
}
