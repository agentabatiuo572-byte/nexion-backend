package ffdd.opsconsole.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.bouncycastle.asn1.ASN1Boolean;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/** Verifies an installation key, not benchmark performance or immutable hardware identity. */
@Component
@RequiredArgsConstructor
public class AndroidPhoneAttestationVerifier {
    private static final String OID = "1.3.6.1.4.1.11129.2.1.17";
    private final Environment environment;
    private final Clock clock;
    private final AtomicReference<Trust> trust = new AtomicReference<>();
    record Trust(List<X509Certificate> roots, Set<String> revoked, long fetchedAt) { }

    public String verify(List<String> encodedChain, String nonce, String payload, String encodedSignature, String knownKey) {
        String packageName = environment.getProperty("nexion.phone.attestation.package-name", "").trim();
        Set<String> signers = new HashSet<>(Arrays.asList(environment.getProperty(
                "nexion.phone.attestation.signer-sha256", "").toLowerCase().replace(":", "").split(",")));
        signers.removeIf(value -> !value.matches("[0-9a-f]{64}"));
        if (packageName.isBlank() || signers.isEmpty()) throw new BizException(503, "PHONE_NATIVE_PROOF_NOT_CONFIGURED");
        if (encodedChain == null || encodedChain.size() < 2 || encodedChain.size() > 8
                || encodedSignature == null || encodedSignature.length() > 1024) invalid();
        try {
            var factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (String encoded : encodedChain) {
                if (encoded == null || encoded.length() > 16384) invalid();
                var certificate = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
                certificate.checkValidity(Date.from(clock.instant()));
                chain.add(certificate);
            }
            Trust trusted = trust();
            X509Certificate root = chain.get(chain.size() - 1);
            Set<TrustAnchor> anchors = new HashSet<>();
            for (var allowed : trusted.roots()) if (Arrays.equals(root.getEncoded(), allowed.getEncoded())) {
                anchors.add(new TrustAnchor(allowed, null));
            }
            if (anchors.isEmpty()) invalid();
            for (var certificate : chain) if (trusted.revoked().contains(certificate.getSerialNumber().toString(16))) invalid();
            var parameters = new PKIXParameters(anchors);
            parameters.setDate(Date.from(clock.instant()));
            // Android publishes its own serial-number revocation list, checked above.
            parameters.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(chain.subList(0, chain.size() - 1)), parameters);
            int attestedIndex = -1;
            for (int i = chain.size() - 1; i >= 0; i--) {
                if (chain.get(i).getExtensionValue(OID) != null) { attestedIndex = i; break; }
            }
            // Reject attacker-appended leaf certificates; the signed key must be the attested key.
            if (attestedIndex != 0) invalid();
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(chain.get(0).getPublicKey().getEncoded()));
            if (knownKey != null && !knownKey.equals(key)) throw new BizException(409, "PHONE_NATIVE_KEY_CHANGED");
            validateExtension(chain.get(0).getExtensionValue(OID), knownKey == null ? nonce : null, packageName, signers);
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(chain.get(0).getPublicKey());
            signature.update(Base64.getDecoder().decode(payload));
            if (!signature.verify(Base64.getDecoder().decode(encodedSignature))) invalid();
            return key;
        } catch (BizException exception) { throw exception; }
        catch (Exception invalid) { throw new BizException(403, "PHONE_NATIVE_PROOF_INVALID"); }
    }

    static void validateExtension(byte[] extension, String nonce, String packageName, Set<String> signers) {
        ASN1Sequence key = ASN1Sequence.getInstance(ASN1OctetString.getInstance(extension).getOctets());
        if (key.size() != 8 || !Set.of(1,2).contains(ASN1Enumerated.getInstance(key.getObjectAt(1)).intValueExact())
                || !Set.of(1,2).contains(ASN1Enumerated.getInstance(key.getObjectAt(3)).intValueExact())) invalid();
        if (nonce != null && !MessageDigest.isEqual(Base64.getDecoder().decode(nonce),
                ASN1OctetString.getInstance(key.getObjectAt(4)).getOctets())) invalid();
        ASN1Sequence software = ASN1Sequence.getInstance(key.getObjectAt(6));
        ASN1Sequence hardware = ASN1Sequence.getInstance(key.getObjectAt(7));
        ASN1Sequence boot = ASN1Sequence.getInstance(tag(hardware,704));
        if (boot == null || boot.size() < 3 || !ASN1Boolean.getInstance(boot.getObjectAt(1)).isTrue()
                || ASN1Enumerated.getInstance(boot.getObjectAt(2)).intValueExact() != 0) invalid();
        ASN1Encodable rawApp = tag(software,709);
        if (rawApp == null) rawApp = tag(hardware,709);
        if (rawApp == null) invalid();
        ASN1Sequence app = ASN1Sequence.getInstance(ASN1OctetString.getInstance(rawApp).getOctets());
        if (app.size() != 2) invalid();
        ASN1Set packages = ASN1Set.getInstance(app.getObjectAt(0));
        ASN1Set digests = ASN1Set.getInstance(app.getObjectAt(1));
        if (packages.size() != 1 || digests.size() < 1) invalid();
        ASN1Sequence packageEntry = ASN1Sequence.getInstance(packages.getObjectAt(0));
        if (!packageName.equals(new String(ASN1OctetString.getInstance(packageEntry.getObjectAt(0)).getOctets(), StandardCharsets.UTF_8))) invalid();
        for (ASN1Encodable digest : digests) {
            if (!signers.contains(HexFormat.of().formatHex(ASN1OctetString.getInstance(digest).getOctets()))) invalid();
        }
    }
    private static ASN1Encodable tag(ASN1Sequence list, int id) {
        ASN1Encodable found = null;
        for (ASN1Encodable value : list) {
            ASN1TaggedObject tagged = ASN1TaggedObject.getInstance(value);
            if (tagged.getTagNo() == id) {
                if (found != null) invalid();
                found = tagged.getExplicitBaseObject();
            }
        }
        return found;
    }
    private synchronized Trust trust() {
        Trust cached = trust.get();
        if (cached != null && clock.millis() - cached.fetchedAt() < 3600000) return cached;
        try {
            ObjectMapper json = new ObjectMapper();
            var rootJson = json.readTree(fetch("https://android.googleapis.com/attestation/root"));
            var revocationJson = json.readTree(fetch("https://android.googleapis.com/attestation/status"));
            if (!rootJson.isArray() || rootJson.isEmpty() || !revocationJson.path("entries").isObject()) throw new IllegalStateException();
            var factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> roots = new ArrayList<>();
            for (var pem : rootJson) roots.add((X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(pem.asText().getBytes(StandardCharsets.US_ASCII))));
            Set<String> revoked = new HashSet<>();
            revocationJson.get("entries").fieldNames().forEachRemaining(value -> revoked.add(value.toLowerCase()));
            Trust refreshed = new Trust(List.copyOf(roots), Set.copyOf(revoked), clock.millis());
            trust.set(refreshed);
            return refreshed;
        } catch (Exception unavailable) { throw new BizException(503, "PHONE_NATIVE_TRUST_UNAVAILABLE"); }
    }
    private String fetch(String url) throws Exception {
        var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body().length() > 2000000) throw new IllegalStateException();
        return response.body();
    }
    private static void invalid() { throw new BizException(403, "PHONE_NATIVE_PROOF_INVALID"); }
}
