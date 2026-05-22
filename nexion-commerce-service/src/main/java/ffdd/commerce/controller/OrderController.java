package ffdd.commerce.controller;

import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.dto.OrderCreateRequest;
import ffdd.commerce.dto.OrderPayRequest;
import ffdd.commerce.dto.OrderQueryRequest;
import ffdd.commerce.service.CommerceService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commerce/orders")
public class OrderController {
    private final CommerceService commerceService;

    public OrderController(CommerceService commerceService) {
        this.commerceService = commerceService;
    }

    @PostMapping
    public ApiResult<CommerceOrder> create(@Valid @RequestBody OrderCreateRequest request) {
        return ApiResult.ok(commerceService.createOrder(request));
    }

    @GetMapping
    public ApiResult<PageResult<CommerceOrder>> page(OrderQueryRequest request) {
        return ApiResult.ok(commerceService.pageOrders(request));
    }

    @GetMapping("/{orderNo}")
    public ApiResult<CommerceOrder> detail(@PathVariable String orderNo) {
        return ApiResult.ok(commerceService.getOrder(orderNo));
    }

    @PutMapping("/{orderNo}/paid")
    public ApiResult<CommerceOrder> markPaid(@PathVariable String orderNo, @Valid @RequestBody OrderPayRequest request) {
        return ApiResult.ok(commerceService.markPaid(orderNo, request));
    }

    @PutMapping("/{orderNo}/activate")
    public ApiResult<CommerceOrder> activate(@PathVariable String orderNo) {
        return ApiResult.ok(commerceService.activatePaidOrder(orderNo));
    }
}
