package ffdd.commerce.controller;

import ffdd.commerce.domain.TradeinRule;
import ffdd.commerce.dto.TradeinRuleRequest;
import ffdd.commerce.service.TradeinService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commerce/tradein-rules")
public class TradeinRuleController {
    private final TradeinService tradeinService;

    public TradeinRuleController(TradeinService tradeinService) {
        this.tradeinService = tradeinService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ')")
    public ApiResult<PageResult<TradeinRule>> page(
            @RequestParam(required = false) Long pageNum,
            @RequestParam(required = false) Long pageSize,
            @RequestParam(required = false) String sourceTier,
            @RequestParam(required = false) String targetTier,
            @RequestParam(required = false) Integer status) {
        return ApiResult.ok(tradeinService.pageRules(pageNum, pageSize, sourceTier, targetTier, status));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<TradeinRule> create(@Valid @RequestBody TradeinRuleRequest request) {
        return ApiResult.ok(tradeinService.createRule(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<TradeinRule> update(@PathVariable Long id, @Valid @RequestBody TradeinRuleRequest request) {
        return ApiResult.ok(tradeinService.updateRule(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_WRITE')")
    public ApiResult<Void> delete(@PathVariable Long id) {
        tradeinService.deleteRule(id);
        return ApiResult.ok(null);
    }
}
