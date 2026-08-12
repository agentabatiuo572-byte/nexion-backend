package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class H8AcceptanceSandboxRunScopeTest {

    @Test
    void aLegalRunFromAnotherFixtureIsAConflictNotAnAlternateScope() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("NEXION_ACCEPTANCE_RUN_ID", "RUN-H8-SERVER");
        H8AcceptanceSandboxRunScope scope = new H8AcceptanceSandboxRunScope(environment);

        assertThatThrownBy(() -> scope.requireCurrentRunId("RUN-H8-OTHER"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_SANDBOX_RUN_ID_MISMATCH");
        assertThat(scope.requireCurrentRunId("RUN-H8-SERVER")).isEqualTo("RUN-H8-SERVER");
    }
}
