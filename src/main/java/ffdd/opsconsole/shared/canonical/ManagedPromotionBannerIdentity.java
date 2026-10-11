package ffdd.opsconsole.shared.canonical;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

/** Promotion artwork is separate from private evidence and E1 product media. */
public final class ManagedPromotionBannerIdentity {
    private static final long MAX_IMAGE_PIXELS = 16_000_000L;
    private static final Pattern KEY = Pattern.compile("admin/growth/promotion-banner/\\d{8}/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(?:jpg|png)");
    private ManagedPromotionBannerIdentity() {}

    public static String objectKey(String assetId) {
        if (assetId == null || assetId.length() > 240) return null;
        try {
            String key = new String(Base64.getUrlDecoder().decode(assetId), StandardCharsets.UTF_8);
            return KEY.matcher(key).matches() && assetId.equals(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(key.getBytes(StandardCharsets.UTF_8))) ? key : null;
        } catch (IllegalArgumentException rejected) {
            return null;
        }
    }

    public static String contentType(String key) {
        return key.endsWith(".png") ? "image/png" : "image/jpeg";
    }

    public static boolean matchesImageType(String type, byte[] bytes) {
        return switch (type) {
            case "image/png" -> startsWith(bytes, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, 0)
                    && startsWith(bytes, new byte[] {0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, (byte) 0xae, 0x42, 0x60, (byte) 0x82}, bytes.length - 12)
                    && decodesAs(bytes, "png");
            case "image/jpeg" -> startsWith(bytes, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}, 0)
                    && startsWith(bytes, new byte[] {(byte) 0xff, (byte) 0xd9}, bytes.length - 2) && decodesAs(bytes, "JPEG");
            default -> false;
        };
    }

    private static boolean decodesAs(byte[] bytes, String format) {
        ImageReader reader = null;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return false;
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return false;
            reader = readers.next();
            reader.setInput(input, true, true);
            if (!format.equalsIgnoreCase(reader.getFormatName()) || !safeDimensions(reader.getWidth(0), reader.getHeight(0))) return false;
            return reader.read(0) != null;
        } catch (IOException | RuntimeException rejected) {
            return false;
        } finally {
            if (reader != null) reader.dispose();
        }
    }

    private static boolean safeDimensions(int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= MAX_IMAGE_PIXELS;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix, int offset) {
        if (offset < 0 || bytes.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[offset + i] != prefix[i]) return false;
        return true;
    }
}
