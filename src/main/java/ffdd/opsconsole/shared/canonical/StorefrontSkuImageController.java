package ffdd.opsconsole.shared.canonical;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class StorefrontSkuImageController {
    private final StorefrontSkuImageService service;

    @GetMapping("/api/store/media/images/{productNo}/{assetId}")
    public ResponseEntity<byte[]> image(@PathVariable String productNo, @PathVariable String assetId,
                                        @RequestParam String expires, @RequestParam String signature) {
        StorefrontSkuImageService.ImageBytes image = service.read(productNo, assetId, expires, signature);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }
}
