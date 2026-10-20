package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.infrastructure.OAuthProviderFlow;
import ffdd.opsconsole.auth.infrastructure.UserOAuthIdentityEntity;
import ffdd.opsconsole.auth.mapper.OAuthProviderFlowMapper;
import ffdd.opsconsole.auth.mapper.UserOAuthIdentityMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OAuthProviderFlowServiceTest {
    final OAuthProviderClient providers = mock(OAuthProviderClient.class);
    final OAuthProviderFlowMapper mapper = mock(OAuthProviderFlowMapper.class);
    final UserOAuthIdentityMapper identities = mock(UserOAuthIdentityMapper.class);
    final AppUserAuthService auth = mock(AppUserAuthService.class);
    final MockEnvironment environment = new MockEnvironment();
    final AppUserSecurityVerificationGuard guard = mock(AppUserSecurityVerificationGuard.class);
    final OAuthProviderFlowService service = new OAuthProviderFlowService(providers, mapper, identities, auth, environment, guard);
    final String id = "OIDC-" + "a".repeat(32), key = "k".repeat(43), receipt = "r".repeat(43), state = "s".repeat(43);
    final OAuthProviderFlowService.Proof proof = new OAuthProviderFlowService.Proof(id, key, receipt);
    final AppLoginContext context = new AppLoginContext(AppSessionSurface.H5, "", false, id, key, "OAUTH", "GOOGLE:" + id);
    OAuthProviderFlow flow;
    @BeforeEach void setup() {
        environment.setProperty("oauth.enabled", "true");
        environment.setActiveProfiles("dev");
        flow = new OAuthProviderFlow();
        flow.setId(id); flow.setProofHash(OAuthProviderClient.hash(key)); flow.setReceiptHash(OAuthProviderClient.hash(receipt));
        flow.setState(state); flow.setProvider("GOOGLE"); flow.setStatus("VERIFIED"); flow.setSubject("verified-subject");
        flow.setDisplayName("Verified account"); flow.setBrowserHash(OAuthProviderClient.hash("browser"));
        flow.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        when(mapper.find(id)).thenReturn(flow); when(mapper.lock(id)).thenReturn(flow); when(mapper.byState(state)).thenReturn(flow);
        when(mapper.consume(id)).thenReturn(1); when(providers.provider("GOOGLE")).thenReturn(OAuthProviderClient.Provider.GOOGLE);
        when(auth.verifyProviderLink(anyLong(), any(), any())).thenReturn(ApiResult.ok());
        when(guard.allowed(anyLong(), any())).thenReturn(true);
        when(guard.recordFailure(anyLong(), any())).thenReturn(new AppUserSecurityVerificationGuard.VerificationFailure(1, false));
    }
    @Test void flowProofAndBrowserReceiptAreBothRequiredForLogin() {
        assertThatThrownBy(() -> service.login(new OAuthProviderFlowService.Proof(id, key, null), "client", context, null, null))
                .hasMessage("OAUTH_BROWSER_RECEIPT_REQUIRED");
        assertThatThrownBy(() -> service.login(new OAuthProviderFlowService.Proof(id, "x".repeat(43), receipt), "client", context, null, null))
                .hasMessage("OAUTH_FLOW_EXPIRED");
        verifyNoInteractions(auth, identities);
    }
    @Test void pollingWithoutBrowserReceiptRevealsNeitherIdentityNorLoginAuthority() {
        var status = service.status(new OAuthProviderFlowService.Proof(id, key, null));
        assertThat(status.status()).isEqualTo("PENDING"); assertThat(status.displayName()).isNull();
        assertThat(service.status(proof).displayName()).isEqualTo("Verified account");
    }
    @Test void unknownProviderIdentityDoesNotCreateAccountOrIssueSession() {
        assertThat(service.login(proof, "client", context, null, null).getMessage()).isEqualTo("OAUTH_ACCOUNT_LINK_REQUIRED");
        verify(identities, never()).insertIdentity(any());
        verify(auth, never()).issueProviderSession(any(), any(), any(), any(), any(), any());
        verify(mapper, never()).consume(any());
    }
    @Test void validLoginUsesInteractiveSessionPolicyAndConsumesOnlyAfterSuccess() {
        var identity = new UserOAuthIdentityEntity(); identity.setUserId(12L);
        when(identities.findProviderIdentity("GOOGLE", "verified-subject")).thenReturn(identity);
        when(auth.issueProviderSession(12L, "client", context, null, null, null)).thenReturn(ApiResult.fail(428, "USER_TWO_FACTOR_VERIFICATION_REQUIRED"));
        assertThat(service.login(proof, "client", context, null, null).getCode()).isEqualTo(428);
        verify(mapper, never()).consume(any());
        when(auth.issueProviderSession(12L, "client", context, "otp", "123456", null)).thenReturn(ApiResult.ok(
                new UserLoginResponse("access", "Bearer", null, "refresh")));
        assertThat(service.login(proof, "client", context, "otp", "123456").getCode()).isZero();
        verify(mapper).consume(id);
    }
    @Test void consumedLoginCanOnlyRecoverOriginalCompletedAttempt() {
        flow.setStatus("CONSUMED");
        assertThatThrownBy(() -> service.login(proof, "client", context, null, null)).hasMessage("OAUTH_FLOW_NOT_READY");
        var response = ApiResult.ok(new UserLoginResponse("access", "Bearer", null, "refresh"));
        when(auth.recoverCompletedLogin(context)).thenReturn(response);
        assertThat(service.login(proof, "client", context, null, null)).isSameAs(response);
        verify(auth, never()).issueProviderSession(any(), any(), any(), any(), any(), any());
    }
    @Test void linkIntentIsFixedToAuthenticatedOwnerAndNeverChangesAnExistingLink() {
        flow.setLinkUserId(12L);
        assertThatThrownBy(() -> service.link(proof, 13L, "password", "client")).hasMessage("OAUTH_FLOW_INTENT_INVALID");
        var other = new UserOAuthIdentityEntity(); other.setUserId(13L);
        when(identities.findForUpdate("GOOGLE", "verified-subject", "PRODUCTION")).thenReturn(other);
        assertThatThrownBy(() -> service.link(proof, 12L, "password", "client")).hasMessage("OAUTH_IDENTITY_ALREADY_LINKED");
        verify(identities, never()).insertIdentity(any()); verify(mapper, never()).consume(any());
    }
    @Test void passwordDenialReturnsWithoutRollingBackRateCountersOrConsumingProof() {
        flow.setLinkUserId(12L);
        when(auth.verifyProviderLink(12L, "wrong", "client")).thenReturn(ApiResult.fail(403, "OAUTH_LINK_PASSWORD_INVALID"));
        assertThat(service.link(proof, 12L, "wrong", "client").getCode()).isEqualTo(403);
        verifyNoInteractions(identities); verify(mapper, never()).consume(any());
    }
    @Test void authorizedBindingCanRecoverLostResponseWithoutCreatingAnotherLink() {
        flow.setLinkUserId(12L);
        when(identities.linkedProviders(12L)).thenReturn(List.of());
        assertThat(service.link(proof, 12L, "password", "client").getCode()).isZero();
        var linked = new UserOAuthIdentityEntity(); linked.setUserId(12L);
        when(identities.findForUpdate("GOOGLE", "verified-subject", "PRODUCTION")).thenReturn(linked);
        flow.setStatus("CONSUMED");
        assertThat(service.link(proof, 12L, "password", "client").getCode()).isZero();
        verify(identities, times(1)).insertIdentity(any());
    }
    @Test void callbackRejectsWrongBrowserProviderAndDuplicateBeforeExchangingCode() {
        assertThatThrownBy(() -> service.callback("APPLE", state, "browser", "code", null)).hasMessage("OAUTH_AUTHORIZATION_INVALID");
        assertThatThrownBy(() -> service.callback("GOOGLE", state, "wrong", "code", null)).hasMessage("OAUTH_AUTHORIZATION_INVALID");
        assertThatThrownBy(() -> service.callback("GOOGLE", state, "browser", "code", null)).hasMessage("OAUTH_FLOW_EXPIRED");
        verify(providers, never()).exchange(any(), any(), any(), any());
    }
    @Test void expiryAndSandboxNeverReachRealIdentityLogin() {
        flow.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        assertThatThrownBy(() -> service.login(proof, "client", context, null, null)).hasMessage("OAUTH_FLOW_EXPIRED");
        environment.setActiveProfiles("test");
        assertThatThrownBy(() -> service.login(proof, "client", context, null, null)).hasMessage("OAUTH_PROFILE_FORBIDDEN");
        verifyNoInteractions(auth, identities);
    }
    @Test void accountPasswordLockBlocksBindingAcrossClientAddresses() {
        flow.setLinkUserId(12L);
        when(guard.allowed(12L, "OAUTH_LINK")).thenReturn(false);
        assertThat(service.link(proof, 12L, "password", "another-client").getCode()).isEqualTo(429);
        verifyNoInteractions(auth, identities);
    }
}
