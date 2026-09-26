package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class StorefrontSkuImageService {
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final long MAX_URL_SECONDS = 900;
    private static final byte[] KEY_CONTEXT = "nexgrid:storefront-sku-image:v1".getBytes(StandardCharsets.UTF_8);

    private final AppTradeinMapper mapper;
    private final ObjectStorageService storage;
    private final StorageProperties properties;
    private final Clock clock;

    @Autowired
    public StorefrontSkuImageService(AppTradeinMapper mapper, ObjectStorageService storage,
                                    StorageProperties properties) {
        this(mapper, storage, properties, Clock.systemUTC());
    }

    StorefrontSkuImageService(AppTradeinMapper mapper, ObjectStorageService storage,
                             StorageProperties properties, Clock clock) {
        this.mapper = mapper;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    public String issueUrl(String productNo, String assetId, String objectKey, Duration expiry) {
        if (!validProductNo(productNo) || !ManagedSkuMediaIdentity.isCanonicalPair(assetId, objectKey)
                || !ManagedSkuMediaIdentity.isApprovedImageObjectKey(objectKey)) {
            throw new IllegalArgumentException("STORE_IMAGE_IDENTITY_INVALID");
        }
        String origin = publicOrigin();
        long seconds = expiry == null ? MAX_URL_SECONDS : Math.min(expiry.toSeconds(), MAX_URL_SECONDS);
        if (seconds <= 0) throw new IllegalArgumentException("STORE_IMAGE_EXPIRY_INVALID");
        long expires = clock.instant().getEpochSecond() + seconds;
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac(signingKey(), payload(productNo, assetId, objectKey, expires)));
        return origin + "/api/store/media/images/" + productNo + "/" + assetId
                + "?expires=" + expires + "&signature=" + signature;
    }

    public ImageBytes read(String productNo, String assetId, String rawExpires, String rawSignature) {
        if (!validProductNo(productNo) || assetId == null || assetId.length() > 240
                || rawExpires == null || !rawExpires.matches("[0-9]{1,12}")
                || rawSignature == null || !rawSignature.matches("[A-Za-z0-9_-]{43}")) {
            throw unavailable();
        }
        String objectKey;
        long expires;
        byte[] suppliedSignature;
        try {
            objectKey = new String(Base64.getUrlDecoder().decode(assetId), StandardCharsets.UTF_8);
            expires = Long.parseLong(rawExpires);
            suppliedSignature = Base64.getUrlDecoder().decode(rawSignature);
        } catch (IllegalArgumentException ex) {
            throw unavailable();
        }
        long now = clock.instant().getEpochSecond();
        if (!ManagedSkuMediaIdentity.isCanonicalPair(assetId, objectKey)
                || !ManagedSkuMediaIdentity.isApprovedImageObjectKey(objectKey)
                || expires <= now || expires > now + MAX_URL_SECONDS
                || !MessageDigest.isEqual(suppliedSignature,
                    mac(signingKey(), payload(productNo, assetId, objectKey, expires)))) {
            throw unavailable();
        }
        if (!Integer.valueOf(1).equals(mapper.currentStorefrontImage(productNo, assetId, objectKey))) {
            throw unavailable();
        }
        String contentType = contentType(objectKey);
        byte[] bytes;
        try (InputStream stream = storage.get(objectKey)) {
            bytes = stream.readNBytes(MAX_IMAGE_BYTES + 1);
        } catch (IOException | RuntimeException ex) {
            throw new BizException(503, "STORE_IMAGE_UNAVAILABLE");
        }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES || !matchesImageType(contentType, bytes)) {
            throw unavailable();
        }
        return new ImageBytes(contentType, bytes);
    }

    private String publicOrigin() {
        String raw = properties.getPublicMediaOrigin();
        if (!StringUtils.hasText(raw)) throw new IllegalStateException("STORE_IMAGE_PUBLIC_ORIGIN_REQUIRED");
        URI uri = URI.create(raw.trim());
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(host)
                || "localhost".equalsIgnoreCase(host) || host.startsWith("127.") || "::1".equals(host)
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("STORE_IMAGE_PUBLIC_ORIGIN_INVALID");
        }
        return uri.toString().replaceAll("/+$", "");
    }

    private byte[] signingKey() {
        if (!StringUtils.hasText(properties.getSecretKey())) {
            throw new IllegalStateException("STORE_IMAGE_SIGNING_KEY_UNAVAILABLE");
        }
        return mac(properties.getSecretKey().getBytes(StandardCharsets.UTF_8), KEY_CONTEXT);
    }

    private byte[] mac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("STORE_IMAGE_SIGNING_UNAVAILABLE", ex);
        }
    }

    private byte[] payload(String productNo, String assetId, String objectKey, long expires) {
        return ("v1\nGET\n" + productNo + "\n" + assetId + "\n" + objectKey + "\n" + expires)
                .getBytes(StandardCharsets.UTF_8);
    }

    private boolean validProductNo(String productNo) {
        return productNo != null && productNo.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    }

    private String contentType(String objectKey) {
        if (objectKey.endsWith(".png")) return "image/png";
        if (objectKey.endsWith(".jpg") || objectKey.endsWith(".jpeg")) return "image/jpeg";
        if (objectKey.endsWith(".webp")) return "image/webp";
        if (objectKey.endsWith(".gif")) return "image/gif";
        throw unavailable();
    }

    private boolean matchesImageType(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/png" -> startsWith(bytes, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> startsWith(bytes, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "image/webp" -> startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && bytes.length >= 12 && startsWith(bytes, "WEBP".getBytes(StandardCharsets.US_ASCII), 8);
            case "image/gif" -> startsWith(bytes, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                    || startsWith(bytes, "GIF89a".getBytes(StandardCharsets.US_ASCII));
            default -> false;
        };
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        return startsWith(bytes, prefix, 0);
    }

    private boolean startsWith(byte[] bytes, byte[] prefix, int offset) {
        if (bytes.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[offset + i] != prefix[i]) return false;
        }
        return true;
    }

    private BizException unavailable() {
        return new BizException(404, "STORE_IMAGE_UNAVAILABLE");
    }

    public record ImageBytes(String contentType, byte[] bytes) {}
}
