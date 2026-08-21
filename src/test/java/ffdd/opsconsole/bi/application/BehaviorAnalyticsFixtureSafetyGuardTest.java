package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class BehaviorAnalyticsFixtureSafetyGuardTest {
    @ParameterizedTest(name = "fixture is rejected for profiles {0}")
    @MethodSource("forbiddenProfileSets")
    void fixtureHardFailsOutsideOneStrictIsolatedProfile(String label, String[] profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);

        assertThatThrownBy(()->new BehaviorAnalyticsFixtureSafetyGuard(true,environment).afterPropertiesSet())
                .isInstanceOfSatisfying(BizException.class,error ->
                        org.assertj.core.api.Assertions.assertThat(error.getMessage()).isEqualTo("L6_FIXTURE_PROFILE_FORBIDDEN"));
    }

    @ParameterizedTest(name = "fixture is allowed for isolated profile {0}")
    @MethodSource("allowedProfileSets")
    void oneStrictIsolatedProfileMayEnableExplicitFixture(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);

        assertThatCode(()->new BehaviorAnalyticsFixtureSafetyGuard(true,environment).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void disabledFixtureDoesNotRestrictNormalApplicationProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "dev");

        assertThatCode(() -> new BehaviorAnalyticsFixtureSafetyGuard(false, environment).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void mixedProductionContextFailsDuringBeanInitialization() {
        new ApplicationContextRunner()
                .withBean(BehaviorAnalyticsFixtureSafetyGuard.class)
                .withPropertyValues("nexion.analytics.fixture.enabled=true")
                .withInitializer(context -> context.getEnvironment()
                        .setActiveProfiles("prod", "dev"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BizException.class)
                            .hasRootCauseMessage("L6_FIXTURE_PROFILE_FORBIDDEN");
                });
    }

    private static Stream<String> allowedProfileSets() {
        return Stream.of("test", "dev", "dev");
    }

    private static Stream<Arguments> forbiddenProfileSets() {
        return Stream.of(
                Arguments.of("none", new String[0]),
                Arguments.of("prod", new String[]{"prod"}),
                Arguments.of("unknown", new String[]{"development"}),
                Arguments.of("production+acceptance", new String[]{"prod", "dev"}),
                Arguments.of("production+test", new String[]{"prod", "test"}),
                Arguments.of("production+local-sandbox", new String[]{"prod", "dev"}),
                Arguments.of("test+acceptance", new String[]{"test", "dev"}),
                Arguments.of("acceptance+unknown", new String[]{"dev", "development"}),
                Arguments.of("local-sandbox+unknown", new String[]{"dev", "development"}));
    }
}
