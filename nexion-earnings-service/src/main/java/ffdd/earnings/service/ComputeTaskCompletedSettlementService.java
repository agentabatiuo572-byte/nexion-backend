package ffdd.earnings.service;

import ffdd.common.exception.BizException;
import ffdd.earnings.dto.ComputeTaskCompletedPayload;
import ffdd.earnings.dto.ReceiptSettleRequest;
import ffdd.earnings.dto.ReceiptSettleResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ComputeTaskCompletedSettlementService {
    private final EarningsService earningsService;

    public ComputeTaskCompletedSettlementService(EarningsService earningsService) {
        this.earningsService = earningsService;
    }

    public ReceiptSettleResponse settle(ComputeTaskCompletedPayload payload) {
        return earningsService.settleReceipt(toReceiptSettleRequest(payload));
    }

    ReceiptSettleRequest toReceiptSettleRequest(ComputeTaskCompletedPayload payload) {
        validate(payload);
        ReceiptSettleRequest request = new ReceiptSettleRequest();
        request.setUserId(payload.getUserId());
        request.setUserDeviceId(payload.getUserDeviceId());
        request.setReceiptNo(payload.getReceiptNo());
        request.setRewardUsdt(payload.getRewardUsdt());
        request.setRewardNex(payload.getRewardNex());
        request.setCompletedAt(payload.getCompletedAt());
        return request;
    }

    private void validate(ComputeTaskCompletedPayload payload) {
        if (payload == null) {
            throw new BizException("ComputeTaskCompleted payload is required");
        }
        if (payload.getUserId() == null) {
            throw new BizException("ComputeTaskCompleted userId is required");
        }
        if (!StringUtils.hasText(payload.getReceiptNo())) {
            throw new BizException("ComputeTaskCompleted receiptNo is required");
        }
    }
}
