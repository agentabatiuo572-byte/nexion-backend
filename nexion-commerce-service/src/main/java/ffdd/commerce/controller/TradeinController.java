package ffdd.commerce.controller;

import ffdd.commerce.domain.TradeinApplication;
import ffdd.commerce.dto.TradeinApplicationQueryRequest;
import ffdd.commerce.dto.TradeinQuoteRequest;
import ffdd.commerce.dto.TradeinQuoteResponse;
import ffdd.commerce.dto.TradeinReviewRequest;
import ffdd.commerce.dto.TradeinSubmitRequest;
import ffdd.commerce.service.TradeinService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commerce/tradeins")
public class TradeinController {
    private final TradeinService tradeinService;

    public TradeinController(TradeinService tradeinService) {
        this.tradeinService = tradeinService;
    }

    @PostMapping("/quote")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<TradeinQuoteResponse> quote(@Valid @RequestBody TradeinQuoteRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        return ApiResult.ok(tradeinService.quote(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<TradeinApplication> submit(@Valid @RequestBody TradeinSubmitRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        return ApiResult.ok(tradeinService.submit(request));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<PageResult<TradeinApplication>> page(TradeinApplicationQueryRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        return ApiResult.ok(tradeinService.page(request));
    }

    @GetMapping("/{tradeinNo}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<TradeinApplication> detail(@PathVariable String tradeinNo) {
        TradeinApplication application = tradeinService.get(tradeinNo);
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null && !roleUserId.equals(application.getUserId())) {
            throw new BizException("Trade-in application does not belong to authenticated user");
        }
        return ApiResult.ok(application);
    }

    @PostMapping("/{tradeinNo}/review")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<TradeinApplication> review(
            @PathVariable String tradeinNo,
            @Valid @RequestBody TradeinReviewRequest request) {
        return ApiResult.ok(tradeinService.review(tradeinNo, request, currentReviewer()));
    }

    private Long currentRoleUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) {
            return null;
        }
        String subject = String.valueOf(authentication.getPrincipal());
        if (!StringUtils.hasText(subject)) {
            return null;
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            throw new BizException("Authenticated user id is invalid");
        }
    }

    private String currentReviewer() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return String.valueOf(authentication.getPrincipal());
    }
}
