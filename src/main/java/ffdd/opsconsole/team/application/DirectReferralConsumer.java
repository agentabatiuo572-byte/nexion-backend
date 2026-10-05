package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DirectReferralConsumer {
    static final String GROUP="direct-referral-settlement";
    private static final Set<String> TYPES=Set.of("checkout.completed","earnings.credited","order.refunded");
    private final EventConsumerDeliveryService delivery;
    private final DirectReferralService service;
    private final ObjectMapper json;
    @EventListener public void consume(EventOutboxMessage message){
        if(message==null||!TYPES.contains(message.getEventType()))return;
        var claim=delivery.claim(message,GROUP,"spring-direct-referral",message.getEventId(),0);
        if(!claim.claimed()){
            if(!Set.of("SUCCESS","SKIPPED").contains(claim.status()))throw new IllegalStateException("DIRECT_REFERRAL_DELIVERY_NOT_COMPLETE:"+claim.status());
            return;
        }
        try {
            var payload=json.readTree(message.getPayload());int result=0;
            switch(message.getEventType()){
                case "checkout.completed" -> {
                    if(!"ORDER".equals(message.getAggregateType()))throw new IllegalStateException("DIRECT_REFERRAL_ORDER_ENVELOPE_INVALID");
                    result=service.settle("direct_purchase",message.getAggregateId(),payload.path("user_id").longValue());
                }
                case "earnings.credited" -> {
                    if(!"COMPUTE_TASK".equals(message.getAggregateType()))throw new IllegalStateException("DIRECT_REFERRAL_EARNING_ENVELOPE_INVALID");
                    result=service.settle("direct_device_earning",payload.path("receipt_no").asText(),payload.path("user_id").longValue());
                }
                case "order.refunded" -> {
                    if(!"E4_ORDER".equals(message.getAggregateType()))throw new IllegalStateException("DIRECT_REFERRAL_REFUND_ENVELOPE_INVALID");
                    service.refund(message.getAggregateId());result=1;
                }
                default -> throw new IllegalStateException();
            }
            delivery.markSuccess(GROUP,claim.eventId(),result);
        }catch(Exception e){
            delivery.markFailure(GROUP,claim.eventId(),0,e.getMessage());
            if(e instanceof RuntimeException runtime)throw runtime;
            throw new IllegalStateException("DIRECT_REFERRAL_PAYLOAD_INVALID",e);
        }
    }
}
