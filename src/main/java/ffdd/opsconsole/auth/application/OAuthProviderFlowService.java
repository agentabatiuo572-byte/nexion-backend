package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.infrastructure.OAuthProviderFlow;
import ffdd.opsconsole.auth.infrastructure.UserOAuthIdentityEntity;
import ffdd.opsconsole.auth.mapper.OAuthProviderFlowMapper;
import ffdd.opsconsole.auth.mapper.UserOAuthIdentityMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OAuthProviderFlowService {
    public record Start(String flowId, String flowKey, String authorizationUrl, int expiresInSec) {
        @Override public String toString() { return "Start[REDACTED]"; }
    }
    public record Proof(String flowId, String flowKey, String receipt) {
        @Override public String toString() { return "Proof[REDACTED]"; }
    }
    public record Status(String status, String provider, String displayName) {}
    public record BrowserStart(String location, String browserKey) {}
    public record Callback(String flowId, String receipt) {}
    private final OAuthProviderClient providers;
    private final OAuthProviderFlowMapper flows;
    private final UserOAuthIdentityMapper identities;
    private final AppUserAuthService auth;
    private final Environment environment;
    private final AppUserSecurityVerificationGuard verificationGuard;

    @PostConstruct void ensureSchema() { flows.createTable(); }

    private void requireEnvironment() {
        if (!OAuthProviderClient.enabled(environment))
            throw new BizException(503, "OAUTH_DISABLED");
        if (UserAuthEnvironment.resolve(environment).orElse(null) != UserAuthEnvironment.PRODUCTION)
            throw new BizException(503, "OAUTH_PROFILE_FORBIDDEN");
    }

    public List<String> available() {
        return OAuthProviderClient.enabled(environment)
                && UserAuthEnvironment.resolve(environment).orElse(null) == UserAuthEnvironment.PRODUCTION
                ? providers.available() : List.of();
    }

    public List<String> linked(Long userId) {
        requireEnvironment();
        return identities.linkedProviders(userId);
    }

    public Start start(String providerName, Long linkUserId, String clientAddress) {
        requireEnvironment();
        var provider = providers.provider(providerName);
        providers.requireConfigured(provider);
        String clientHash = OAuthProviderClient.hash(String.valueOf(clientAddress));
        flows.cleanup();
        if (flows.countForClient(clientHash) >= 20) throw new BizException(429, "OAUTH_RATE_LIMITED");
        var flow = new OAuthProviderFlow();
        String key = OAuthProviderClient.random();
        flow.setId("OIDC-" + UUID.randomUUID().toString().replace("-", ""));
        flow.setProofHash(OAuthProviderClient.hash(key));
        flow.setState(OAuthProviderClient.random());
        flow.setProvider(provider.name());
        flow.setNonce(OAuthProviderClient.random());
        flow.setVerifier(OAuthProviderClient.random());
        flow.setLinkUserId(linkUserId);
        flow.setClientHash(clientHash);
        flows.insertFlow(flow);
        return new Start(flow.getId(), key, providers.publicBaseUrl()
                + "/auth/users/oauth/provider/authorize/" + flow.getState(), 600);
    }

    public BrowserStart openBrowser(String state) {
        requireEnvironment();
        OAuthProviderFlow flow = stateFlow(state);
        String browserKey = OAuthProviderClient.random();
        if (flows.openBrowser(flow.getId(), OAuthProviderClient.hash(browserKey)) != 1)
            throw new BizException(401, "OAUTH_FLOW_EXPIRED");
        return new BrowserStart(providers.authorizationUrl(providers.provider(flow.getProvider()),
                flow.getState(), flow.getNonce(), flow.getVerifier()), browserKey);
    }

    public Callback callback(String providerName, String state, String browserKey, String code, String error) {
        requireEnvironment();
        OAuthProviderFlow flow = stateFlow(state);
        if (!flow.getProvider().equals(providerName) || browserKey == null
                || !OAuthProviderClient.constantEquals(flow.getBrowserHash(), OAuthProviderClient.hash(browserKey)))
            throw new BizException(401, "OAUTH_AUTHORIZATION_INVALID");
        if (flows.claimCallback(flow.getId()) != 1) throw new BizException(401, "OAUTH_FLOW_EXPIRED");
        try {
            if (error != null) throw new BizException(401, "OAUTH_AUTHORIZATION_CANCELLED");
            var identity = providers.exchange(providers.provider(providerName), code, flow.getNonce(), flow.getVerifier());
            // Only the authorizing browser receives the receipt. Polling cannot complete login:
            // sharing an authorization link must not allow its creator to steal the visitor's account.
            String receipt = OAuthProviderClient.random();
            return flows.finishCallback(flow.getId(), "VERIFIED", identity.subject(), identity.displayName(),
                    OAuthProviderClient.hash(receipt)) == 1 ? new Callback(flow.getId(), receipt) : null;
        } catch (RuntimeException exception) {
            flows.finishCallback(flow.getId(), "FAILED", null, null, null);
            return null;
        }
    }

    public Status status(Proof proof) {
        requireEnvironment();
        OAuthProviderFlow flow = proofFlow(proof, false);
        String state = flow.getStatus();
        if ("VERIFIED".equals(state) && (proof.receipt() == null
                || !OAuthProviderClient.constantEquals(flow.getReceiptHash(), OAuthProviderClient.hash(proof.receipt()))))
            state = "PENDING";
        if (List.of("PENDING", "AUTHORIZING", "EXCHANGING").contains(state)) state = "PENDING";
        return new Status(state, flow.getProvider(), "VERIFIED".equals(state) ? flow.getDisplayName() : null);
    }

    @Transactional
    public ApiResult<UserLoginResponse> login(Proof proof, String clientAddress, AppLoginContext context,
            String challengeNo, String code) {
        return login(proof, clientAddress, context, challengeNo, code, null);
    }

    @Transactional
    public ApiResult<UserLoginResponse> login(Proof proof, String clientAddress, AppLoginContext context,
            String challengeNo, String code, String captchaTicket) {
        requireEnvironment();
        OAuthProviderFlow flow = proofFlow(proof, true);
        requireReceipt(flow, proof);
        if (flow.getLinkUserId() != null) throw new BizException(403, "OAUTH_FLOW_INTENT_INVALID");
        if (!context.authReference().equals(flow.getProvider() + ":" + flow.getId()))
            throw new BizException(422, "OAUTH_FLOW_INTENT_INVALID");
        ApiResult<UserLoginResponse> recovered = auth.recoverCompletedLogin(context);
        if (recovered != null) return recovered;
        requireVerified(flow);
        // Links are immutable here. Read the mapping before the account lock without taking an identity
        // lock; linking always locks account -> identity, so login must not reverse that order.
        var identity = identities.findProviderIdentity(flow.getProvider(), flow.getSubject());
        if (identity == null) return ApiResult.fail(409, "OAUTH_ACCOUNT_LINK_REQUIRED");
        var result = auth.issueProviderSession(identity.getUserId(), clientAddress, context, challengeNo, code, captchaTicket);
        if (result.getCode() == 0 && flows.consume(flow.getId()) != 1)
            throw new BizException(409, "OAUTH_FLOW_ALREADY_USED");
        return result;
    }

    @Transactional
    public ApiResult<Void> link(Proof proof, Long userId, String password, String clientAddress) {
        requireEnvironment();
        OAuthProviderFlow flow = proofFlow(proof, true);
        requireReceipt(flow, proof);
        if (!Objects.equals(userId, flow.getLinkUserId())) throw new BizException(403, "OAUTH_FLOW_INTENT_INVALID");
        if (!verificationGuard.allowed(userId, "OAUTH_LINK"))
            return ApiResult.fail(429, "USER_SECURITY_VERIFICATION_RATE_LIMITED");
        ApiResult<Void> verified = auth.verifyProviderLink(userId, password, clientAddress);
        if (verified.getCode() != 0) {
            if ("OAUTH_LINK_PASSWORD_INVALID".equals(verified.getMessage())) {
                var failure = verificationGuard.recordFailure(userId, "OAUTH_LINK");
                if (failure.rateLimited()) return ApiResult.fail(429, "USER_SECURITY_VERIFICATION_RATE_LIMITED");
            }
            return verified;
        }
        verificationGuard.clear(userId);
        var identity = identities.findForUpdate(flow.getProvider(), flow.getSubject(), "PRODUCTION");
        if ("CONSUMED".equals(flow.getStatus()) && identity != null && userId.equals(identity.getUserId()))
            return ApiResult.ok();
        requireVerified(flow);
        if (identity != null && !userId.equals(identity.getUserId()))
            throw new BizException(409, "OAUTH_IDENTITY_ALREADY_LINKED");
        if (identity == null) {
            if (identities.linkedProviders(userId).contains(flow.getProvider()))
                throw new BizException(409, "OAUTH_PROVIDER_ALREADY_LINKED");
            identity = new UserOAuthIdentityEntity();
            identity.setProvider(flow.getProvider());
            identity.setExternalSubject(flow.getSubject());
            identity.setUserId(userId);
            identity.setSourceEnvironment("PRODUCTION");
            identity.setDisplayName(flow.getDisplayName());
            try { identities.insertIdentity(identity); }
            catch (DuplicateKeyException exception) { throw new BizException(409, "OAUTH_IDENTITY_ALREADY_LINKED"); }
        }
        if (flows.consume(flow.getId()) != 1) throw new BizException(409, "OAUTH_FLOW_ALREADY_USED");
        return ApiResult.ok();
    }

    private OAuthProviderFlow stateFlow(String state) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}"))
            throw new BizException(401, "OAUTH_FLOW_EXPIRED");
        OAuthProviderFlow flow = flows.byState(state);
        if (flow == null) throw new BizException(401, "OAUTH_FLOW_EXPIRED");
        return flow;
    }

    private OAuthProviderFlow proofFlow(Proof proof, boolean lock) {
        if (proof == null || proof.flowId() == null || !proof.flowId().matches("OIDC-[a-f0-9]{32}")
                || proof.flowKey() == null || !proof.flowKey().matches("[A-Za-z0-9_-]{43}"))
            throw new BizException(401, "OAUTH_FLOW_EXPIRED");
        OAuthProviderFlow flow = lock ? flows.lock(proof.flowId()) : flows.find(proof.flowId());
        if (flow == null || !OAuthProviderClient.constantEquals(flow.getProofHash(), OAuthProviderClient.hash(proof.flowKey()))
                || flow.getExpiresAt() == null || !flow.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)))
            throw new BizException(401, "OAUTH_FLOW_EXPIRED");
        return flow;
    }

    private void requireVerified(OAuthProviderFlow flow) {
        if (!"VERIFIED".equals(flow.getStatus()) || flow.getSubject() == null)
            throw new BizException(409, "OAUTH_FLOW_NOT_READY");
    }

    private void requireReceipt(OAuthProviderFlow flow, Proof proof) {
        if (proof.receipt() == null || !proof.receipt().matches("[A-Za-z0-9_-]{43}")
                || !OAuthProviderClient.constantEquals(flow.getReceiptHash(), OAuthProviderClient.hash(proof.receipt())))
            throw new BizException(403, "OAUTH_BROWSER_RECEIPT_REQUIRED");
    }
}
