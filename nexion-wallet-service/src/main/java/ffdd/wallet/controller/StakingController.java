package ffdd.wallet.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.wallet.domain.StakingPosition;
import ffdd.wallet.domain.StakingProduct;
import ffdd.wallet.dto.CreateStakingPositionRequest;
import ffdd.wallet.dto.StakingPositionQueryRequest;
import ffdd.wallet.dto.StakingProductQueryRequest;
import ffdd.wallet.service.StakingService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallet")
public class StakingController {
    private final StakingService stakingService;

    public StakingController(StakingService stakingService) {
        this.stakingService = stakingService;
    }

    @GetMapping("/staking-products")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<PageResult<StakingProduct>> products(StakingProductQueryRequest request) {
        if (currentRoleUserId() != null && !StringUtils.hasText(request.getStatus())) {
            request.setStatus("ACTIVE");
        }
        return ApiResult.ok(stakingService.pageProducts(request));
    }

    @PostMapping("/staking-products")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<StakingProduct> createProduct(@Valid @RequestBody StakingProduct product) {
        product.setId(null);
        return ApiResult.ok(stakingService.saveProduct(product));
    }

    @PatchMapping("/staking-products/{id}")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<StakingProduct> updateProduct(@PathVariable Long id, @Valid @RequestBody StakingProduct product) {
        product.setId(id);
        return ApiResult.ok(stakingService.saveProduct(product));
    }

    @DeleteMapping("/staking-products/{id}")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<Void> deleteProduct(@PathVariable Long id) {
        stakingService.deleteProduct(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/staking-positions")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<PageResult<StakingPosition>> positions(StakingPositionQueryRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        return ApiResult.ok(stakingService.pagePositions(request));
    }

    @PostMapping("/staking-positions")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<StakingPosition> createPosition(@Valid @RequestBody CreateStakingPositionRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        return ApiResult.ok(stakingService.createPosition(request));
    }

    @PostMapping("/staking-positions/{positionNo}/claim")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<StakingPosition> claim(@PathVariable String positionNo) {
        return ApiResult.ok(stakingService.claim(positionNo));
    }

    @PostMapping("/staking-positions/{positionNo}/early-withdraw")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<StakingPosition> earlyWithdraw(@PathVariable String positionNo) {
        return ApiResult.ok(stakingService.earlyWithdraw(positionNo));
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
}
