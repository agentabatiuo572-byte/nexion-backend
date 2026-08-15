package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountDeletionStateMachineTest {
    @Test
    void onlyAllowsTheAuthoritativeLifecycle() {
        assertThat(AccountDeletionStateMachine.canTransition("REQUESTED", "IN_REVIEW")).isTrue();
        assertThat(AccountDeletionStateMachine.canTransition("IN_REVIEW", "COMPLETED")).isTrue();
        assertThat(AccountDeletionStateMachine.canTransition("REQUESTED", "CANCELLED")).isTrue();
        assertThat(AccountDeletionStateMachine.canTransition("BLOCKED", "IN_REVIEW")).isTrue();
        assertThat(AccountDeletionStateMachine.canTransition("BLOCKED", "CANCELLED")).isTrue();
        assertThat(AccountDeletionStateMachine.canTransition("BLOCKED", "COMPLETED")).isFalse();
        assertThat(AccountDeletionStateMachine.canTransition("COMPLETED", "CANCELLED")).isFalse();
    }

    @Test
    void rejectsUnknownStatesAndMissingReasonsForSafetyCriticalTransitions() {
        assertThatThrownBy(() -> AccountDeletionStateMachine.requireTransition("REQUESTED", "BLOCKED", ""))
                .hasMessage("ACCOUNT_DELETION_REASON_REQUIRED");
        assertThatThrownBy(() -> AccountDeletionStateMachine.requireTransition("REQUESTED", "COMPLETED", "reviewed"))
                .hasMessage("ACCOUNT_DELETION_INVALID_STATE_TRANSITION");
        assertThatThrownBy(() -> AccountDeletionStateMachine.requireTransition("NOT_A_STATE", "IN_REVIEW", "reviewed"))
                .hasMessage("ACCOUNT_DELETION_INVALID_STATE");
    }
}
