package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.BankWithdrawalService;
import ffdd.opsconsole.finance.application.BankWithdrawalEligibility;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/finance/withdrawals")
public class OpsBankWithdrawalController {
    private final BankWithdrawalMapper mapper;
    private final ffdd.opsconsole.finance.application.BankPayoutRecoveryService recovery;
    @GetMapping("/{withdrawalNo}/bank")
    @PreAuthorize("hasAuthority('finance_d2_read')")
    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> detail(@PathVariable String withdrawalNo) {
        var order = mapper.order(withdrawalNo);
        if (order == null) throw new BizException(404, "BANK_WITHDRAWAL_NOT_FOUND");
        var quote = mapper.quote(order.quoteNo());
        if (quote == null) throw new BizException(409, "BANK_PAYOUT_SNAPSHOT_MISMATCH");
        return ApiResult.ok(BankWithdrawalService.map("withdrawalNo", withdrawalNo, "provider", "HDPAY", "state", order.state(),
                "providerOrderId", order.providerOrderId() == null ? null : order.providerOrderId().toString(),
                "providerStatus", order.providerStatus(), "lastError", order.lastError(),
                "version", mapper.version(withdrawalNo),
                "quote", BankWithdrawalService.quoteView(quote),
                "beneficiaryVerification", BankWithdrawalEligibility.quoteEvidenceView(mapper.verification(quote.beneficiaryNo()),
                        mapper.beneficiary(order.userId()), quote,
                        java.time.LocalDateTime.now(ffdd.opsconsole.shared.config.DateTimeFormatConfig.BUSINESS_ZONE)),
                "settlementEvidence", BankWithdrawalService.settlementView(order, mapper.settlementEvidence(withdrawalNo))));
    }
    @PostMapping("/{withdrawalNo}/bank/requery")
    @PreAuthorize("hasAuthority('finance_d2_read') and hasAuthority('finance_d2_withdrawal_approve') and hasAuthority('finance_d2_withdrawal_refund')")
    public ApiResult<Map<String, Object>> requery(@PathVariable String withdrawalNo,
            @RequestHeader("Idempotency-Key") String key, @RequestBody RecoveryRequest request) {
        if (request == null) throw new BizException(422, "BANK_PAYOUT_RECOVERY_REQUEST_INVALID");
        return ApiResult.ok(Map.of("withdrawalNo", withdrawalNo, "state",
                recovery.recover(withdrawalNo, key, request.version(), request.reason())));
    }
    public record RecoveryRequest(Long version, String reason) {}
}
