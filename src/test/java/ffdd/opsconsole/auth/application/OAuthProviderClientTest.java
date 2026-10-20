package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;

class OAuthProviderClientTest {
    private MockEnvironment config() {
        var env = new MockEnvironment().withProperty("oauth.enabled", "true")
                .withProperty("oauth.public-base-url", "https://api.example.test")
                .withProperty("oauth.h5-origin", "https://app.example.test");
        env.setActiveProfiles("dev");
        return env;
    }

    @Test void noCredentialsMeansNoAvailableProviders() {
        assertThat(new OAuthProviderClient(config()).available()).isEmpty();
    }

    @ParameterizedTest @EnumSource(OAuthProviderClient.Provider.class)
    void authorizationBindsFixedProviderCallbackStateNonceAndPkce(OAuthProviderClient.Provider provider) {
        var client = new OAuthProviderClient(config().withProperty("oauth." + provider.key() + ".client-id", "client")
                .withProperty("oauth." + provider.key() + ".client-secret", "secret"));
        var url = java.net.URI.create(client.authorizationUrl(provider, "state", "nonce", "verifier"));
        assertThat(url.getHost()).isEqualTo(java.net.URI.create(provider.authorization).getHost());
        assertThat(url.getQuery()).contains("state=state", "nonce=nonce", "/callback/" + provider.name())
                .doesNotContain("client-secret", "secret");
        if (provider == OAuthProviderClient.Provider.APPLE)
            assertThat(url.getQuery()).contains("response_mode=form_post", "scope=email").doesNotContain("scope=openid");
        else assertThat(url.getQuery()).contains("code_challenge_method=S256", OAuthProviderClient.hash("verifier"));
    }

    private Jwt.Builder token() {
        return Jwt.withTokenValue("test").header("alg", "RS256").issuer("https://issuer.test").subject("CaseSensitiveSubject")
                .audience(List.of("client")).issuedAt(Instant.now().minusSeconds(10))
                .expiresAt(Instant.now().plusSeconds(60)).claim("nonce", "nonce");
    }
    private OAuthProviderClient.Identity validate(Jwt jwt) {
        return OAuthProviderClient.validateIdentity(jwt, "https://issuer.test", "client", "nonce");
    }
    @Test void identityRejectsIssuerAudienceExpiryFutureIssuanceNonceAndAmbiguousAudience() {
        for (Jwt invalid : List.of(token().issuer("https://attacker.test").build(),
                token().audience(List.of("other")).build(), token().expiresAt(Instant.now().minusSeconds(1)).build(),
                token().issuedAt(Instant.now().plusSeconds(120)).expiresAt(Instant.now().plusSeconds(300)).build(),
                token().claim("nonce", "wrong").build(), token().audience(List.of("client", "other")).build())) {
            assertThatThrownBy(() -> validate(invalid)).hasMessage("OAUTH_AUTHORIZATION_INVALID");
        }
    }
    @Test void subjectsRemainCaseSensitiveAndAreNotDerivedFromEmail() {
        var a = validate(token().claim("email", "same@example.test").build());
        var b = validate(token().subject("casesensitivesubject").claim("email", "same@example.test").build());
        assertThat(a.subject()).matches("oidc:[0-9a-f]{64}").isNotEqualTo(b.subject());
        assertThat(a.displayName()).isEqualTo("same@example.test");
    }
    @Test void unsafeBaseUrlAndMissingOriginStayUnavailable() {
        var env = config().withProperty("oauth.google.client-id", "client").withProperty("oauth.google.client-secret", "secret");
        for (String base : List.of("http://api.example.test", "https://user:secret@api.example.test", "https://api.example.test?q=1")) {
            env.setProperty("oauth.public-base-url", base);
            assertThat(new OAuthProviderClient(env).available()).isEmpty();
        }
    }
    @Test void actualTokenExchangeRejectsInvalidSignatureAndAcceptsSignedProviderIdentity() throws Exception {
        var rsa = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate();
        var other = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate();
        var client = new OAuthProviderClient(config().withProperty("oauth.google.client-id", "client")
                .withProperty("oauth.google.client-secret", "secret"));
        var http = (org.springframework.web.client.RestTemplate) org.springframework.test.util.ReflectionTestUtils.getField(client, "http");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(http).build();
        @SuppressWarnings("unchecked")
        var decoders = (java.util.Map<OAuthProviderClient.Provider, org.springframework.security.oauth2.jwt.JwtDecoder>)
                org.springframework.test.util.ReflectionTestUtils.getField(client, "decoders");
        decoders.put(OAuthProviderClient.Provider.GOOGLE,
                org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withPublicKey(rsa.toRSAPublicKey()).build());
        for (var signingKey : List.of(other, rsa)) {
            var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("https://accounts.google.com")
                    .audience("client").subject("verified-google-subject").claim("nonce", "nonce")
                    .issueTime(java.util.Date.from(Instant.now())).expirationTime(java.util.Date.from(Instant.now().plusSeconds(60))).build();
            var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.RS256), claims);
            jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(signingKey));
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                            "{\"id_token\":\"" + jwt.serialize() + "\"}", org.springframework.http.MediaType.APPLICATION_JSON));
            if (signingKey == other) assertThatThrownBy(() -> client.exchange(OAuthProviderClient.Provider.GOOGLE, "code", "nonce", "verifier"))
                    .hasMessage("OAUTH_AUTHORIZATION_INVALID");
            else assertThat(client.exchange(OAuthProviderClient.Provider.GOOGLE, "code", "nonce", "verifier").subject())
                    .matches("oidc:[0-9a-f]{64}");
            server.verify(); server.reset();
        }
    }
}
