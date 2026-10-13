package ffdd.opsconsole.promotion.web;

import ffdd.opsconsole.promotion.application.PromotionBannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PromotionBannerController {
    private final PromotionBannerService banners;

    @GetMapping("/api/promotions/{activityId}/banner/{version}")
    public ResponseEntity<byte[]> image(@PathVariable String activityId, @PathVariable long version) {
        var image = banners.read(activityId, version);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(image.bytes());
    }
}
