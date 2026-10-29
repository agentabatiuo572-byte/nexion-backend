package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.TrustDocumentService;
import ffdd.opsconsole.content.domain.TrustDocumentsView;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/content/trust/documents")
@RequiredArgsConstructor
public class AppTrustDocumentController {
    private final TrustDocumentService service;

    @GetMapping
    @PreAuthorize("permitAll()")
    public ApiResult<TrustDocumentsView> current() {
        return ApiResult.ok(service.published());
    }

    /** File URLs carry a content hash, so a short public cache never serves a replaced file under the same URL. */
    @GetMapping("/{slot}/file")
    @PreAuthorize("permitAll()")
    public ResponseEntity<byte[]> file(@PathVariable String slot) {
        return pdf(service.publicFile(slot), CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic());
    }

    @GetMapping("/{slot}/pages/{page}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<byte[]> page(@PathVariable String slot, @PathVariable int page) {
        byte[] image = service.page(slot, page);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).contentLength(image.length)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(image);
    }

    static ResponseEntity<byte[]> pdf(TrustDocumentService.Content content, CacheControl cacheControl) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(content.bytes().length)
                .cacheControl(cacheControl).header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline; filename=\"" + content.fileName() + "\"").body(content.bytes());
    }
}
