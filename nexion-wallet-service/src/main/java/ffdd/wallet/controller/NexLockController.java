package ffdd.wallet.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.wallet.domain.NexLockOrder;
import ffdd.wallet.dto.CreateNexLockRequest;
import ffdd.wallet.dto.WalletOrderQueryRequest;
import ffdd.wallet.service.NexLockService;
import ffdd.common.exception.BizException;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallet/nex-locks")
public class NexLockController {
    private final NexLockService lockService;

    public NexLockController(NexLockService lockService) {
        this.lockService = lockService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_WALLET_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<PageResult<NexLockOrder>> page(WalletOrderQueryRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        return ApiResult.ok(lockService.page(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<NexLockOrder> create(@Valid @RequestBody CreateNexLockRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        return ApiResult.ok(lockService.create(request));
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
