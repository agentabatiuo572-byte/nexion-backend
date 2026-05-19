package ffdd.task.service.impl;

import ffdd.task.domain.ComputeReceipt;
import ffdd.task.service.ComputeReceiptService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ComputeReceiptServiceImpl implements ComputeReceiptService {
    @Override
    public List<ComputeReceipt> listMine() {
        ComputeReceipt receipt = new ComputeReceipt();
        receipt.setId(1L);
        receipt.setUserId(10001L);
        receipt.setReceiptNo("POC-20260519-0001");
        receipt.setTaskType("IMAGE_INFERENCE");
        receipt.setClientName("OpenRouter");
        receipt.setRewardUsdt(new BigDecimal("0.018"));
        receipt.setRewardNex(new BigDecimal("3.2"));
        receipt.setProofHash("0x8a22c91f6f8e7c2d");
        receipt.setCompletedAt(LocalDateTime.now());
        return List.of(receipt);
    }
}

