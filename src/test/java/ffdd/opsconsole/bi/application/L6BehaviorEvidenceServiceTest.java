package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.bi.mapper.L6BehaviorEvidenceMapper;
import ffdd.opsconsole.bi.mapper.L6BehaviorEvidenceMapper.Fact;
import ffdd.opsconsole.shared.outbox.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class L6BehaviorEvidenceServiceTest {
    static final String ID="a".repeat(32), SESSION="b".repeat(64), ACTOR="c".repeat(64);
    L6BehaviorEvidenceMapper mapper;
    EventOutboxService outbox;
    ObjectMapper json=new ObjectMapper();
    L6BehaviorEvidenceService service;
    @BeforeEach void setup() {
        mapper=mock(L6BehaviorEvidenceMapper.class);outbox=mock(EventOutboxService.class);
        service=new L6BehaviorEvidenceService(mapper,outbox,json);
    }
    static EventOutboxMessage message(String type) {
        var m=new EventOutboxMessage();m.setId(1L);m.setEventId(ID);m.setEventType(type);m.setEventName(type);
        m.setAggregateType("APP_BEHAVIOR");m.setAggregateId(SESSION);m.setAnalyticsEvent(true);
        m.setSchemaRegistered(true);m.setServerAuthoritative(false);m.setSchemaRevision(1);m.setStatus("PENDING");
        m.setPayload("{\"event_id\":\""+ID+"\",\"event_name\":\""+type+"\",\"is_server_authoritative\":false,"
            +"\"schema_revision\":1,\"source_environment\":\"PRODUCTION\",\"session_id\":\""+SESSION+"\",\"anon_id\":\""+ACTOR
            +"\",\"route\":\"/pages/me/me\",\"page_level\":1,\"parent_l1\":\"/pages/me/me\",\"parent_l2\":null,"
            +"\"platform\":\"h5\",\"locale\":\"zh\",\"dwell_ms\":250,\"x_norm\":0.1,\"y_norm\":0.5,\"zone\":\"CONTENT\",\"user_id\":null}");
        return m;
    }
    static Fact fact(String type) { return new Fact(ID,type,SESSION,ACTOR,"/pages/me/me",1,"/pages/me/me",null,
        type.equals("app.page_viewed")?250L:null,new BigDecimal("0.1000"),new BigDecimal("0.5000"),"CONTENT",null,"H5","zh","PRODUCTION"); }
    void valid(String type) {
        when(mapper.lockPending(1)).thenReturn(message(type));when(mapper.fact(ID)).thenReturn(fact(type));
        when(mapper.insertReceipt(any())).thenReturn(1);when(mapper.publish(1)).thenReturn(1);
    }
    @ParameterizedTest @ValueSource(strings={"app.page_viewed","app.element_clicked"})
    void existingProductionFactGetsOneReceiptAndPublishes(String type) {
        valid(type);assertThat(service.consume(1)).isTrue();verify(mapper).insertReceipt(any());verify(mapper).publish(1);
    }
    @Test void missingFactRemainsUnresolved() {
        valid("app.page_viewed");when(mapper.fact(ID)).thenReturn(null);
        assertThat(service.consume(1)).isFalse();verify(mapper,never()).insertReceipt(any());verify(mapper,never()).publish(anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"APP.PAGE_VIEWED","app.páge_viewed","app.page_viewed ","wallet.ledger_posted"})
    void rejectsOtherFamiliesAndAliases(String type) {
        valid("app.page_viewed");var m=message(type);when(mapper.lockPending(1)).thenReturn(m);
        assertThatThrownBy(()->service.consume(1)).hasMessage("L6_EVIDENCE_ENVELOPE_INVALID");
        verify(mapper,never()).fact(anyString());verify(mapper,never()).publish(anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"event_id","event_name","session_id","anon_id","route","parent_l1","parent_l2","locale","platform","source_environment"})
    void payloadIdentityAndFactFieldsCannotBeSubstituted(String field) throws Exception {
        valid("app.page_viewed");var m=message("app.page_viewed");
        var p=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(m.getPayload());p.put(field,"different");m.setPayload(p.toString());
        when(mapper.lockPending(1)).thenReturn(m);
        assertThatThrownBy(()->service.consume(1)).isInstanceOf(L6BehaviorEvidenceService.EvidenceRejected.class);
        verify(mapper,never()).insertReceipt(any());verify(mapper,never()).publish(anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"schema_revision","page_level","dwell_ms"})
    void numericStringsCannotProveIntegerFields(String field) throws Exception {
        valid("app.page_viewed");var m=message("app.page_viewed");
        var p=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(m.getPayload());p.put(field,"1");m.setPayload(p.toString());
        when(mapper.lockPending(1)).thenReturn(m);
        assertThatThrownBy(()->service.consume(1)).hasMessage("L6_EVIDENCE_PAYLOAD_INVALID");verify(mapper,never()).publish(anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"x_norm","y_norm","zone","element_id"})
    void clickCoordinatesAndTargetMustMatch(String field) throws Exception {
        valid("app.element_clicked");var m=message("app.element_clicked");
        var p=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(m.getPayload());p.put(field,"different");m.setPayload(p.toString());
        when(mapper.lockPending(1)).thenReturn(m);
        assertThatThrownBy(()->service.consume(1)).hasMessage("L6_EVIDENCE_FACT_CONFLICT");verify(mapper,never()).publish(anyLong());
    }
    @Test void receiptWriteFailureCannotPublish() {
        valid("app.page_viewed");when(mapper.insertReceipt(any())).thenReturn(0);
        assertThatThrownBy(()->service.consume(1)).hasMessage("L6_EVIDENCE_RECEIPT_FAILED");verify(mapper,never()).publish(anyLong());
    }
    @Test void publicationFailureRequiresTransactionRollback() {
        valid("app.page_viewed");when(mapper.publish(1)).thenReturn(0);
        assertThatThrownBy(()->service.consume(1)).hasMessage("L6_EVIDENCE_PUBLICATION_FAILED");
    }
    @Test void completedOrIneligibleEventIsNoOp() {
        assertThat(service.consume(1)).isFalse();verify(mapper,never()).fact(anyString());verifyNoInteractions(outbox);
    }
    @Test void lifecycleGateCannotBeBypassedByMatchingFact() {
        valid("app.page_viewed");doThrow(new IllegalStateException("blocked")).when(outbox).assertDispatchAllowed(any());
        assertThatThrownBy(()->service.consume(1)).hasMessage("blocked");verify(mapper,never()).insertReceipt(any());
    }
    @Test void existingIncompleteReceiptIsNotPromoted() {
        valid("app.page_viewed");var r=new EventConsumerDelivery();r.setStatus("PROCESSING");when(mapper.receipt(ID)).thenReturn(r);
        assertThatThrownBy(()->service.consume(1)).hasMessage("L6_EVIDENCE_RECEIPT_CONFLICT");verify(mapper,never()).publish(anyLong());
    }
}
