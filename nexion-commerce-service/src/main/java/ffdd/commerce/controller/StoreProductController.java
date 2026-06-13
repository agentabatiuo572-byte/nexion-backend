package ffdd.commerce.controller;

import ffdd.commerce.dto.StoreProductResponse;
import ffdd.commerce.service.CommerceService;
import ffdd.common.api.ApiResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commerce/app/store")
public class StoreProductController {
    private final CommerceService commerceService;

    public StoreProductController(CommerceService commerceService) {
        this.commerceService = commerceService;
    }

    @GetMapping("/products")
    public ApiResult<List<StoreProductResponse>> products() {
        return ApiResult.ok(commerceService.listStoreProducts());
    }
}
