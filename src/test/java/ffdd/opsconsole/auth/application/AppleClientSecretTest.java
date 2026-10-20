package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AppleClientSecretTest {
    @TempDir Path directory;

    private KeyPair writeKey(String curve) throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        var pair = generator.generateKeyPair();
        Files.writeString(directory.resolve("apple.p8"), "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        return pair;
    }

    private MockEnvironment config() {
        return new MockEnvironment().withProperty("oauth.enabled", "true")
                .withProperty("oauth.public-base-url", "https://api.example.test")
                .withProperty("oauth.h5-origin", "https://app.example.test")
                .withProperty("oauth.apple.client-id", "ai.uvel.test.login")
                .withProperty("oauth.apple.team-id", "8UTJU6M327")
                .withProperty("oauth.apple.key-id", "4SW48D2D8Z")
                .withProperty("oauth.apple.private-key-file", directory.resolve("apple.p8").toString());
    }

    @Test void signsFreshShortLivedSecretsEvenAfterThePreviousSecretExpired() throws Exception {
        var pair = writeKey("secp256r1");
        var env = config();
        var firstTime = Instant.parse("2026-10-21T00:00:00Z");
        for (var now : List.of(firstTime, firstTime.plusSeconds(365L * 86400))) {
            var jwt = SignedJWT.parse(AppleClientSecret.create(env, now));
            assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) pair.getPublic()))).isTrue();
            assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
            assertThat(jwt.getHeader().getKeyID()).isEqualTo("4SW48D2D8Z");
            var claims = jwt.getJWTClaimsSet();
            assertThat(claims.getIssuer()).isEqualTo("8UTJU6M327");
            assertThat(claims.getSubject()).isEqualTo("ai.uvel.test.login");
            assertThat(claims.getAudience()).containsExactly("https://appleid.apple.com");
            assertThat(claims.getIssueTime().toInstant()).isEqualTo(now.minusSeconds(30));
            assertThat(claims.getExpirationTime().toInstant()).isEqualTo(now.plusSeconds(300));
        }
    }

    @Test void readsReplacementKeyAndNeverFallsBackToStaticSecretWhenSigningIsConfigured() throws Exception {
        var first = writeKey("secp256r1");
        var env = config().withProperty("oauth.apple.client-secret", "expired-static-secret");
        var second = writeKey("secp256r1");
        var jwt = SignedJWT.parse(AppleClientSecret.create(env, Instant.now()));
        assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) second.getPublic()))).isTrue();
        assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) first.getPublic()))).isFalse();
        Files.writeString(directory.resolve("apple.p8"), "private-material-invalid");
        assertThat(new OAuthProviderClient(env).available()).isEmpty();
        assertThatThrownBy(() -> AppleClientSecret.create(env, Instant.now()))
                .hasMessage("OAUTH_PROVIDER_NOT_CONFIGURED").hasNoCause();
    }

    @Test void incompleteConfigurationMissingFileAndWrongCurveAreUnavailable() throws Exception {
        var env = config().withProperty("oauth.apple.client-secret", "old-secret");
        assertThat(AppleClientSecret.configured(env)).isFalse();
        writeKey("secp384r1");
        assertThat(AppleClientSecret.configured(env)).isFalse();
        writeKey("secp256r1");
        assertThat(AppleClientSecret.configured(env)).isTrue();
        env.setProperty("oauth.apple.team-id", "");
        assertThat(new OAuthProviderClient(env).available()).isEmpty();
    }

    @Test void disabledGateRejectsBeforeUsingMissingSigningKeyOrMakingTokenRequest() {
        var client = new OAuthProviderClient(config().withProperty("oauth.enabled", "false"));
        var http = (RestTemplate) ReflectionTestUtils.getField(client, "http");
        var server = MockRestServiceServer.bindTo(http).build();
        assertThat(client.available()).isEmpty();
        assertThatThrownBy(() -> client.exchange(OAuthProviderClient.Provider.APPLE, "code", "nonce", "verifier"))
                .hasMessage("OAUTH_DISABLED");
        server.verify();
    }

    @Test void appleTokenRequestUsesAutomaticallySignedSecret() throws Exception {
        var pair = writeKey("secp256r1");
        var client = new OAuthProviderClient(config());
        assertThat(client.available()).containsExactly("APPLE");
        var appleKey = new RSAKeyGenerator(2048).generate();
        @SuppressWarnings("unchecked")
        var decoders = (java.util.Map<OAuthProviderClient.Provider, org.springframework.security.oauth2.jwt.JwtDecoder>)
                ReflectionTestUtils.getField(client, "decoders");
        decoders.put(OAuthProviderClient.Provider.APPLE, NimbusJwtDecoder.withPublicKey(appleKey.toRSAPublicKey()).build());
        var now = Instant.now();
        var identity = new SignedJWT(new com.nimbusds.jose.JWSHeader(JWSAlgorithm.RS256),
                new JWTClaimsSet.Builder().issuer("https://appleid.apple.com").subject("apple-subject")
                        .audience("ai.uvel.test.login").claim("nonce", "nonce")
                        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60))).build());
        identity.sign(new RSASSASigner(appleKey));
        var server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(client, "http")).build();
        server.expect(requestTo("https://appleid.apple.com/auth/token")).andExpect(request -> {
            var body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
            var form = org.springframework.web.util.UriComponentsBuilder.fromUriString("https://test.invalid/?" + body)
                    .build().getQueryParams();
            try {
                var jwt = SignedJWT.parse(java.net.URLDecoder.decode(form.getFirst("client_secret"), java.nio.charset.StandardCharsets.UTF_8));
                assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) pair.getPublic()))).isTrue();
                assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("ai.uvel.test.login");
            } catch (Exception exception) {
                throw new AssertionError("Invalid signed Apple client authentication", exception);
            }
            assertThat(body).contains("client_id=ai.uvel.test.login").doesNotContain("code_verifier");
        }).andRespond(withSuccess("{\"id_token\":\"" + identity.serialize() + "\"}", org.springframework.http.MediaType.APPLICATION_JSON));
        assertThat(client.exchange(OAuthProviderClient.Provider.APPLE, "code", "nonce", "unused").subject()).startsWith("oidc:");
        server.verify();
    }
}
