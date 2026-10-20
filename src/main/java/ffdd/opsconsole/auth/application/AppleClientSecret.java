package ffdd.opsconsole.auth.application;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.core.env.Environment;

/** Short-lived client authentication, signed when needed; no expiring secret cache or scheduler. */
final class AppleClientSecret {
    private AppleClientSecret() {}

    static boolean usesSigningKey(Environment env) {
        return !value(env, "team-id").isEmpty() || !value(env, "key-id").isEmpty()
                || !value(env, "private-key-file").isEmpty();
    }

    static boolean configured(Environment env) {
        try {
            privateKey(env);
            return true;
        } catch (BizException exception) {
            return false;
        }
    }

    static String create(Environment env, Instant now) {
        try {
            var key = privateKey(env);
            var claims = new JWTClaimsSet.Builder()
                    .issuer(value(env, "team-id")).subject(value(env, "client-id"))
                    .audience("https://appleid.apple.com")
                    .issueTime(Date.from(now.minusSeconds(30)))
                    .expirationTime(Date.from(now.plusSeconds(300))).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
                    .keyID(value(env, "key-id")).build(), claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (Exception exception) {
            // Do not expose private-key paths, contents or a generated client secret.
            throw new BizException(503, "OAUTH_PROVIDER_NOT_CONFIGURED");
        }
    }

    private static ECPrivateKey privateKey(Environment env) {
        try {
            if (!value(env, "team-id").matches("[A-Z0-9]{10}")
                    || !value(env, "key-id").matches("[A-Z0-9]{10}")
                    || value(env, "client-id").isEmpty() || value(env, "private-key-file").isEmpty())
                throw new IllegalArgumentException();
            var path = Path.of(value(env, "private-key-file"));
            if (!path.isAbsolute()) throw new IllegalArgumentException();
            String pem = Files.readString(path).replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            var key = (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            if (!key.getParams().getOrder().equals(Curve.P_256.toECParameterSpec().getOrder()))
                throw new IllegalArgumentException();
            return key;
        } catch (Exception exception) {
            throw new BizException(503, "OAUTH_PROVIDER_NOT_CONFIGURED");
        }
    }

    private static String value(Environment env, String name) {
        return env.getProperty("oauth.apple." + name, "").trim();
    }
}
