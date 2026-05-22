package ffdd.compute.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.compute.domain.ComputeReceipt;
import ffdd.compute.dto.ReceiptCreateRequest;
import ffdd.compute.dto.ReceiptQueryRequest;
import ffdd.compute.service.ComputeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/compute/receipts")
public class ComputeReceiptController {
    private final ComputeService computeService;

    public ComputeReceiptController(ComputeService computeService) {
        this.computeService = computeService;
    }

    @GetMapping
    public ApiResult<PageResult<ComputeReceipt>> page(ReceiptQueryRequest request) {
        return ApiResult.ok(computeService.pageReceipts(request));
    }

    @PostMapping
    public ApiResult<ComputeReceipt> create(@Valid @RequestBody ReceiptCreateRequest request) {
        return ApiResult.ok(computeService.createReceipt(request));
    }

    @PutMapping("/{id}/settle-earnings")
    public ApiResult<ComputeReceipt> settleEarnings(@PathVariable Long id) {
        return ApiResult.ok(computeService.settleReceiptEarnings(id));
    }
}
