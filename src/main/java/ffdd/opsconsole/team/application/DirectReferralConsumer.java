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
        com.fasterxml.jackson.databind.JsonNode earningPayload=null;
        if("earnings.credited".equals(message.getEventType())&&"COMPUTE_TASK".equals(message.getAggregateType())){
            try {
                var parsed=json.readTree(message.getPayload());
                if(parsed!=null&&parsed.isObject()&&parsed.path("user_id").isIntegralNumber()
                        &&parsed.path("user_id").canConvertToLong()&&parsed.path("user_id").longValue()>0
                        &&parsed.path("receipt_no").isTextual()&&!parsed.path("receipt_no").asText().isBlank()
                        &&parsed.path("receipt_no").asText().length()<=96)earningPayload=parsed;
            }catch(com.fasterxml.jackson.core.JsonProcessingException error){
                // Malformed JSON follows the original claim/failure path below.
            }
        }
        var prepared=earningPayload==null?null:service.claimAndPrepareEarning(message,
                earningPayload.path("receipt_no").asText(),earningPayload.path("user_id").longValue());
        var claim=prepared==null?delivery.claim(message,GROUP,"spring-direct-referral",message.getEventId(),0):prepared.claim();
        if(!claim.claimed()){
            if(!Set.of("SUCCESS","SKIPPED").contains(claim.status()))throw new IllegalStateException("DIRECT_REFERRAL_DELIVERY_NOT_COMPLETE:"+claim.status());
            return;
        }
        if(prepared!=null&&prepared.finished())return;
        try {
            var payload=earningPayload==null?json.readTree(message.getPayload()):earningPayload;int result=0;
            switch(message.getEventType()){
                case "checkout.completed" -> {
                    if(!"ORDER".equals(message.getAggregateType()))throw new IllegalStateException("DIRECT_REFERRAL_ORDER_ENVELOPE_INVALID");
                    result=service.settle("direct_purchase",message.getAggregateId(),payload.path("user_id").longValue());
                }
                case "earnings.credited" -> {
                    if(!"COMPUTE_TASK".equals(message.getAggregateType()))throw new IllegalStateException("DIRECT_REFERRAL_EARNING_ENVELOPE_INVALID");
                    if(prepared==null)result=service.settle("direct_device_earning",payload.path("receipt_no").asText(),payload.path("user_id").longValue());
                    else {service.resumeSource("direct_device_earning",payload.path("receipt_no").asText());result=prepared.prepared();}
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
