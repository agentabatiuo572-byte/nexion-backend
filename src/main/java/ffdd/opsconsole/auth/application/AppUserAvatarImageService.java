package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.mapper.AppUserProfileMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Read capabilities for the current owner's avatar, never a generic object proxy. */
@Service
public class AppUserAvatarImageService {
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_URL_SECONDS = 24 * 60 * 60;
    private static final byte[] KEY_CONTEXT =
            "nexion:app-profile-avatar-image:v1".getBytes(StandardCharsets.UTF_8);

    private final AppUserProfileMapper mapper;
    private final ObjectStorageService storage;
    private final StorageProperties properties;
    private final Clock clock;

    @Autowired
    public AppUserAvatarImageService(AppUserProfileMapper mapper, ObjectStorageService storage,
                                    StorageProperties properties) {
        this(mapper, storage, properties, Clock.systemUTC());
    }

    AppUserAvatarImageService(AppUserProfileMapper mapper, ObjectStorageService storage,
                              StorageProperties properties, Clock clock) {
        this.mapper = mapper;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    public String issueUrl(Long userId, String objectKey) {
        if (!ownedAvatarKey(userId, objectKey)) {
            throw new IllegalArgumentException("USER_AVATAR_IMAGE_IDENTITY_INVALID");
        }
        String origin = publicOrigin();
        String revision = revision(objectKey);
        String asset = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectKey.getBytes(StandardCharsets.UTF_8));
        long expires = clock.instant().getEpochSecond() + MAX_URL_SECONDS;
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac(signingKey(), payload(userId, revision, objectKey, expires)));
        return origin + "/api/app/profile/avatar/image/" + userId + "/" + revision
                + "?asset=" + asset + "&expires=" + expires + "&signature=" + signature;
    }

    public ImageBytes read(String rawUserId, String revision, String asset,
                           String rawExpires, String rawSignature) {
        if (rawUserId == null || !rawUserId.matches("[1-9][0-9]{0,18}")
                || revision == null || !revision.matches("[a-f0-9]{64}")
                || asset == null || !asset.matches("[A-Za-z0-9_-]{1,160}")
                || rawExpires == null || !rawExpires.matches("[0-9]{1,12}")
                || rawSignature == null || !rawSignature.matches("[A-Za-z0-9_-]{43}")) {
            throw unavailable();
        }
        long userId;
        long expires;
        String objectKey;
        byte[] suppliedSignature;
        try {
            userId = Long.parseLong(rawUserId);
            expires = Long.parseLong(rawExpires);
            byte[] objectBytes = Base64.getUrlDecoder().decode(asset);
            objectKey = new String(objectBytes, StandardCharsets.UTF_8);
            suppliedSignature = Base64.getUrlDecoder().decode(rawSignature);
            if (!rawUserId.equals(String.valueOf(userId)) || !rawExpires.equals(String.valueOf(expires))
                    || !asset.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(objectBytes))
                    || !rawSignature.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(suppliedSignature))) {
                throw unavailable();
            }
        } catch (IllegalArgumentException ex) {
            throw unavailable();
        }
        long now = clock.instant().getEpochSecond();
        // Verify the signed scope before any user lookup, including nonexistent users.
        if (!ownedAvatarKey(userId, objectKey) || !revision(objectKey).equals(revision)
                || expires <= now || expires > now + MAX_URL_SECONDS
                || !MessageDigest.isEqual(suppliedSignature,
                        mac(signingKey(), payload(userId, revision, objectKey, expires)))) {
            throw unavailable();
        }
        java.util.Map<String, Object> current;
        try {
            current = mapper.profile(userId); // ACTIVE, not deleted, exactly this owner.
        } catch (RuntimeException ex) {
            throw new BizException(503, "USER_AVATAR_IMAGE_UNAVAILABLE");
        }
        if (current == null || !objectKey.equals(current.get("avatarObjectKey"))) {
            throw unavailable();
        }
        String contentType = contentType(objectKey);
        byte[] bytes;
        try (InputStream stream = storage.get(objectKey)) {
            bytes = stream.readNBytes(MAX_IMAGE_BYTES + 1);
        } catch (IOException | RuntimeException ex) {
            throw new BizException(503, "USER_AVATAR_IMAGE_UNAVAILABLE");
        }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES || !matchesImageType(contentType, bytes)) {
            throw unavailable();
        }
        return new ImageBytes(contentType, bytes);
    }

    private boolean ownedAvatarKey(Long userId, String objectKey) {
        return userId != null && userId > 0 && objectKey != null
                && objectKey.matches("users/" + userId + "/avatar/[a-f0-9]{32}\\.(png|jpg|webp)");
    }

    private String publicOrigin() {
        String raw = properties.getPublicMediaOrigin();
        if (!StringUtils.hasText(raw)) {
            throw new BizException(503, "USER_AVATAR_IMAGE_PUBLIC_ORIGIN_REQUIRED");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new BizException(503, "USER_AVATAR_IMAGE_PUBLIC_ORIGIN_INVALID");
        }
        String host = uri.getHost();
        String normalizedHost = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT)
                .replace("[", "").replace("]", "").replaceAll("\\.$", "");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(host)
                || normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")
                || normalizedHost.startsWith("127.") || normalizedHost.equals("::1")
                || normalizedHost.equals("0.0.0.0") || normalizedHost.startsWith("::ffff:127.")
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new BizException(503, "USER_AVATAR_IMAGE_PUBLIC_ORIGIN_INVALID");
        }
        return uri.toString().replaceAll("/+$", "");
    }

    private byte[] signingKey() {
        if (!StringUtils.hasText(properties.getSecretKey())) {
            throw new BizException(503, "USER_AVATAR_IMAGE_SIGNING_KEY_UNAVAILABLE");
        }
        return mac(properties.getSecretKey().getBytes(StandardCharsets.UTF_8), KEY_CONTEXT);
    }

    private byte[] payload(Long userId, String revision, String objectKey, long expires) {
        return ("v1\nGET\n" + userId + "\n" + revision + "\n" + objectKey + "\n" + expires)
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] mac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (java.security.GeneralSecurityException ex) {
            throw new BizException(503, "USER_AVATAR_IMAGE_SIGNING_UNAVAILABLE");
        }
    }

    private String revision(String objectKey) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(objectKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", ex);
        }
    }

    private String contentType(String objectKey) {
        if (objectKey.endsWith(".png")) return "image/png";
        if (objectKey.endsWith(".jpg")) return "image/jpeg";
        return "image/webp";
    }

    private boolean matchesImageType(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/png" -> startsWith(bytes,
                    new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> startsWith(bytes, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "image/webp" -> bytes.length >= 12
                    && startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }

    private boolean startsWith(byte[] bytes, byte[] magic) {
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (bytes[i] != magic[i]) return false;
        return true;
    }

    private BizException unavailable() {
        return new BizException(404, "USER_AVATAR_IMAGE_UNAVAILABLE");
    }

    public record ImageBytes(String contentType, byte[] bytes) {}
}
