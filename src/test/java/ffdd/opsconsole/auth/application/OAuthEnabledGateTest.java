package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.auth.mapper.OAuthProviderFlowMapper;
import ffdd.opsconsole.auth.mapper.UserOAuthIdentityMapper;
import ffdd.opsconsole.shared.security.CurrentAppSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class OAuthEnabledGateTest {
    private MockEnvironment configured() {
        var env = new MockEnvironment().withProperty("oauth.public-base-url", "https://api.example.test")
                .withProperty("oauth.h5-origin", "https://app.example.test");
        for (var provider : OAuthProviderClient.Provider.values()) {
            env.setProperty("oauth." + provider.key() + ".client-id", "client");
            env.setProperty("oauth." + provider.key() + ".client-secret", "secret");
        }
        return env;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"false", "FALSE", "0", "yes", "invalid", " true "})
    void credentialsDoNotEnableAnyProviderOrExistingFlow(String flag) {
        for (String profile : new String[] {"dev", "prod", "test"}) {
            var env = configured();
            env.setActiveProfiles(profile);
            if (flag != null) env.setProperty("oauth.enabled", flag);
            var client = new OAuthProviderClient(env);
            var mapper = mock(OAuthProviderFlowMapper.class);
            var identities = mock(UserOAuthIdentityMapper.class);
            var auth = mock(AppUserAuthService.class);
            var guard = mock(AppUserSecurityVerificationGuard.class);
            var sessions = mock(CurrentAppSessionService.class);
            var service = new OAuthProviderFlowService(client, mapper, identities, auth, env, guard, sessions);
            var proof = new OAuthProviderFlowService.Proof("existing-flow", "key", "receipt");
            assertThat(client.available()).isEmpty();
            assertThat(service.available()).isEmpty();
            for (var provider : OAuthProviderClient.Provider.values()) {
                assertThatThrownBy(() -> client.authorizationUrl(provider, "state", "nonce", "verifier"))
                        .hasMessage("OAUTH_DISABLED");
                assertThatThrownBy(() -> client.exchange(provider, "code", "nonce", "verifier"))
                        .hasMessage("OAUTH_DISABLED");
                assertThatThrownBy(() -> service.start(provider.name(), null, "client"))
                        .hasMessage("OAUTH_DISABLED");
            }
            assertThatThrownBy(() -> service.openBrowser("state")).hasMessage("OAUTH_DISABLED");
            assertThatThrownBy(() -> service.callback("GOOGLE", "state", "browser", "code", null))
                    .hasMessage("OAUTH_DISABLED");
            assertThatThrownBy(() -> service.status(proof)).hasMessage("OAUTH_DISABLED");
            assertThatThrownBy(() -> service.login(proof, "client", null, null, null))
                    .hasMessage("OAUTH_DISABLED");
            assertThatThrownBy(() -> service.link(proof, 1L, "password", "client", null)).hasMessage("OAUTH_DISABLED");
            assertThatThrownBy(() -> service.linked(1L)).hasMessage("OAUTH_DISABLED");
            verifyNoInteractions(mapper, identities, auth, guard, sessions);
        }
    }

    @Test
    void explicitEnableStillRequiresCredentialsAndCorrectProfileAndCanBeTurnedOff() {
        var env = configured().withProperty("oauth.enabled", "true");
        env.setActiveProfiles("dev");
        var client = new OAuthProviderClient(env);
        assertThat(client.available()).containsExactly("GOOGLE", "APPLE", "TELEGRAM");
        env.setProperty("oauth.apple.client-secret", "");
        assertThat(client.available()).containsExactly("GOOGLE", "TELEGRAM");
        var mapper = mock(OAuthProviderFlowMapper.class);
        var service = new OAuthProviderFlowService(client, mapper, mock(UserOAuthIdentityMapper.class),
                mock(AppUserAuthService.class), env, mock(AppUserSecurityVerificationGuard.class), mock(CurrentAppSessionService.class));
        env.setActiveProfiles("test");
        assertThat(service.available()).isEmpty();
        assertThatThrownBy(() -> service.start("GOOGLE", null, "client")).hasMessage("OAUTH_PROFILE_FORBIDDEN");
        env.setActiveProfiles("dev");
        env.setProperty("oauth.enabled", "false");
        assertThat(service.available()).isEmpty();
        assertThatThrownBy(() -> service.start("GOOGLE", null, "client")).hasMessage("OAUTH_DISABLED");
        verifyNoInteractions(mapper);
    }
}
