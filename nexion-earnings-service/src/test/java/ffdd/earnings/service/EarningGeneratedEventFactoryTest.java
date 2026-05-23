package ffdd.earnings.service;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.earnings.domain.EarningEvent;
import ffdd.earnings.dto.EarningGeneratedPayload;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EarningGeneratedEventFactoryTest {
    private final EarningGeneratedEventFactory factory = new EarningGeneratedEventFactory();

    @Test
    void mapsEarningEventToGeneratedPayload() {
        EarningEvent event = new EarningEvent();
        event.setEventNo("EARN-POC1-USDT");
        event.setUserId(10001L);
        event.setUserDeviceId(7L);
        event.setReceiptNo("POC-1");
        event.setAsset("USDT");
        event.setAmount(new BigDecimal("0.018"));
        event.setStatus("PENDING_WALLET");

        EarningGeneratedPayload payload = factory.fromEvent(event);

        assertThat(payload.getEventNo()).isEqualTo("EARN-POC1-USDT");
        assertThat(payload.getUserId()).isEqualTo(10001L);
        assertThat(payload.getUserDeviceId()).isEqualTo(7L);
        assertThat(payload.getReceiptNo()).isEqualTo("POC-1");
        assertThat(payload.getAsset()).isEqualTo("USDT");
        assertThat(payload.getAmount()).isEqualByComparingTo("0.018");
        assertThat(payload.getStatus()).isEqualTo("PENDING_WALLET");
    }
}
