package ffdd.opsconsole.shared.canonical;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedPromotionBannerIdentityTest {
    @Test void jpegMustDecodeAndIncludeItsCompleteImageEnd() throws Exception {
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpeg", output));
        var jpeg = output.toByteArray();
        assertTrue(ManagedPromotionBannerIdentity.matchesImageType("image/jpeg", jpeg));
        assertFalse(ManagedPromotionBannerIdentity.matchesImageType("image/jpeg", Arrays.copyOf(jpeg, 3)));
        assertFalse(ManagedPromotionBannerIdentity.matchesImageType("image/jpeg", Arrays.copyOf(jpeg, jpeg.length - 2)));
        assertFalse(ManagedPromotionBannerIdentity.matchesImageType("image/png", jpeg));
    }

    @Test void promotionBannerRejectsWebpIncludingValidAndMalformedEncodedPayloads() {
        var webp = Base64.getDecoder().decode("UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAUAmJaQAA3AA/vz0AAA=");
        assertFalse(ManagedPromotionBannerIdentity.matchesImageType("image/webp", webp));
        var malformed = new byte[] {82,73,70,70,18,0,0,0,87,69,66,80,86,80,56,76,6,0,0,0,47,0,0,0,0,0};
        assertFalse(ManagedPromotionBannerIdentity.matchesImageType("image/webp", malformed));
        assertFalse(ManagedPromotionBannerIdentity.matchesImageType("image/png", webp));
        assertFalse(ManagedPromotionBannerIdentity.matchesImageType("image/jpeg", webp));
    }
}
