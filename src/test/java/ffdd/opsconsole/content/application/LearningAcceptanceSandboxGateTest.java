package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class LearningAcceptanceSandboxGateTest {

    private final Environment environment = mock(Environment.class);
    private final LearningAcceptanceSandboxGate gate = new LearningAcceptanceSandboxGate(environment);

    @Test
    void enablesSandboxFactsOnlyForOneDeclaredIsolatedProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"acceptance"});

        assertThat(gate.enabled("SANDBOX")).isTrue();
        assertThat(gate.enabled("PRODUCTION")).isFalse();
    }

    @Test
    void rejectsSandboxFactsWhenProfilesAreMixedOrNotApproved() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"acceptance", "production"});

        assertThat(gate.enabled("SANDBOX")).isFalse();
        assertThatThrownBy(() -> gate.requireEnabled("SANDBOX"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LEARNING_ACCEPTANCE_PROFILE_INVALID");
    }

    @Test
    void controlledAcceptanceProfileRejectsNormalUsersInsteadOfFallingThroughToProductionFacts() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"acceptance"});

        assertThatThrownBy(() -> gate.requireEnabled("PRODUCTION"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LEARNING_ACCEPTANCE_SANDBOX_USER_REQUIRED");
    }

    @Test
    void mixedOrUnknownProfilesFailClosedForBothSandboxAndProductionUsers() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"acceptance", "production"});

        assertThatThrownBy(() -> gate.requireEnabled("PRODUCTION"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LEARNING_ACCEPTANCE_PROFILE_INVALID");
        assertThatThrownBy(() -> gate.requireEnabled("SANDBOX"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LEARNING_ACCEPTANCE_PROFILE_INVALID");

        when(environment.getActiveProfiles()).thenReturn(new String[] {"staging"});
        assertThatThrownBy(() -> gate.requireEnabled("PRODUCTION"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LEARNING_ACCEPTANCE_PROFILE_INVALID");
    }

    @Test
    void productionProfileAndDefaultProfileKeepProductionUsersEnabled() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"production"});
        assertThatCode(() -> gate.requireEnabled("PRODUCTION")).doesNotThrowAnyException();

        when(environment.getActiveProfiles()).thenReturn(new String[] {});
        assertThatCode(() -> gate.requireEnabled("PRODUCTION")).doesNotThrowAnyException();
    }

    @Test
    void strictProfileBlocksFormalProductionCatalogCommandsWhileDefaultAndProductionRemainAvailable() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"local-sandbox"});
        assertThatThrownBy(gate::requireProductionMutationAllowed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LEARNING_ACCEPTANCE_PRODUCTION_MUTATION_FORBIDDEN");

        when(environment.getActiveProfiles()).thenReturn(new String[] {"production"});
        assertThatCode(gate::requireProductionMutationAllowed).doesNotThrowAnyException();
        when(environment.getActiveProfiles()).thenReturn(new String[] {});
        assertThatCode(gate::requireProductionMutationAllowed).doesNotThrowAnyException();
    }
}
