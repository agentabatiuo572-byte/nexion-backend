package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class ProductionSupportPathGuardTest {
    private final Environment environment = mock(Environment.class);
    private final SupportAcceptanceSandboxMapper mapper = mock(SupportAcceptanceSandboxMapper.class);
    private final ProductionSupportPathGuard guard = new ProductionSupportPathGuard(environment, mapper);

    @Test
    void acceptanceProfileCannotReachAProductionSupportPath() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        assertThatThrownBy(() -> guard.requireAllowed(7L)).hasMessageContaining("SUPPORT_PRODUCTION_PATH_FORBIDDEN");
    }

    @Test
    void sandboxMarkedUserCannotReachAProductionSupportPathInTheDefaultProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(mapper.sandboxUser(7L)).thenReturn(1);
        assertThatThrownBy(() -> guard.requireAllowed(7L)).hasMessageContaining("SUPPORT_PRODUCTION_PATH_FORBIDDEN");
    }

    @Test
    void ordinaryUserKeepsProductionSupportAccessOutsideIsolatedProfiles() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(mapper.sandboxUser(7L)).thenReturn(0);
        assertThatCode(() -> guard.requireAllowed(7L)).doesNotThrowAnyException();
    }

    @Test
    void isolatedProfilesRejectOfficialOpsSupportWritesBeforeTheirServices() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        assertThatThrownBy(guard::requireOpsWriteAllowed).hasMessageContaining("SUPPORT_PRODUCTION_PATH_FORBIDDEN");
    }

    @Test
    void onlyDefaultOrExplicitProductionCanRunSharedSupportAutomation() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev", "prod"});
        assertThat(guard.productionSupportAutomationAllowed()).isFalse();
        when(environment.getActiveProfiles()).thenReturn(new String[] {"unknown"});
        assertThat(guard.productionSupportAutomationAllowed()).isFalse();
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        assertThat(guard.productionSupportAutomationAllowed()).isTrue();
    }
}
