package ffdd.opsconsole;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeProfileEnvironmentPostProcessorTest {

    private final RuntimeProfileEnvironmentPostProcessor processor =
            new RuntimeProfileEnvironmentPostProcessor();

    @Test
    void acceptsExactlyOneDevOrProdProfile() {
        assertThatCode(() -> processor.validate(new MockEnvironment().withProperty(
                "spring.profiles.active", "dev"))).doesNotThrowAnyException();
        assertThatCode(() -> processor.validate(new MockEnvironment().withProperty(
                "spring.profiles.active", "prod"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsLegacyMissingUnknownMixedDuplicateCaseVariantAndEmptyProfiles() {
        for (String profiles : new String[] {
                "", "acceptance", "local-sandbox", "staging", "dev,prod",
                "prod,prod", "dev,dev", "DEV", "PROD", "dev,", ",prod"
        }) {
            MockEnvironment environment = new MockEnvironment();
            if (!profiles.isEmpty()) environment.setProperty("spring.profiles.active", profiles);
            assertThatThrownBy(() -> processor.validate(environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RUNTIME_PROFILE_FORBIDDEN");
        }
    }

    @Test
    void validatesTheRawPropertyBeforeSpringCanDeduplicateActiveProfiles() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.profiles.active", "prod,prod");
        environment.setActiveProfiles("prod", "prod");

        assertThatThrownBy(() -> processor.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RUNTIME_PROFILE_FORBIDDEN");
    }

    @Test
    void rejectsProfileGroupsOrIncludesThatExpandTheFinalActiveSet() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.profiles.active", "dev");
        environment.setActiveProfiles("dev", "prod");

        assertThatThrownBy(() -> processor.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNTIME_PROFILE_FORBIDDEN");
    }
}
