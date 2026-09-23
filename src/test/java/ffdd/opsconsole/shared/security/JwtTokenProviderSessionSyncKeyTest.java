package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JwtTokenProviderSessionSyncKeyTest {
    @Test
    void derivesAnOpaqueStableKeyPerRefreshChain() {
        JwtTokenProvider provider = new JwtTokenProvider(new JwtProperties());
        String chain = "315fe645-827d-4d91-9399-c5103a52cd50";
        String key = provider.sessionSyncKey(chain);

        assertThat(key).matches("[0-9a-f]{64}");
        assertThat(key).isEqualTo(provider.sessionSyncKey(chain));
        assertThat(key).isNotEqualTo(provider.sessionSyncKey("826b1d07-576f-4866-b497-b5f2b51ccdd4"));
        assertThat(key).doesNotContain(chain);
    }
}
