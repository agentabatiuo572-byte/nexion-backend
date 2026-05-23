package ffdd.earnings.service;

import ffdd.earnings.domain.EarningEvent;
import ffdd.earnings.dto.EarningGeneratedPayload;
import org.springframework.stereotype.Component;

@Component
public class EarningGeneratedEventFactory {
    public EarningGeneratedPayload fromEvent(EarningEvent event) {
        EarningGeneratedPayload payload = new EarningGeneratedPayload();
        payload.setEventNo(event.getEventNo());
        payload.setUserId(event.getUserId());
        payload.setUserDeviceId(event.getUserDeviceId());
        payload.setReceiptNo(event.getReceiptNo());
        payload.setAsset(event.getAsset());
        payload.setAmount(event.getAmount());
        payload.setStatus(event.getStatus());
        return payload;
    }
}
