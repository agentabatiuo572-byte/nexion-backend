package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StorageProperties;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
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
    private final java.util.concurrent.Semaphore imageConversions = new java.util.concurrent.Semaphore(2);
    // Only small, immutable object-key renditions are retained (at most 16 MiB).
    // Product ownership and signed-link expiry are still checked on every request.
    private final Map<String, ImageBytes> renditions = new LinkedHashMap<>(16, 0.75f, true);

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
        ImageBytes cached;
        synchronized (renditions) {
            cached = renditions.get(objectKey);
            if (cached != null && cached.bytes().length > 0) return cached;
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
        // A zero-byte cache entry remembers that this immutable asset stays original.
        if (cached != null || !imageConversions.tryAcquire()) return new ImageBytes(contentType, bytes);
        ImageBytes image;
        try { image = compactImage(contentType, bytes); }
        finally { imageConversions.release(); }
        synchronized (renditions) {
            renditions.put(objectKey, image.bytes().length <= 1024 * 1024
                    ? image : new ImageBytes(contentType, new byte[0]));
            if (renditions.size() > 16) renditions.remove(renditions.keySet().iterator().next());
        }
        return image;
    }

    private ImageBytes compactImage(String contentType, byte[] bytes) {
        ImageBytes original = new ImageBytes(contentType, bytes);
        // Preserve animation, transparency, JPEG orientation and already-small images.
        if (bytes.length < 128 * 1024 || !"image/png".equals(contentType) || animatedPng(bytes)) {
            return original;
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return original;
            var reader = readers.next();
            BufferedImage decoded;
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 4_000_000) return original;
                decoded = reader.read(0);
            } finally { reader.dispose(); }
            if (decoded == null) return original;
            if (decoded.getColorModel().hasAlpha()) {
                for (int y = 0; y < decoded.getHeight(); y++) {
                    for (int x = 0; x < decoded.getWidth(); x++) {
                        if ((decoded.getRGB(x, y) >>> 24) != 255) return original;
                    }
                }
            }
            double scale = Math.min(1d, 960d / Math.max(decoded.getWidth(), decoded.getHeight()));
            BufferedImage resized = new BufferedImage(Math.max(1, (int) Math.round(decoded.getWidth() * scale)),
                    Math.max(1, (int) Math.round(decoded.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
            var graphics = resized.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(decoded, 0, 0, resized.getWidth(), resized.getHeight(), null);
            } finally { graphics.dispose(); }
            var output = new ByteArrayOutputStream();
            var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try (var stream = new MemoryCacheImageOutputStream(output)) {
                writer.setOutput(stream);
                var params = writer.getDefaultWriteParam();
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(0.86f);
                writer.write(null, new javax.imageio.IIOImage(resized, null, null), params);
            } finally { writer.dispose(); }
            byte[] compact = output.toByteArray();
            return compact.length < bytes.length ? new ImageBytes("image/jpeg", compact) : original;
        } catch (IOException | RuntimeException ex) {
            return original;
        }
    }

    private boolean animatedPng(byte[] bytes) {
        for (int offset = 8; offset + 12 <= bytes.length;) {
            int length = java.nio.ByteBuffer.wrap(bytes, offset, 4).getInt();
            if (length < 0 || (long) offset + length + 12 > bytes.length) return true;
            if (bytes[offset + 4] == 'a' && bytes[offset + 5] == 'c'
                    && bytes[offset + 6] == 'T' && bytes[offset + 7] == 'L') return true;
            offset += length + 12;
        }
        return false;
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
