package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.mapper.LeadershipPoolAlertEvidenceMapper;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Acknowledges a persisted configuration ALERT only; no settlement, configuration or audit writes. */
@Service
@RequiredArgsConstructor
public class LeadershipPoolAlertEvidenceService {
    private final LeadershipPoolAlertEvidenceMapper mapper;
    private final EventOutboxService outbox;
    private final ObjectMapper json;

    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED,timeout=10)
    public boolean consume(long id) {
        var message=mapper.lockPending(id);
        if(message==null) return false;
        require(LeadershipPoolAlertEvidenceMapper.TYPE.equals(message.getEventType())
                && message.getEventType().equals(message.getEventName())
                && message.getEventId()!=null && message.getEventId().matches("[a-f0-9]{32}")
                && "LEADERSHIP_POOL_CONFIG".equals(message.getAggregateType())
                && Boolean.TRUE.equals(message.getAnalyticsEvent()) && Boolean.TRUE.equals(message.getSchemaRegistered())
                && Boolean.TRUE.equals(message.getServerAuthoritative()),"F4_ALERT_ENVELOPE_INVALID");
        JsonNode payload=object(message.getPayload());
        require(message.getEventId().equals(text(payload,"event_id")) && message.getEventName().equals(text(payload,"event_name"))
                && payload.path("is_server_authoritative").isBoolean() && payload.path("is_server_authoritative").booleanValue()
                && message.getSchemaRevision()!=null && message.getSchemaRevision()>0
                && payload.path("schema_revision").isIntegralNumber()
                && payload.path("schema_revision").canConvertToInt()
                && payload.path("schema_revision").intValue()==message.getSchemaRevision()
                && payload.path("ts").isIntegralNumber() && payload.path("ts").canConvertToLong()
                && payload.path("ts").longValue()>0,"F4_ALERT_ENVELOPE_INVALID");
        String source=text(payload,"source"),key=text(payload,"config_key"),reason=text(payload,"reason"),
                fingerprint=text(payload,"value_fingerprint"),blockedAt=text(payload,"blocked_at");
        require(Set.of("scheduler","settlement","direct-settlement").contains(source)
                && Set.of(LeadershipPoolConfigGuard.RATE_KEY,LeadershipPoolConfigGuard.UNLOCK_KEY,
                    LeadershipPoolConfigGuard.MONTHLY_CAP_KEY,LeadershipPoolConfigGuard.CRON_KEY,
                    LeadershipPoolConfigGuard.VERSION_KEY).contains(key)
                && Set.of("MISSING","INVALID_CRON","INVALID_VERSION","INVALID_RATE","INVALID_THRESHOLD").contains(reason)
                && fingerprint.matches("absent|[a-f0-9]{16}") && fingerprint.equals(message.getAggregateId())
                && validInstant(blockedAt),"F4_ALERT_PAYLOAD_INVALID");
        outbox.assertDispatchAllowed(message);
        var facts=mapper.facts(id);
        if(facts.isEmpty()) return false; // retention can win after the eligible scan
        require(facts.size()==1,"F4_ALERT_AUDIT_NOT_UNIQUE");
        var fact=facts.get(0);
        require(fact.id()!=null && fact.id()>0 && "F4_LEADERSHIP_POOL_CONFIG_BLOCKED".equals(fact.action())
                && "LEADERSHIP_POOL_CONFIG".equals(fact.resourceType()) && key.equals(fact.resourceId())
                && "SYSTEM".equals(fact.actorType()) && "SYSTEM".equals(fact.actorUsername())
                && "FAILED".equals(fact.result()) && "HIGH".equals(fact.riskLevel()),"F4_ALERT_AUDIT_CONFLICT");
        JsonNode detail=object(fact.detailJson());
        require(source.equals(text(detail,"source")) && key.equals(text(detail,"configKey"))
                && reason.equals(text(detail,"reason")) && fingerprint.equals(text(detail,"valueFingerprint"))
                && blockedAt.equals(text(detail,"blockedAt")),"F4_ALERT_AUDIT_CONFLICT");
        // The audit row is locked exclusively before checking claims: two different event IDs
        // cannot consume the same original failure. No general event bus is invoked.
        var claims=mapper.claims(fact.id());
        var receipt=mapper.receipt(message.getEventId());
        if(receipt==null) {
            require(claims.isEmpty(),"F4_ALERT_AUDIT_ALREADY_CLAIMED");
            require(mapper.insertReceipt(message,fact.id())==1,"F4_ALERT_RECEIPT_FAILED");
        } else {
            require(claims.size()==1 && message.getEventId().equals(claims.get(0).getEventId())
                    && "SUCCESS".equals(receipt.getStatus()) && message.getEventId().equals(receipt.getEventId())
                    && LeadershipPoolAlertEvidenceMapper.GROUP.equals(receipt.getConsumerGroup())
                    && ("A2:"+fact.id()).equals(receipt.getMsgId()) && message.getEventType().equals(receipt.getEventType())
                    && message.getAggregateType().equals(receipt.getAggregateType())
                    && fingerprint.equals(receipt.getAggregateId()) && Integer.valueOf(0).equals(receipt.getCreatedCommissions()),
                    "F4_ALERT_RECEIPT_CONFLICT");
        }
        require(mapper.publish(id)==1,"F4_ALERT_PUBLICATION_FAILED");
        return true;
    }
    private JsonNode object(String value) {
        try {var node=json.readTree(value);require(node!=null&&node.isObject(),"F4_ALERT_PAYLOAD_INVALID");return node;}
        catch(Exception ex) {throw new EvidenceRejected("F4_ALERT_PAYLOAD_INVALID");}
    }
    private static String text(JsonNode node,String key) {
        var value=node.path(key);require(value.isTextual()&&!value.textValue().isBlank(),"F4_ALERT_PAYLOAD_INVALID");return value.textValue();
    }
    private static boolean validInstant(String value) {
        try {return Instant.parse(value).toString().equals(value);} catch(RuntimeException ex) {return false;}
    }
    private static void require(boolean condition,String code) {if(!condition)throw new EvidenceRejected(code);}
    static final class EvidenceRejected extends RuntimeException {EvidenceRejected(String code){super(code);}}
}
