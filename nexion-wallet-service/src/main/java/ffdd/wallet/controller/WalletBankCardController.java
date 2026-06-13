package ffdd.wallet.controller;

import ffdd.common.api.ApiResult;
import ffdd.wallet.domain.WalletBankCard;
import ffdd.wallet.dto.BankCardQueryRequest;
import ffdd.wallet.dto.CreateBankCardRequest;
import ffdd.wallet.service.WalletBankCardService;
import ffdd.common.exception.BizException;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/wallet/bank-cards")
public class WalletBankCardController {
    private final WalletBankCardService cardService;

    public WalletBankCardController(WalletBankCardService cardService) {
        this.cardService = cardService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_WALLET_READ') or hasAuthority('ROLE_USER')")
    public ApiResult<List<WalletBankCard>> list(BankCardQueryRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        return ApiResult.ok(cardService.list(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<WalletBankCard> create(@Valid @RequestBody CreateBankCardRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        return ApiResult.ok(cardService.create(request));
    }

    @PatchMapping("/{id}/default")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<WalletBankCard> setDefault(@PathVariable Long id) {
        return ApiResult.ok(cardService.setDefault(id, currentRoleUserId()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<Void> delete(@PathVariable Long id) {
        cardService.delete(id, currentRoleUserId());
        return ApiResult.ok(null);
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
