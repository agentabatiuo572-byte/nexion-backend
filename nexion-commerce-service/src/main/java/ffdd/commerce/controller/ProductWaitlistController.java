package ffdd.commerce.controller;

import ffdd.commerce.domain.ProductWaitlist;
import ffdd.commerce.dto.ProductWaitlistQueryRequest;
import ffdd.commerce.service.ProductWaitlistService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductWaitlistController {
    private final ProductWaitlistService service;

    public ProductWaitlistController(ProductWaitlistService service) {
        this.service = service;
    }

    @PostMapping("/commerce/app/store/products/{productId}/waitlist")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<ProductWaitlist> join(@PathVariable Long productId) {
        return ApiResult.ok(service.joinProductWaitlist(productId, currentRoleUserId()));
    }

    @GetMapping("/commerce/product-waitlist")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ')")
    public ApiResult<PageResult<ProductWaitlist>> page(ProductWaitlistQueryRequest request) {
        return ApiResult.ok(service.pageWaitlist(request));
    }

    @DeleteMapping("/commerce/product-waitlist/{id}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<Void> delete(@PathVariable Long id) {
        service.deleteWaitlist(id);
        return ApiResult.ok(null);
    }

    private Long currentRoleUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) {
            throw new BizException("Authenticated user is required");
        }
        String subject = String.valueOf(authentication.getPrincipal());
        if (!StringUtils.hasText(subject)) {
            throw new BizException("Authenticated user id is invalid");
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            throw new BizException("Authenticated user id is invalid");
        }
    }
}
