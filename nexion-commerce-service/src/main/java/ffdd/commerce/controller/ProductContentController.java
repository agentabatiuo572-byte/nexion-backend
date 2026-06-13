package ffdd.commerce.controller;

import ffdd.commerce.domain.PriceIndex;
import ffdd.commerce.domain.ProductFaq;
import ffdd.commerce.domain.ProductReview;
import ffdd.commerce.domain.ProductSpec;
import ffdd.commerce.dto.PriceIndexRequest;
import ffdd.commerce.dto.ProductFaqRequest;
import ffdd.commerce.dto.ProductReviewRequest;
import ffdd.commerce.dto.ProductSpecRequest;
import ffdd.commerce.dto.StoreProductContentResponse;
import ffdd.commerce.service.ProductContentService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductContentController {
    private final ProductContentService service;

    public ProductContentController(ProductContentService service) {
        this.service = service;
    }

    @GetMapping("/commerce/app/store/products/{productId}/content")
    public ApiResult<StoreProductContentResponse> appContent(@PathVariable Long productId) {
        return ApiResult.ok(service.appContent(productId));
    }

    @GetMapping("/commerce/app/store/products/{productId}/reviews")
    public ApiResult<PageResult<ProductReview>> appReviews(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "20") Long pageSize) {
        return ApiResult.ok(service.pageReviews(productId, true, null, pageNum, pageSize));
    }

    @GetMapping("/commerce/app/price-index")
    public ApiResult<List<PriceIndex>> appPriceIndex() {
        return ApiResult.ok(service.listPriceIndex(true));
    }

    @GetMapping("/commerce/product-reviews")
    public ApiResult<PageResult<ProductReview>> reviews(
            @RequestParam Long productId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "20") Long pageSize) {
        return ApiResult.ok(service.pageReviews(productId, false, status, pageNum, pageSize));
    }

    @PostMapping("/commerce/product-reviews")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<ProductReview> createReview(@Valid @RequestBody ProductReviewRequest request) {
        return ApiResult.ok(service.saveReview(null, request));
    }

    @PostMapping("/commerce/app/product-reviews")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<ProductReview> submitAppReview(@Valid @RequestBody ProductReviewRequest request) {
        request.setUserId(currentRoleUserId());
        return ApiResult.ok(service.submitAppReview(request));
    }

    @GetMapping("/commerce/app/product-reviews/by-order/{orderNo}")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<ProductReview> appReviewByOrder(@PathVariable String orderNo) {
        return ApiResult.ok(service.findAppReviewByOrder(orderNo, currentRoleUserId()));
    }

    @PutMapping("/commerce/product-reviews/{id}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<ProductReview> updateReview(@PathVariable Long id, @Valid @RequestBody ProductReviewRequest request) {
        return ApiResult.ok(service.saveReview(id, request));
    }

    @DeleteMapping("/commerce/product-reviews/{id}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<Void> deleteReview(@PathVariable Long id) {
        service.deleteReview(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/commerce/product-faqs")
    public ApiResult<List<ProductFaq>> faqs(@RequestParam Long productId) {
        return ApiResult.ok(service.listFaqs(productId, false));
    }

    @PostMapping("/commerce/product-faqs")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<ProductFaq> createFaq(@Valid @RequestBody ProductFaqRequest request) {
        return ApiResult.ok(service.saveFaq(null, request));
    }

    @PutMapping("/commerce/product-faqs/{id}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<ProductFaq> updateFaq(@PathVariable Long id, @Valid @RequestBody ProductFaqRequest request) {
        return ApiResult.ok(service.saveFaq(id, request));
    }

    @DeleteMapping("/commerce/product-faqs/{id}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<Void> deleteFaq(@PathVariable Long id) {
        service.deleteFaq(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/commerce/product-specs")
    public ApiResult<List<ProductSpec>> specs(@RequestParam Long productId) {
        return ApiResult.ok(service.listSpecs(productId, false));
    }

    @PostMapping("/commerce/product-specs")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<ProductSpec> createSpec(@Valid @RequestBody ProductSpecRequest request) {
        return ApiResult.ok(service.saveSpec(null, request));
    }

    @PutMapping("/commerce/product-specs/{id}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<ProductSpec> updateSpec(@PathVariable Long id, @Valid @RequestBody ProductSpecRequest request) {
        return ApiResult.ok(service.saveSpec(id, request));
    }

    @DeleteMapping("/commerce/product-specs/{id}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<Void> deleteSpec(@PathVariable Long id) {
        service.deleteSpec(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/commerce/price-index")
    public ApiResult<List<PriceIndex>> priceIndex() {
        return ApiResult.ok(service.listPriceIndex(false));
    }

    @PostMapping("/commerce/price-index")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<PriceIndex> createPriceIndex(@Valid @RequestBody PriceIndexRequest request) {
        return ApiResult.ok(service.savePriceIndex(null, request));
    }

    @PutMapping("/commerce/price-index/{id}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<PriceIndex> updatePriceIndex(@PathVariable Long id, @Valid @RequestBody PriceIndexRequest request) {
        return ApiResult.ok(service.savePriceIndex(id, request));
    }

    @DeleteMapping("/commerce/price-index/{id}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<Void> deletePriceIndex(@PathVariable Long id) {
        service.deletePriceIndex(id);
        return ApiResult.ok(null);
    }

    private Long currentRoleUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) {
            throw new BizException("Authenticated user id is required");
        }
        String subject = String.valueOf(authentication.getPrincipal());
        if (!StringUtils.hasText(subject)) {
            throw new BizException("Authenticated user id is required");
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            throw new BizException("Authenticated user id is invalid");
        }
    }
}
