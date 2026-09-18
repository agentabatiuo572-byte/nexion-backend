package ffdd.opsconsole.team.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.team.mapper.LeadershipPoolAlertEvidenceMapper;
import ffdd.opsconsole.team.mapper.LeadershipPoolAlertEvidenceMapper.AuditFact;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class LeadershipPoolAlertEvidenceServiceTest {
    static final String ID="a".repeat(32),TYPE="leadership_pool.settlement_blocked",TIME="2026-09-18T08:00:00.123456789Z";
    final ObjectMapper json=new ObjectMapper();
    final LeadershipPoolAlertEvidenceMapper mapper=mock(LeadershipPoolAlertEvidenceMapper.class);
    final EventOutboxService outbox=mock(EventOutboxService.class);
    final LeadershipPoolAlertEvidenceService service=new LeadershipPoolAlertEvidenceService(mapper,outbox,json);
    static EventOutboxMessage message() {
        var m=new EventOutboxMessage();m.setEventId(ID);m.setEventType(TYPE);m.setEventName(TYPE);
        m.setAggregateType("LEADERSHIP_POOL_CONFIG");m.setAggregateId("absent");m.setAnalyticsEvent(true);
        m.setSchemaRegistered(true);m.setServerAuthoritative(true);m.setSchemaRevision(305);
        m.setPayload("""
          {"event_id":"%s","event_name":"%s","is_server_authoritative":true,"schema_revision":305,
           "ts":1789718400123,"source":"scheduler","config_key":"team.ui.F.pool.configVersion","reason":"MISSING",
           "value_fingerprint":"absent","blocked_at":"%s"}
          """.formatted(ID,TYPE,TIME));return m;
    }
    static AuditFact fact() {return new AuditFact(42L,"F4_LEADERSHIP_POOL_CONFIG_BLOCKED","LEADERSHIP_POOL_CONFIG",
        "team.ui.F.pool.configVersion","SYSTEM","SYSTEM","FAILED","HIGH","""
          {"source":"scheduler","configKey":"team.ui.F.pool.configVersion","reason":"MISSING",
           "valueFingerprint":"absent","blockedAt":"%s"}
          """.formatted(TIME));}
    void valid() {when(mapper.lockPending(1)).thenReturn(message());when(mapper.facts(1)).thenReturn(List.of(fact()));
        when(mapper.claims(42)).thenReturn(List.of());when(mapper.insertReceipt(any(),eq(42L))).thenReturn(1);when(mapper.publish(1)).thenReturn(1);}
    @Test void acknowledgesOnlyExistingFailedAlertWithoutReplayingSettlementOrWritingAudit() {
        valid();assertThat(service.consume(1)).isTrue();verify(outbox).assertDispatchAllowed(any());verifyNoMoreInteractions(outbox);
        verify(mapper).insertReceipt(any(),eq(42L));verify(mapper).publish(1);
    }
    @Test void missingAndDuplicateOriginalAuditCannotPublish() {valid();when(mapper.facts(1)).thenReturn(List.of());
        assertThat(service.consume(1)).isFalse();when(mapper.facts(1)).thenReturn(List.of(fact(),fact()));
        assertThatThrownBy(()->service.consume(1)).hasMessage("F4_ALERT_AUDIT_NOT_UNIQUE");verify(mapper,never()).publish(anyLong());}
    @ParameterizedTest @ValueSource(strings={"LEADERSHIP_POOL.SETTLEMENT_BLOCKED","leadership_pool.settlement_blocked ","léadership_pool.settlement_blocked","wallet.ledger_posted"})
    void refusesAliasesAndOtherFamilies(String type){valid();var m=message();m.setEventType(type);when(mapper.lockPending(1)).thenReturn(m);
        assertThatThrownBy(()->service.consume(1)).hasMessage("F4_ALERT_ENVELOPE_INVALID");verify(mapper,never()).facts(anyLong());}
    @ParameterizedTest @ValueSource(strings={"event_id","event_name","source","config_key","reason","value_fingerprint","blocked_at","schema_revision"})
    void refusesPayloadSubstitution(String field)throws Exception{valid();var m=message();var p=(ObjectNode)json.readTree(m.getPayload());p.put(field,"forged");m.setPayload(p.toString());when(mapper.lockPending(1)).thenReturn(m);
        assertThatThrownBy(()->service.consume(1)).isInstanceOf(LeadershipPoolAlertEvidenceService.EvidenceRejected.class);verify(mapper,never()).publish(anyLong());}
    @ParameterizedTest @ValueSource(strings={"source","configKey","reason","valueFingerprint","blockedAt"})
    void rechecksAllFiveAuditFieldsAfterLock(String field)throws Exception{valid();var f=fact();var p=(ObjectNode)json.readTree(f.detailJson());p.put(field,"different");
        when(mapper.facts(1)).thenReturn(List.of(new AuditFact(f.id(),f.action(),f.resourceType(),f.resourceId(),f.actorType(),f.actorUsername(),f.result(),f.riskLevel(),p.toString())));
        assertThatThrownBy(()->service.consume(1)).hasMessage("F4_ALERT_AUDIT_CONFLICT");verify(mapper,never()).publish(anyLong());}
    @Test void successfulOrHumanAuditCannotProveTheSystemFailure(){valid();var f=fact();when(mapper.facts(1)).thenReturn(List.of(new AuditFact(f.id(),f.action(),f.resourceType(),f.resourceId(),"ADMIN","suadmin","SUCCESS",f.riskLevel(),f.detailJson())));
        assertThatThrownBy(()->service.consume(1)).hasMessage("F4_ALERT_AUDIT_CONFLICT");verify(mapper,never()).publish(anyLong());}
    @Test void oneAuditCannotBeClaimedByAnotherEvent(){valid();var claim=new EventConsumerDelivery();claim.setEventId("b".repeat(32));when(mapper.claims(42)).thenReturn(List.of(claim));
        assertThatThrownBy(()->service.consume(1)).hasMessage("F4_ALERT_AUDIT_ALREADY_CLAIMED");verify(mapper,never()).insertReceipt(any(),anyLong());}
    @Test void receiptWriteAndPublicationFailuresAbort(){valid();when(mapper.insertReceipt(any(),eq(42L))).thenReturn(0);
        assertThatThrownBy(()->service.consume(1)).hasMessage("F4_ALERT_RECEIPT_FAILED");verify(mapper,never()).publish(anyLong());
        when(mapper.insertReceipt(any(),eq(42L))).thenReturn(1);when(mapper.publish(1)).thenReturn(0);assertThatThrownBy(()->service.consume(1)).hasMessage("F4_ALERT_PUBLICATION_FAILED");}
    @Test void registryDenialCannotBeOverriddenByAudit(){valid();doThrow(new IllegalStateException("disabled")).when(outbox).assertDispatchAllowed(any());
        assertThatThrownBy(()->service.consume(1)).hasMessage("disabled");verify(mapper,never()).facts(anyLong());verify(mapper,never()).publish(anyLong());}
}
