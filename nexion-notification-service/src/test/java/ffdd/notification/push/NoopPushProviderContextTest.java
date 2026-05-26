package ffdd.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

class NoopPushProviderContextTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(PushProviderScanConfig.class);

    @Test
    void registersNoopProviderAsDefaultPushProvider() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(PushProvider.class));
    }

    @Configuration
    @ComponentScan("ffdd.notification.push")
    static class PushProviderScanConfig {
    }
}
