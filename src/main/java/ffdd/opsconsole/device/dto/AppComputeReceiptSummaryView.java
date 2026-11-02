package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AppComputeReceiptSummaryView(
        String receiptNo,
        String taskNo,
        String taskClass,
        String model,
        String client,
        BigDecimal rewardUsdt,
        BigDecimal rewardNex,
        String earningStatus,
        LocalDateTime completedAt,
        String taskName,
        String executionMode,
        String proofKind,
        boolean modelExecuted,
        String displayReceiptNo) {
    public AppComputeReceiptSummaryView(String receiptNo,String taskNo,String taskClass,String model,String client,
            BigDecimal rewardUsdt,BigDecimal rewardNex,String earningStatus,LocalDateTime completedAt) {
        this(receiptNo,taskNo,taskClass,model,client,rewardUsdt,rewardNex,earningStatus,completedAt,
                null,"MODEL","MODEL_EXECUTION",true,null);
    }
}
