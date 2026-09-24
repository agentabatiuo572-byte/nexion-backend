package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JwtTokenProviderRefreshSuccessorTest {
    @Test
    void successorIsDeterministicAndPurposeSeparated() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-only-secret-with-at-least-thirty-two-bytes");
        JwtTokenProvider tokens = new JwtTokenProvider(properties);
        String predecessor = "opaque-browser-refresh-token";
        String nonce = "a".repeat(64);

        String successor = tokens.refreshSuccessor(predecessor, nonce);

        assertThat(successor).matches("[A-Za-z0-9_-]{43}");
        assertThat(tokens.refreshSuccessor(predecessor, nonce)).isEqualTo(successor);
        assertThat(tokens.refreshSuccessor(predecessor, "b".repeat(64))).isNotEqualTo(successor);
        assertThat(tokens.refreshSuccessor("another-token", nonce)).isNotEqualTo(successor);
        assertThat(successor).isNotEqualTo(tokens.sessionSyncKey(predecessor));
    }
}
