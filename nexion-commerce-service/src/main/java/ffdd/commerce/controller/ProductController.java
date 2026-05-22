package ffdd.commerce.controller;

import ffdd.commerce.domain.Product;
import ffdd.commerce.dto.ProductQueryRequest;
import ffdd.commerce.service.CommerceService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commerce/products")
public class ProductController {
    private final CommerceService commerceService;

    public ProductController(CommerceService commerceService) {
        this.commerceService = commerceService;
    }

    @GetMapping
    public ApiResult<PageResult<Product>> page(ProductQueryRequest request) {
        return ApiResult.ok(commerceService.pageProducts(request));
    }

    @GetMapping("/{id}")
    public ApiResult<Product> detail(@PathVariable Long id) {
        return ApiResult.ok(commerceService.getProduct(id));
    }
}
