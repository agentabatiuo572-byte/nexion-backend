package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Only provider-issued, signature-verified identities cross this boundary. */
@Component
public class OAuthProviderClient {
    public enum Provider {
        GOOGLE("https://accounts.google.com", "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token", "https://www.googleapis.com/oauth2/v3/certs"),
        APPLE("https://appleid.apple.com", "https://appleid.apple.com/auth/authorize",
                "https://appleid.apple.com/auth/token", "https://appleid.apple.com/auth/keys"),
        TELEGRAM("https://oauth.telegram.org", "https://oauth.telegram.org/auth",
                "https://oauth.telegram.org/token", "https://oauth.telegram.org/.well-known/jwks.json");

        final String issuer, authorization, token, jwks;
        Provider(String issuer, String authorization, String token, String jwks) {
            this.issuer = issuer; this.authorization = authorization; this.token = token; this.jwks = jwks;
        }
        String key() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public record Identity(String subject, String displayName) {}
    private static final Logger log = LoggerFactory.getLogger(OAuthProviderClient.class);
    private static final List<String> OAUTH_ERROR_CODES = List.of("invalid_request", "invalid_client", "invalid_grant",
            "unauthorized_client", "unsupported_grant_type", "invalid_scope", "access_denied", "server_error",
            "temporarily_unavailable");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Environment environment;
    private final RestTemplate http;
    private final Map<Provider, JwtDecoder> decoders = new ConcurrentHashMap<>();

    public OAuthProviderClient(Environment environment) {
        this.environment = environment;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = new RestTemplate(factory);
    }

    public List<String> available() {
        if (!enabled(environment)) return List.of();
        return java.util.Arrays.stream(Provider.values()).filter(this::configured).map(Enum::name).toList();
    }

    static boolean enabled(Environment environment) {
        return "true".equalsIgnoreCase(environment.getProperty("oauth.enabled", "false"));
    }

    public Provider provider(String value) {
        try { return Provider.valueOf(value); }
        catch (RuntimeException exception) { throw new BizException(422, "OAUTH_REQUEST_INVALID"); }
    }

    public String publicBaseUrl() {
        String base = environment.getProperty("oauth.public-base-url", "").replaceAll("/+$", "");
        try {
            URI uri = URI.create(base);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException exception) { throw new BizException(503, "OAUTH_PROVIDER_NOT_CONFIGURED"); }
        return base;
    }

    private String property(Provider provider, String name) {
        return environment.getProperty("oauth." + provider.key() + "." + name, "").trim();
    }

    private boolean configured(Provider provider) {
        try {
            publicBaseUrl();
            h5Origin();
            if (provider == Provider.APPLE && AppleClientSecret.usesSigningKey(environment))
                return AppleClientSecret.configured(environment);
            return !property(provider, "client-id").isEmpty() && !property(provider, "client-secret").isEmpty();
        } catch (BizException exception) { return false; }
    }

    public String h5Origin() {
        String value = environment.getProperty("oauth.h5-origin", "").trim();
        try {
            URI uri = URI.create(value);
            boolean local = UserAuthEnvironment.hasSingleActiveProfile(environment, "dev")
                    && "http".equals(uri.getScheme()) && List.of("localhost", "127.0.0.1").contains(uri.getHost());
            if ((!local && !"https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty())
                throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException exception) { throw new BizException(503, "OAUTH_PROVIDER_NOT_CONFIGURED"); }
    }

    public void requireConfigured(Provider provider) {
        if (!enabled(environment)) throw new BizException(503, "OAUTH_DISABLED");
        if (!configured(provider)) throw new BizException(503, "OAUTH_PROVIDER_NOT_CONFIGURED");
    }

    public String callback(Provider provider) {
        return publicBaseUrl() + "/auth/users/oauth/provider/callback/" + provider.name();
    }

    public String authorizationUrl(Provider provider, String state, String nonce, String verifier) {
        requireConfigured(provider);
        var uri = UriComponentsBuilder.fromHttpUrl(provider.authorization)
                .queryParam("client_id", property(provider, "client-id"))
                .queryParam("redirect_uri", callback(provider)).queryParam("response_type", "code")
                .queryParam("scope", provider == Provider.APPLE ? "email" : "openid profile")
                .queryParam("state", state).queryParam("nonce", nonce);
        if (provider == Provider.APPLE) {
            // Apple uses a signed client secret and form_post; it does not advertise PKCE.
            uri.queryParam("response_mode", "form_post");
        } else {
            uri.queryParam("code_challenge", hash(verifier)).queryParam("code_challenge_method", "S256");
        }
        return uri.build().encode().toUriString();
    }

    public Identity exchange(Provider provider, String code, String nonce, String verifier) {
        requireConfigured(provider);
        if (code == null || code.isBlank() || code.length() > 4096)
            throw new BizException(401, "OAUTH_AUTHORIZATION_INVALID");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", callback(provider));
        form.add("client_id", property(provider, "client-id"));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (provider == Provider.TELEGRAM) {
            headers.setBasicAuth(property(provider, "client-id"), property(provider, "client-secret"));
        } else {
            form.add("client_secret", provider == Provider.APPLE && AppleClientSecret.usesSigningKey(environment)
                    ? AppleClientSecret.create(environment, Instant.now()) : property(provider, "client-secret"));
        }
        if (provider != Provider.APPLE) form.add("code_verifier", verifier);
        String stage = "TOKEN_EXCHANGE";
        String category = "REQUEST_FAILED";
        String httpStatus = "none";
        Jwt jwt = null;
        try {
            var response = http.postForEntity(provider.token, new HttpEntity<>(form, headers), Map.class);
            httpStatus = Integer.toString(response.getStatusCode().value());
            stage = "TOKEN_RESPONSE";
            category = "ID_TOKEN_MISSING_OR_INVALID";
            Map<?, ?> body = response.getBody();
            Object raw = body == null ? null : body.get("id_token");
            if (!(raw instanceof String token) || token.length() > 32768) {
                if (body != null && body.get("error") instanceof String) category = "PROVIDER_ERROR";
                logTokenResponse(provider, body, raw, httpStatus);
                throw new IllegalArgumentException("Missing ID token");
            }
            stage = "ID_TOKEN_DECODE";
            category = "DECODER_FAILURE";
            httpStatus = "none";
            jwt = decoders.computeIfAbsent(provider, this::decoder).decode(token);
            stage = "IDENTITY_CLAIMS";
            category = "CLAIMS_REJECTED";
            return validateIdentity(jwt, provider.issuer, property(provider, "client-id"), nonce);
        } catch (RuntimeException exception) {
            if (exception instanceof RestClientResponseException responseException) {
                category = "HTTP_ERROR";
                httpStatus = Integer.toString(responseException.getStatusCode().value());
            } else if (exception instanceof ResourceAccessException) {
                category = "TRANSPORT_ERROR";
            } else if (exception instanceof JwtValidationException) {
                category = "JWT_CLAIMS_REJECTED";
            } else if (exception instanceof BadJwtException) {
                category = "JWT_REJECTED";
            }
            // Only fixed labels and status codes: never log the exception, message or provider payload.
            log.warn("OAUTH_PROVIDER_REJECTED provider={} stage={} category={} http_status={}",
                    provider.name(), stage, category, httpStatus);
            if ("IDENTITY_CLAIMS".equals(stage)) logIdentityChecks(provider, jwt, nonce);
            throw new BizException(401, "OAUTH_AUTHORIZATION_INVALID");
        }
    }

    private void logTokenResponse(Provider provider, Map<?, ?> body, Object token, String httpStatus) {
        Map<?, ?> fields = body == null ? Map.of() : body;
        Object error = fields.get("error");
        String errorCode = error == null ? "NONE"
                : error instanceof String value && OAUTH_ERROR_CODES.contains(value) ? value : "OTHER";
        log.warn("OAUTH_PROVIDER_TOKEN_SCHEMA provider={} stage=TOKEN_RESPONSE category=SCHEMA http_status={}"
                        + " body_present={} id_token_present={} id_token_kind={} id_token_length={}"
                        + " access_token_present={} token_type_present={} expires_in_present={}"
                        + " error_present={} error_kind={} error_code={} error_description_present={}"
                        + " data_present={} result_present={} ok_present={}",
                provider.name(), httpStatus, body != null, fields.containsKey("id_token"),
                tokenFieldKind(token, fields.containsKey("id_token")), token instanceof String value ? value.length() : -1,
                fields.containsKey("access_token"), fields.containsKey("token_type"), fields.containsKey("expires_in"),
                fields.containsKey("error"), tokenFieldKind(error, fields.containsKey("error")), errorCode,
                fields.containsKey("error_description"), fields.containsKey("data"), fields.containsKey("result"), fields.containsKey("ok"));
    }

    private static String tokenFieldKind(Object value, boolean present) {
        if (!present) return "MISSING";
        if (value == null) return "NULL";
        if (value instanceof String) return "STRING";
        if (value instanceof Number) return "NUMBER";
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Map) return "OBJECT";
        if (value instanceof List) return "ARRAY";
        return "OTHER";
    }

    private void logIdentityChecks(Provider provider, Jwt jwt, String nonce) {
        try {
            Instant now = Instant.now();
            String audience = property(provider, "client-id");
            String subject = jwt.getSubject();
            String actualNonce = jwt.getClaimAsString("nonce");
            log.warn("OAUTH_PROVIDER_CLAIM_CHECKS provider={} stage=IDENTITY_CLAIMS category=CHECKS http_status=none"
                            + " issuer_valid={} audience_valid={} azp_valid={} expiry_valid={} issued_at_valid={}"
                            + " nonce_present={} nonce_match={} subject_valid={}",
                    provider.name(), provider.issuer.equals(jwt.getClaimAsString("iss")),
                    jwt.getAudience().contains(audience),
                    jwt.getAudience().size() <= 1 || audience.equals(jwt.getClaimAsString("azp")),
                    jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(now),
                    jwt.getIssuedAt() != null && !jwt.getIssuedAt().isAfter(now.plusSeconds(60)),
                    actualNonce != null, constantEquals(nonce, actualNonce),
                    subject != null && !subject.isBlank() && subject.length() <= 512);
        } catch (RuntimeException exception) {
            log.warn("OAUTH_PROVIDER_CLAIM_CHECKS provider={} stage=IDENTITY_CLAIMS"
                    + " category=CLAIM_FORMAT_REJECTED http_status=none", provider.name());
        }
    }

    private JwtDecoder decoder(Provider provider) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(provider.jwks).restOperations(http).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(provider.issuer));
        return decoder;
    }

    static Identity validateIdentity(Jwt jwt, String issuer, String audience, String nonce) {
        Instant now = Instant.now();
        String subject = jwt.getSubject();
        if (!issuer.equals(jwt.getClaimAsString("iss")) || !jwt.getAudience().contains(audience)
                || jwt.getAudience().size() > 1 && !audience.equals(jwt.getClaimAsString("azp"))
                || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
                || jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(now.plusSeconds(60))
                || !constantEquals(nonce, jwt.getClaimAsString("nonce"))
                || subject == null || subject.isBlank() || subject.length() > 512)
            throw new BizException(401, "OAUTH_AUTHORIZATION_INVALID");
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) name = jwt.getClaimAsString("email");
        if (name == null || name.isBlank()) name = "Account";
        name = name.replaceAll("[\\p{Cntrl}]", "");
        // Hash the case-sensitive subject before using the existing case-insensitive SQL identity index.
        return new Identity("oidc:" + java.util.HexFormat.of().formatHex(
                Base64.getUrlDecoder().decode(hash(issuer + "\n" + subject))),
                name.substring(0, Math.min(128, name.length())));
    }

    static String random() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    static boolean constantEquals(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
