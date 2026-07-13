package ffdd.opsconsole.janus.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JanusTransitionPolicyTest {
    private final JanusTransitionPolicy policy = new JanusTransitionPolicy();

    @Test
    void blocksUndefinedTransitionsAndEnforcesRoleGate() {
        assertThat(policy.validate("ACTIVATED", "NEW", JanusRole.ADMIN, null, null, "strong_single").code())
                .isEqualTo("ILLEGAL_STATUS_TRANSITION");
        assertThat(policy.validate("NEW", "MANUAL_FORCED", JanusRole.OPERATOR, "default", null, "strong_single").code())
                .isEqualTo("ROLE_FORBIDDEN");
    }

    @Test
    void requiresRemoteTargetExpiryAndStrongConfirmationWhereSpecified() {
        assertThat(policy.validate("NEW", "MANUAL_FORCED", JanusRole.SENIOR_OPERATOR, null, null, "strong_single").code())
                .isEqualTo("REMOTE_TARGET_REQUIRED");
        assertThat(policy.validate("NEW", "MANUAL_FORCED", JanusRole.SENIOR_OPERATOR, "untrusted", null, "strong_single").code())
                .isEqualTo("REMOTE_TARGET_INVALID");
        assertThat(policy.validate("NEW", "MANUAL_FORCED", JanusRole.SENIOR_OPERATOR, "promo ", null, "strong_single").code())
                .isEqualTo("REMOTE_TARGET_INVALID");
        assertThat(policy.validate("NEW", "MANUAL_FORCED", JanusRole.SENIOR_OPERATOR, "default", null, "standard").code())
                .isEqualTo("STRONG_CONFIRMATION_REQUIRED");
        assertThat(policy.validate("NEW", "MANUAL_HOLD", JanusRole.OPERATOR, null, null, "standard").code())
                .isEqualTo("EXPIRE_AT_REQUIRED");
    }

    @Test
    void acceptsDocumentedManualTransitions() {
        assertThat(policy.validate("RECOMMENDED", "HIT", JanusRole.OPERATOR, null, null, "standard").allowed()).isTrue();
        assertThat(policy.validate("HIT", "ACTIVATED", JanusRole.OPERATOR, "default", null, "standard").allowed()).isTrue();
        assertThat(policy.validate("BLOCKED", "MANUAL_FORCED", JanusRole.ADMIN, "backup", null, "strong_single").allowed()).isTrue();
    }
}
