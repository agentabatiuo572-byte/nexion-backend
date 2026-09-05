package ffdd.opsconsole.shared.canonical;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** Canonical identity rules shared by the E1 writer and App catalog reader. */
public final class ManagedSkuMediaIdentity {
    private static final Pattern IMAGE_OBJECT_KEY = Pattern.compile(
            "admin/e/sku-image/\\d{8}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|jpeg|png|webp|gif)");
    private static final Pattern VIDEO_OBJECT_KEY = Pattern.compile(
            "admin/e/sku-video/\\d{8}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:mp4|webm|mov)");

    private ManagedSkuMediaIdentity() {}

    public static boolean isCanonicalPair(String assetId, String objectKey) {
        boolean hasAssetId = StringUtils.hasText(assetId);
        boolean hasObjectKey = StringUtils.hasText(objectKey);
        if (!hasAssetId && !hasObjectKey) return true;
        if (!hasAssetId || !hasObjectKey) return false;
        String normalizedObjectKey = objectKey.trim();
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(assetId.trim()), StandardCharsets.UTF_8);
            return normalizedObjectKey.equals(decoded) && isApprovedObjectKey(normalizedObjectKey);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static boolean isApprovedObjectKey(String objectKey) {
        return isApprovedImageObjectKey(objectKey) || isApprovedVideoObjectKey(objectKey);
    }

    public static boolean isApprovedImageObjectKey(String objectKey) {
        return StringUtils.hasText(objectKey) && IMAGE_OBJECT_KEY.matcher(objectKey.trim()).matches();
    }

    public static boolean isApprovedVideoObjectKey(String objectKey) {
        return StringUtils.hasText(objectKey) && VIDEO_OBJECT_KEY.matcher(objectKey.trim()).matches();
    }
}
