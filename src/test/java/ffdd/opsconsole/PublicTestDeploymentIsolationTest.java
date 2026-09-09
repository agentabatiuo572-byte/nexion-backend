package ffdd.opsconsole;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class PublicTestDeploymentIsolationTest {
    private static final List<String> LOCAL_FIXTURE_BEANS = List.of(
            "ffdd.opsconsole.auth.captcha.IsolatedTestCaptchaTicketVerifier",
            "ffdd.opsconsole.auth.web.OAuthDevelopmentPasskeyController",
            "ffdd.opsconsole.finance.web.DevelopmentD2LifecycleController",
            "ffdd.opsconsole.finance.application.DevelopmentD2LifecycleService",
            "ffdd.opsconsole.device.application.DevelopmentTaskPriceHistoryInitializer",
            "ffdd.opsconsole.team.application.DevelopmentRankHowPolicyInitializer",
            "ffdd.opsconsole.content.application.DevelopmentCommissionHowInitializer",
            "ffdd.opsconsole.finance.application.DevelopmentWithdrawalSettlementExecutor");

    @Test
    void publicTestDeploymentDoesNotRegisterLocalFixturesOrCaptchaBypass() throws Exception {
        for (String className : LOCAL_FIXTURE_BEANS) {
            assertThat(registered(className, "dev", true)).as(className).isFalse();
        }
    }

    @Test
    void localDevelopmentKeepsItsExistingBehaviorWhenPublicTestModeIsAbsent() throws Exception {
        for (String className : LOCAL_FIXTURE_BEANS) {
            assertThat(registered(className, "dev", false)).as(className).isTrue();
        }
    }

    @Test
    void productionNeverRegistersDevelopmentFixtures() throws Exception {
        for (String className : LOCAL_FIXTURE_BEANS) {
            assertThat(registered(className, "prod", false)).as(className).isFalse();
            assertThat(registered(className, "prod", true)).as(className).isFalse();
        }
    }

    private boolean registered(String className, String profile, boolean publicTest) throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        if (publicTest) environment.setProperty("nexion.deployment.public-test", "true");
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.setEnvironment(environment);
            new AnnotatedBeanDefinitionReader(context).registerBean(Class.forName(className), "fixture");
            return context.containsBeanDefinition("fixture");
        }
    }
}
