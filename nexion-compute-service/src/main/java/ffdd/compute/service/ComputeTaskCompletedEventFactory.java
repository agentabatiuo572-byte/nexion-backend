package ffdd.compute.service;

import ffdd.compute.domain.ComputeReceipt;
import ffdd.compute.dto.ComputeTaskCompletedPayload;
import org.springframework.stereotype.Component;

@Component
public class ComputeTaskCompletedEventFactory {
    public ComputeTaskCompletedPayload fromReceipt(ComputeReceipt receipt) {
        ComputeTaskCompletedPayload payload = new ComputeTaskCompletedPayload();
        payload.setUserId(receipt.getUserId());
        payload.setUserDeviceId(receipt.getUserDeviceId());
        payload.setTaskNo(receipt.getTaskNo());
        payload.setReceiptNo(receipt.getReceiptNo());
        payload.setTaskType(receipt.getTaskType());
        payload.setClientName(receipt.getClientName());
        payload.setRewardUsdt(receipt.getRewardUsdt());
        payload.setRewardNex(receipt.getRewardNex());
        payload.setCompletedAt(receipt.getCompletedAt());
        payload.setProofHash(receipt.getProofHash());
        return payload;
    }
}
