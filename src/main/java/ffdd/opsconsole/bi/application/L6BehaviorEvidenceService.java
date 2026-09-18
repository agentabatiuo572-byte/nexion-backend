package ffdd.opsconsole.bi.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.bi.mapper.L6BehaviorEvidenceMapper;
import ffdd.opsconsole.bi.mapper.L6BehaviorEvidenceMapper.Fact;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class L6BehaviorEvidenceService {
    private final L6BehaviorEvidenceMapper mapper;
    private final EventOutboxService outbox;
    private final ObjectMapper json;

    static final Set<String> TYPES=Set.of("app.page_viewed","app.element_clicked");

    /** No business replay: the fact, receipt and publication are one locked proof transaction. */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED,timeout=10)
    public boolean consume(long id) {
        EventOutboxMessage m=mapper.lockPending(id);
        if(m==null) return false;
        require(m.getEventType()!=null && TYPES.contains(m.getEventType()) && m.getEventType().equals(m.getEventName())
                && m.getEventId()!=null && m.getEventId().matches("[a-f0-9]{32}")
                && "APP_BEHAVIOR".equals(m.getAggregateType())
                && Boolean.TRUE.equals(m.getAnalyticsEvent()) && Boolean.TRUE.equals(m.getSchemaRegistered())
                && Boolean.FALSE.equals(m.getServerAuthoritative()),"L6_EVIDENCE_ENVELOPE_INVALID");
        JsonNode p=payload(m.getPayload());
        require(m.getEventId().equals(text(p,"event_id")) && m.getEventName().equals(text(p,"event_name"))
                && p.path("is_server_authoritative").isBoolean() && !p.path("is_server_authoritative").booleanValue()
                && m.getSchemaRevision()!=null && m.getSchemaRevision()>0
                && integer(p,"schema_revision").longValue()==m.getSchemaRevision().longValue()
                && "PRODUCTION".equals(text(p,"source_environment"))
                && p.path("user_id").isNull(),"L6_EVIDENCE_ENVELOPE_INVALID");
        String session=text(p,"session_id"),actor=text(p,"anon_id");
        require(session.matches("[a-f0-9]{64}") && actor.matches("[a-f0-9]{64}")
                && session.equals(m.getAggregateId()),"L6_EVIDENCE_ENVELOPE_INVALID");
        // Lifecycle remains an independent gate; a durable fact does not authorize disabled schemas.
        outbox.assertDispatchAllowed(m);
        Fact f=mapper.fact(m.getEventId());
        if(f==null) return false; // retention may win between the eligible scan and this transaction
        require(m.getEventId().equals(f.eventId()) && m.getEventName().equals(f.eventName())
                && "PRODUCTION".equals(f.sourceEnvironment()) && session.equals(f.sessionHash()) && actor.equals(f.actorHash())
                && text(p,"route").equals(f.route()) && Objects.equals(integer(p,"page_level"),longValue(f.pageLevel()))
                && Objects.equals(optionalText(p,"parent_l1"),f.parentL1())
                && Objects.equals(optionalText(p,"parent_l2"),f.parentL2())
                && f.deviceType()!=null && text(p,"platform").equals(f.deviceType().toLowerCase(Locale.ROOT))
                && text(p,"locale").equals(f.locale()),"L6_EVIDENCE_FACT_CONFLICT");
        if("app.page_viewed".equals(m.getEventType())) {
            require(Objects.equals(integer(p,"dwell_ms"),f.dwellMs()),"L6_EVIDENCE_FACT_CONFLICT");
        } else {
            require(decimalEquals(p,"x_norm",f.xNorm()) && decimalEquals(p,"y_norm",f.yNorm())
                    && text(p,"zone").equals(f.zone()) && Objects.equals(optionalText(p,"element_id"),f.elementId()),
                    "L6_EVIDENCE_FACT_CONFLICT");
        }
        var receipt=mapper.receipt(m.getEventId());
        if(receipt==null) {
            require(mapper.insertReceipt(m)==1,"L6_EVIDENCE_RECEIPT_FAILED");
        } else {
            require("SUCCESS".equals(receipt.getStatus()) && m.getEventId().equals(receipt.getEventId())
                    && L6BehaviorEvidenceMapper.GROUP.equals(receipt.getConsumerGroup())
                    && m.getEventId().equals(receipt.getMsgId()) && m.getEventType().equals(receipt.getEventType())
                    && m.getAggregateType().equals(receipt.getAggregateType()) && session.equals(receipt.getAggregateId())
                    && Integer.valueOf(1).equals(receipt.getCreatedCommissions()),"L6_EVIDENCE_RECEIPT_CONFLICT");
        }
        require(mapper.publish(id)==1,"L6_EVIDENCE_PUBLICATION_FAILED");
        return true;
    }

    private JsonNode payload(String value) {
        try { JsonNode p=json.readTree(value);require(p!=null && p.isObject(),"L6_EVIDENCE_PAYLOAD_INVALID");return p; }
        catch(Exception ex) { throw new EvidenceRejected("L6_EVIDENCE_PAYLOAD_INVALID"); }
    }
    private static String text(JsonNode p,String key) {
        JsonNode v=p.path(key);require(v.isTextual()&&!v.textValue().isBlank(),"L6_EVIDENCE_PAYLOAD_INVALID");return v.textValue();
    }
    private static String optionalText(JsonNode p,String key) {
        JsonNode v=p.path(key);if(v.isNull()||v.isMissingNode()) return null;
        require(v.isTextual(),"L6_EVIDENCE_PAYLOAD_INVALID");return v.textValue();
    }
    private static Long integer(JsonNode p,String key) {
        JsonNode v=p.path(key);require(v.isIntegralNumber()&&v.canConvertToLong(),"L6_EVIDENCE_PAYLOAD_INVALID");return v.longValue();
    }
    private static Long longValue(Integer value) { return value==null?null:value.longValue(); }
    private static boolean decimalEquals(JsonNode p,String key,BigDecimal expected) {
        JsonNode v=p.path(key);return v.isNumber()&&expected!=null&&v.decimalValue().compareTo(expected)==0;
    }
    private static void require(boolean condition,String code) { if(!condition) throw new EvidenceRejected(code); }
    static final class EvidenceRejected extends RuntimeException { EvidenceRejected(String code) { super(code); } }
}
