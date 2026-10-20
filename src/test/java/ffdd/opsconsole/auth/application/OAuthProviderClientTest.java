package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(OutputCaptureExtension.class)
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
    @ParameterizedTest @EnumSource(OAuthProviderClient.Provider.class)
    void actualTokenExchangeRejectsInvalidSignatureAndAcceptsSignedProviderIdentity(
            OAuthProviderClient.Provider provider, CapturedOutput output) throws Exception {
        var rsa = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate();
        var other = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate();
        var client = new OAuthProviderClient(config().withProperty("oauth." + provider.key() + ".client-id", "client")
                .withProperty("oauth." + provider.key() + ".client-secret", "hidden-client-secret"));
        var http = (org.springframework.web.client.RestTemplate) org.springframework.test.util.ReflectionTestUtils.getField(client, "http");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(http).build();
        @SuppressWarnings("unchecked")
        var decoders = (java.util.Map<OAuthProviderClient.Provider, org.springframework.security.oauth2.jwt.JwtDecoder>)
                org.springframework.test.util.ReflectionTestUtils.getField(client, "decoders");
        decoders.put(provider,
                org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withPublicKey(rsa.toRSAPublicKey()).build());
        for (var signingKey : List.of(other, rsa)) {
            var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer(provider.issuer)
                    .audience("client").subject("hidden-provider-subject").claim("nonce", "hidden-nonce")
                    .issueTime(java.util.Date.from(Instant.now())).expirationTime(java.util.Date.from(Instant.now().plusSeconds(60))).build();
            var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.RS256), claims);
            jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(signingKey));
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(provider.token))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                            "{\"id_token\":\"" + jwt.serialize() + "\"}", org.springframework.http.MediaType.APPLICATION_JSON));
            if (signingKey == other) assertThatThrownBy(() -> client.exchange(provider, "hidden-code", "hidden-nonce", "hidden-verifier"))
                    .hasMessage("OAUTH_AUTHORIZATION_INVALID");
            else assertThat(client.exchange(provider, "hidden-code", "hidden-nonce", "hidden-verifier").subject())
                    .matches("oidc:[0-9a-f]{64}");
            server.verify(); server.reset();
            assertThat(output).doesNotContain(jwt.serialize());
        }
        assertThat(output).contains("provider=" + provider.name()
                + " stage=ID_TOKEN_DECODE category=JWT_REJECTED http_status=none")
                .doesNotContain("hidden-client-secret", "hidden-provider-subject", "hidden-nonce", "hidden-code", "hidden-verifier");
    }
    @Test void tokenEndpointDiagnosticsRetainStatusAndSuppressProviderPayload(CapturedOutput output) {
        var client = new OAuthProviderClient(config().withProperty("oauth.telegram.client-id", "client")
                .withProperty("oauth.telegram.client-secret", "hidden-client-secret"));
        var http = (org.springframework.web.client.RestTemplate) org.springframework.test.util.ReflectionTestUtils.getField(client, "http");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(http).build();
        for (var response : List.of(
                org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .body("{\"error\":\"hidden-provider-error\",\"access_token\":\"hidden-access-token\"}")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON),
                org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"access_token\":\"hidden-access-token\"}", org.springframework.http.MediaType.APPLICATION_JSON))) {
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(OAuthProviderClient.Provider.TELEGRAM.token))
                    .andRespond(response);
            assertThatThrownBy(() -> client.exchange(OAuthProviderClient.Provider.TELEGRAM, "hidden-code", "hidden-nonce", "hidden-verifier"))
                    .hasMessage("OAUTH_AUTHORIZATION_INVALID").hasNoCause();
            server.verify(); server.reset();
        }
        assertThat(output).contains(
                "provider=TELEGRAM stage=TOKEN_EXCHANGE category=HTTP_ERROR http_status=400",
                "provider=TELEGRAM stage=TOKEN_RESPONSE category=ID_TOKEN_MISSING_OR_INVALID http_status=200")
                .doesNotContain("hidden-provider-error", "hidden-access-token", "hidden-client-secret",
                        "hidden-code", "hidden-nonce", "hidden-verifier", "HttpClientErrorException");
    }
    @Test void decodeAndIdentityDiagnosticsDoNotExposeExceptionMessagesOrClaims(CapturedOutput output) {
        var client = new OAuthProviderClient(config().withProperty("oauth.telegram.client-id", "client")
                .withProperty("oauth.telegram.client-secret", "hidden-client-secret"));
        var http = (org.springframework.web.client.RestTemplate) org.springframework.test.util.ReflectionTestUtils.getField(client, "http");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(http).build();
        @SuppressWarnings("unchecked")
        var decoders = (java.util.Map<OAuthProviderClient.Provider, org.springframework.security.oauth2.jwt.JwtDecoder>)
                org.springframework.test.util.ReflectionTestUtils.getField(client, "decoders");
        Jwt malformed = org.mockito.Mockito.mock(Jwt.class);
        org.mockito.Mockito.when(malformed.getSubject()).thenThrow(new IllegalArgumentException("hidden-malformed-claim"));
        List<org.springframework.security.oauth2.jwt.JwtDecoder> rejected = List.of(
                token -> { throw new org.springframework.security.oauth2.jwt.JwtException("hidden-jwks-error"); },
                token -> { throw new org.springframework.security.oauth2.jwt.JwtValidationException("hidden-validation-error",
                        List.of(new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", "hidden-claim-error", null))); },
                token -> token().issuer(OAuthProviderClient.Provider.TELEGRAM.issuer).subject("hidden-subject")
                        .claim("name", "hidden-name").claim("nonce", "hidden-wrong-nonce").build(),
                token -> token().issuer(OAuthProviderClient.Provider.TELEGRAM.issuer).subject("hidden-subject")
                        .claims(claims -> claims.remove("nonce")).build(),
                token -> malformed);
        for (var decoder : rejected) {
            decoders.put(OAuthProviderClient.Provider.TELEGRAM, decoder);
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(OAuthProviderClient.Provider.TELEGRAM.token))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                            "{\"id_token\":\"hidden-id-token\"}", org.springframework.http.MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.exchange(OAuthProviderClient.Provider.TELEGRAM, "hidden-code", "hidden-nonce", "hidden-verifier"))
                    .hasMessage("OAUTH_AUTHORIZATION_INVALID").hasNoCause();
            server.verify(); server.reset();
        }
        assertThat(output).contains(
                "provider=TELEGRAM stage=ID_TOKEN_DECODE category=DECODER_FAILURE http_status=none",
                "provider=TELEGRAM stage=ID_TOKEN_DECODE category=JWT_CLAIMS_REJECTED http_status=none",
                "provider=TELEGRAM stage=IDENTITY_CLAIMS category=CLAIMS_REJECTED http_status=none",
                "issuer_valid=true audience_valid=true azp_valid=true expiry_valid=true issued_at_valid=true"
                        + " nonce_present=true nonce_match=false subject_valid=true",
                "nonce_present=false nonce_match=false subject_valid=true",
                "provider=TELEGRAM stage=IDENTITY_CLAIMS category=CLAIM_FORMAT_REJECTED http_status=none")
                .doesNotContain("hidden-jwks-error", "hidden-validation-error", "hidden-claim-error",
                        "hidden-id-token", "hidden-client-secret", "hidden-code", "hidden-nonce", "hidden-verifier",
                        "hidden-subject", "hidden-name", "hidden-wrong-nonce", "hidden-malformed-claim",
                        "JwtException", "JwtValidationException", "IllegalArgumentException");
    }
}
