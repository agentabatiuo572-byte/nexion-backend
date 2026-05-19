package ffdd.task.controller;

import ffdd.common.api.ApiResult;
import ffdd.task.domain.ComputeReceipt;
import ffdd.task.service.ComputeReceiptService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/receipts")
@RequiredArgsConstructor
public class ComputeReceiptController {
    private final ComputeReceiptService computeReceiptService;

    @GetMapping("/mine")
    public ApiResult<List<ComputeReceipt>> listMine() {
        return ApiResult.ok(computeReceiptService.listMine());
    }
}

