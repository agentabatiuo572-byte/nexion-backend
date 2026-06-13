package ffdd.commerce.controller;

import ffdd.commerce.dto.ProductMediaUploadResponse;
import ffdd.commerce.service.ProductMediaService;
import ffdd.common.api.ApiResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping({"/commerce/product-media", "/commerce/products/media"})
public class ProductMediaController {
    private final ProductMediaService productMediaService;

    public ProductMediaController(ProductMediaService productMediaService) {
        this.productMediaService = productMediaService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<ProductMediaUploadResponse> upload(
            @RequestParam(defaultValue = "DETAIL") String mediaType,
            @RequestPart("file") MultipartFile file) {
        return ApiResult.ok(productMediaService.upload(mediaType, file));
    }

    @PostMapping("/app/product-review")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<ProductMediaUploadResponse> uploadAppProductReviewMedia(@RequestPart("file") MultipartFile file) {
        return ApiResult.ok(productMediaService.upload("PRODUCT_REVIEW", file));
    }

    @PostMapping("/app/user-avatar")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<ProductMediaUploadResponse> uploadAppUserAvatar(@RequestPart("file") MultipartFile file) {
        return ApiResult.ok(productMediaService.upload("USER_AVATAR", file));
    }

    @GetMapping("/preview-url")
    public ApiResult<ProductMediaUploadResponse> preview(@RequestParam String objectKey) {
        return ApiResult.ok(productMediaService.preview(objectKey));
    }
}
