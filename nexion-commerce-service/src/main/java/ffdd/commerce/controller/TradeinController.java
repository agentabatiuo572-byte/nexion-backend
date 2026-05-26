package ffdd.commerce.controller;

import ffdd.commerce.domain.TradeinApplication;
import ffdd.commerce.dto.TradeinApplicationQueryRequest;
import ffdd.commerce.dto.TradeinQuoteRequest;
import ffdd.commerce.dto.TradeinQuoteResponse;
import ffdd.commerce.dto.TradeinSubmitRequest;
import ffdd.commerce.service.TradeinService;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import jakarta.validation.Valid;
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
    public ApiResult<TradeinQuoteResponse> quote(@Valid @RequestBody TradeinQuoteRequest request) {
        return ApiResult.ok(tradeinService.quote(request));
    }

    @PostMapping
    public ApiResult<TradeinApplication> submit(@Valid @RequestBody TradeinSubmitRequest request) {
        return ApiResult.ok(tradeinService.submit(request));
    }

    @GetMapping
    public ApiResult<PageResult<TradeinApplication>> page(TradeinApplicationQueryRequest request) {
        return ApiResult.ok(tradeinService.page(request));
    }

    @GetMapping("/{tradeinNo}")
    public ApiResult<TradeinApplication> detail(@PathVariable String tradeinNo) {
        return ApiResult.ok(tradeinService.get(tradeinNo));
    }
}
