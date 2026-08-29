package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class BehaviorAnalyticsFixtureInitializerTest {
    private final BehaviorAnalyticsService service = mock(BehaviorAnalyticsService.class);

    @Test
    void disabledFixtureNeverWrites() throws Exception {
        new BehaviorAnalyticsFixtureInitializer(service, false, 0L).run(null);
        verify(service, never()).ingestFixture(eq(0L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enabledFixtureRequiresAnExplicitSyntheticUser() {
        assertThatThrownBy(() -> new BehaviorAnalyticsFixtureInitializer(service, true, 0L).run(null))
                .isInstanceOfSatisfying(BizException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.getMessage())
                                .isEqualTo("L6_FIXTURE_USER_ID_REQUIRED"));
    }

    @Test
    void enabledFixtureFeedsBothCanonicalEventKindsThroughIngest() throws Exception {
        new BehaviorAnalyticsFixtureInitializer(service, true, 9000001L).run(null);
        verify(service, times(1)).ingestFixture(eq(9000001L), argThat(request ->
                "app.page_viewed".equals(request.eventName())));
        verify(service, times(1)).ingestFixture(eq(9000001L), argThat(request ->
                "app.element_clicked".equals(request.eventName())));
    }

    @Test
    void initializerBeanIsRegisteredOnlyForOneStrictIsolatedProfile() {
        for (String profile : new String[]{"test"}) {
            context(profile).run(context -> assertThat(context)
                    .hasSingleBean(BehaviorAnalyticsFixtureInitializer.class));
        }

        for (String profiles : new String[]{
                "prod", "dev", "development", "production,acceptance", "production,test",
                "test,acceptance", "acceptance,development", "local-sandbox,development"}) {
            context(profiles).run(context -> assertThat(context)
                    .doesNotHaveBean(BehaviorAnalyticsFixtureInitializer.class));
        }
    }

    private static ApplicationContextRunner context(String activeProfiles) {
        return new ApplicationContextRunner()
                .withUserConfiguration(FixtureContext.class)
                .withInitializer(context -> context.getEnvironment()
                        .setActiveProfiles(activeProfiles.split(",")));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(BehaviorAnalyticsFixtureInitializer.class)
    static class FixtureContext {
        @Bean
        BehaviorAnalyticsService behaviorAnalyticsService() {
            return mock(BehaviorAnalyticsService.class);
        }
    }
}
