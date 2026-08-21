package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class LearningAcceptanceSandboxGateTest {

    private final Environment environment = mock(Environment.class);
    private final LearningAcceptanceSandboxGate gate = new LearningAcceptanceSandboxGate(environment);

    @Test
    void enablesSandboxFactsOnlyForOneDeclaredIsolatedProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});

        assertThat(gate.enabled("SANDBOX")).isTrue();
        assertThat(gate.enabled("PRODUCTION")).isFalse();
    }

    @Test
    void rejectsSandboxFactsWhenProfilesAreMixedOrNotApproved() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev", "prod"});

        assertThat(gate.enabled("SANDBOX")).isFalse();
        assertThatThrownBy(() -> gate.requireEnabled("SANDBOX"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503))
                .hasMessage("LEARNING_ACCEPTANCE_PROFILE_INVALID");
    }

    @Test
    void controlledAcceptanceProfileRejectsNormalUsersInsteadOfFallingThroughToProductionFacts() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});

        assertThatThrownBy(() -> gate.requireEnabled("PRODUCTION"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LEARNING_ACCEPTANCE_SANDBOX_USER_REQUIRED");
    }

    @Test
    void mixedOrUnknownProfilesFailClosedForBothSandboxAndProductionUsers() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev", "prod"});

        assertThatThrownBy(() -> gate.requireEnabled("PRODUCTION"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503))
                .hasMessage("LEARNING_ACCEPTANCE_PROFILE_INVALID");
        assertThatThrownBy(() -> gate.requireEnabled("SANDBOX"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503))
                .hasMessage("LEARNING_ACCEPTANCE_PROFILE_INVALID");

        when(environment.getActiveProfiles()).thenReturn(new String[] {"staging"});
        assertThatThrownBy(() -> gate.requireEnabled("PRODUCTION"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503))
                .hasMessage("LEARNING_ACCEPTANCE_PROFILE_INVALID");
    }

    @Test
    void productionProfileIsEnabledWhileLegacyOrMissingProfilesFailClosed() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        assertThatCode(() -> gate.requireEnabled("PRODUCTION")).doesNotThrowAnyException();

        when(environment.getActiveProfiles()).thenReturn(new String[] {"default"});
        assertThatThrownBy(() -> gate.requireEnabled("PRODUCTION"))
                .isInstanceOf(BizException.class)
                .hasMessage("LEARNING_ACCEPTANCE_PROFILE_INVALID");

        when(environment.getActiveProfiles()).thenReturn(new String[] {});
        assertThatThrownBy(() -> gate.requireEnabled("PRODUCTION"))
                .isInstanceOf(BizException.class)
                .hasMessage("LEARNING_ACCEPTANCE_PROFILE_INVALID");
    }

    @Test
    void developmentBlocksProductionCatalogCommandsWhileOnlyProdRemainsAvailable() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        assertThatThrownBy(gate::requireProductionMutationAllowed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LEARNING_ACCEPTANCE_PRODUCTION_MUTATION_FORBIDDEN");

        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        assertThatCode(gate::requireProductionMutationAllowed).doesNotThrowAnyException();
        when(environment.getActiveProfiles()).thenReturn(new String[] {"default"});
        assertThatThrownBy(gate::requireProductionMutationAllowed)
                .isInstanceOf(BizException.class)
                .hasMessage("LEARNING_ACCEPTANCE_PROFILE_INVALID");
        when(environment.getActiveProfiles()).thenReturn(new String[] {});
        assertThatThrownBy(gate::requireProductionMutationAllowed)
                .isInstanceOf(BizException.class)
                .hasMessage("LEARNING_ACCEPTANCE_PROFILE_INVALID");
    }
}
