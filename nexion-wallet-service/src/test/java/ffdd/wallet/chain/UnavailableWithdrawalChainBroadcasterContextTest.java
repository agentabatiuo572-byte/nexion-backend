package ffdd.wallet.chain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

class UnavailableWithdrawalChainBroadcasterContextTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(WithdrawalChainBroadcasterScanConfig.class);

    @Test
    void registersUnavailableBroadcasterAsDefaultFallback() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(WithdrawalChainBroadcaster.class));
    }

    @Configuration
    @ComponentScan("ffdd.wallet.chain")
    static class WithdrawalChainBroadcasterScanConfig {
    }
}
