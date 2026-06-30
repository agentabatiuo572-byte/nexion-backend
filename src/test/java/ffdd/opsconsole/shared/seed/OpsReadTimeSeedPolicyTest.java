package ffdd.opsconsole.shared.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;

class OpsReadTimeSeedPolicyTest {
    @Test
    void disablesReadTimeSeedsWhenConfiguredOff() {
        OpsReadTimeSeedPolicy policy = OpsReadTimeSeedPolicy.disabledForDirectConstruction();

        assertThat(policy.enabled()).isFalse();
    }

    @Test
    void keepsLegacyDirectConstructionTestsSeeded() {
        OpsReadTimeSeedPolicy policy = OpsReadTimeSeedPolicy.enabledForDirectConstruction();

        assertThat(policy.enabled()).isTrue();
    }

    @Test
    void directConstructionCanOptIntoProductionLikeReadModel() {
        OpsReadTimeSeedPolicy policy = OpsReadTimeSeedPolicy.disabledForDirectConstruction();

        assertThat(policy.enabled()).isFalse();
    }

    @Test
    void springDefaultDisablesReadTimeSeeds() {
        new ApplicationContextRunner()
                .withBean(OpsReadTimeSeedPolicy.class)
                .run(context -> assertThat(context.getBean(OpsReadTimeSeedPolicy.class).enabled()).isFalse());
    }

    @Test
    void springPropertyCannotEnableReadTimeSeeds() {
        new ApplicationContextRunner()
                .withBean(OpsReadTimeSeedPolicy.class)
                .withPropertyValues("nexion.ops.seed.read-time-enabled=true")
                .run(context -> assertThat(context.getBean(OpsReadTimeSeedPolicy.class).enabled()).isFalse());
    }
}
