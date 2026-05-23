package ffdd.compute.service;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.compute.domain.ComputeReceipt;
import ffdd.compute.dto.ComputeTaskCompletedPayload;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ComputeTaskCompletedEventFactoryTest {
    private final ComputeTaskCompletedEventFactory factory = new ComputeTaskCompletedEventFactory();

    @Test
    void mapsReceiptToTaskCompletedPayload() {
        LocalDateTime completedAt = LocalDateTime.parse("2026-05-23T12:30:00");
        ComputeReceipt receipt = new ComputeReceipt();
        receipt.setUserId(10001L);
        receipt.setUserDeviceId(7L);
        receipt.setTaskNo("TASK-1");
        receipt.setReceiptNo("POC-1");
        receipt.setTaskType("AI_INFERENCE");
        receipt.setClientName("worker-a");
        receipt.setRewardUsdt(new BigDecimal("0.018"));
        receipt.setRewardNex(new BigDecimal("3.2"));
        receipt.setCompletedAt(completedAt);
        receipt.setProofHash("0xabc");

        ComputeTaskCompletedPayload payload = factory.fromReceipt(receipt);

        assertThat(payload.getUserId()).isEqualTo(10001L);
        assertThat(payload.getUserDeviceId()).isEqualTo(7L);
        assertThat(payload.getReceiptNo()).isEqualTo("POC-1");
        assertThat(payload.getRewardUsdt()).isEqualByComparingTo("0.018");
        assertThat(payload.getRewardNex()).isEqualByComparingTo("3.2");
        assertThat(payload.getCompletedAt()).isEqualTo(completedAt);
        assertThat(payload.getProofHash()).isEqualTo("0xabc");
    }
}
