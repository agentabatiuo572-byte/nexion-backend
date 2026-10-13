package ffdd.opsconsole.team.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.team.mapper.SupportDirectReferralReadMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SupportDirectReferralReadServiceTest {
    private SupportDirectReferralReadMapper mapper;
    private SupportDirectReferralReadService reader;
    private Map<String,Object> settlement;
    @BeforeEach void setup() {
        mapper=mock(SupportDirectReferralReadMapper.class);reader=new SupportDirectReferralReadService(mapper);
        settlement=new LinkedHashMap<>(Map.of("settlement_no","DR-1","source_type","direct_purchase","source_ref","ORDER-1","usdt_event_id",11L,"nex_event_id",12L));
        when(mapper.settlementsByCustomers(any())).thenAnswer(i->List.of(settlement));
        when(mapper.settlementsByNos(any())).thenAnswer(i->List.of(settlement));
        when(mapper.reissuesByCustomers(any())).thenReturn(List.of(Map.of("id",13L)));
        when(mapper.eventsByIds(any())).thenAnswer(i->i.<Collection<Long>>getArgument(0).contains(13L)?List.of(Map.of("id",13L)):List.of(Map.of("id",11L),Map.of("id",12L)));
        when(mapper.entries(anyString())).thenReturn(List.of(Map.of("entryNo","E-1")));
        when(mapper.recoveries(anyLong())).thenReturn(List.of(Map.of("event_id",13L)));
        when(mapper.orders(anyString())).thenReturn(List.of(Map.of("id",1L)));
        when(mapper.reissueRootOrders(anyLong())).thenReturn(List.of(Map.of("id",1L)));
        when(mapper.ledgers(anyString(),anyString())).thenReturn(List.of(Map.of("id",1L)));
        when(mapper.timePrecisions()).thenReturn(List.of(Map.of("tableName","nx_direct_referral_settlement","columnName","updated_at","precision",0)));
    }
    static Stream<Object[]> missingRows() {return Stream.of("settlements-customers","reissues-customers","settlements-nos","events-linked","events-key","entries","recoveries","orders","reissue-orders","ledgers-settlement","ledgers-reissue","precisions").flatMap(name->Stream.of(new Object[]{name,false},new Object[]{name,true}));}
    @ParameterizedTest @MethodSource("missingRows") void missingCollectionOrRowRejectsWithoutNullPointer(String path,boolean row) {
        List<Map<String,Object>> invalid=row?Collections.singletonList(null):null;
        switch(path) {
            case "settlements-customers" -> when(mapper.settlementsByCustomers(any())).thenReturn(invalid);
            case "reissues-customers" -> when(mapper.reissuesByCustomers(any())).thenReturn(invalid);
            case "settlements-nos" -> when(mapper.settlementsByNos(any())).thenReturn(invalid);
            case "events-linked","events-key" -> doReturn(invalid).when(mapper).eventsByIds(any());
            case "entries" -> when(mapper.entries(anyString())).thenReturn(invalid);
            case "recoveries" -> when(mapper.recoveries(anyLong())).thenReturn(invalid);
            case "orders" -> when(mapper.orders(anyString())).thenReturn(invalid);
            case "reissue-orders" -> when(mapper.reissueRootOrders(anyLong())).thenReturn(invalid);
            case "ledgers-settlement","ledgers-reissue" -> when(mapper.ledgers(anyString(),anyString())).thenReturn(invalid);
            case "precisions" -> when(mapper.timePrecisions()).thenReturn(invalid);
            default -> throw new AssertionError(path);
        }
        assertThrows(IllegalStateException.class,()->{
            if(path.equals("precisions"))reader.timePrecisions();
            else if(path.equals("settlements-nos"))reader.readByKeys(List.of("S:DR-1"));
            else if(path.equals("events-key")||path.equals("ledgers-reissue"))reader.readByKeys(List.of("R:13"));
            else reader.readByCustomers(List.of(7L));
        });
    }
    @ParameterizedTest @ValueSource(strings={"source_type","settlement_no","source_ref"}) void missingSourceTextRejectsBeforeDependentMapper(String field) {
        settlement.remove(field);assertThrows(IllegalArgumentException.class,()->reader.readByCustomers(List.of(7L)));
    }
    @ParameterizedTest @ValueSource(strings={"source_type","settlement_no","source_ref"}) void nonTextSourceFieldRejectsBeforeCast(String field) {
        settlement.put(field,7L);assertThrows(IllegalArgumentException.class,()->reader.readByCustomers(List.of(7L)));
    }
    @ParameterizedTest @ValueSource(strings={"tableName","columnName","precision"}) void missingPrecisionFieldRejected(String field) {
        var row=new LinkedHashMap<String,Object>(Map.of("tableName","nx_direct_referral_settlement","columnName","updated_at","precision",0));row.remove(field);
        when(mapper.timePrecisions()).thenReturn(List.of(row));assertThrows(IllegalArgumentException.class,reader::timePrecisions);
    }
    @Test void nullScopesAreNotSuccessfulEmptyReads() {assertThrows(IllegalArgumentException.class,()->reader.readByCustomers(null));assertThrows(IllegalArgumentException.class,()->reader.readByKeys(null));verifyNoInteractions(mapper);}
    @Test void emptyScopesSkipMapperAndPreserveKnownEmpty() {assertEquals(List.of(),reader.readByCustomers(List.of()));assertEquals(List.of(),reader.readByKeys(List.of()));verifyNoInteractions(mapper);}
    @Test void validRowsCopyNestedFactsAndNullableCellsWithoutMutatingSource() {
        var entry=new LinkedHashMap<String,Object>();entry.put("entryNo","E-1");entry.put("clusterId",null);
        when(mapper.entries(anyString())).thenReturn(List.of(entry));var result=reader.readByCustomers(List.of(7L));
        assertEquals(List.of("S:DR-1","R:13"),result.stream().map(s->s.key()).toList());assertTrue(result.get(0).entries().get(0).containsKey("clusterId"));assertNull(result.get(0).entries().get(0).get("clusterId"));
        assertFalse(settlement.containsKey("support_root_orders"));entry.put("entryNo","CHANGED");assertEquals("E-1",result.get(0).entries().get(0).get("entryNo"));
        assertThrows(UnsupportedOperationException.class,()->result.get(0).subject().put("id",1));
    }
    @Test void emptyQueryResultsRemainEmptyRatherThanMissing() {when(mapper.settlementsByCustomers(any())).thenReturn(List.of());when(mapper.reissuesByCustomers(any())).thenReturn(List.of());assertEquals(List.of(),reader.readByCustomers(List.of(7L)));}
    @Test void absenceOfSingleRecoveryRowRemainsAllowed() {when(mapper.recoveries(anyLong())).thenReturn(List.of());assertNull(reader.readByKeys(List.of("R:13")).get(0).recovery());}
    @Test void ambiguousRecoveryStillRejected() {when(mapper.recoveries(anyLong())).thenReturn(List.of(Map.of("event_id",13L),Map.of("event_id",13L)));assertThrows(IllegalArgumentException.class,()->reader.readByKeys(List.of("R:13")));}
    @Test void nestedNullMapKeyRejectedBeforeDtoCopy() {var nested=new LinkedHashMap<Object,Object>();nested.put(null,1);settlement.put("unexpected",nested);assertThrows(IllegalStateException.class,()->reader.readByCustomers(List.of(7L)));}
    @Test void topLevelNullMapKeyRejectedBeforeDtoCopy() {settlement.put(null,1);assertThrows(IllegalStateException.class,()->reader.readByCustomers(List.of(7L)));}
    @Test void exactFractionalPrecisionRemainsRejected() {when(mapper.timePrecisions()).thenReturn(List.of(Map.of("tableName","nx_direct_referral_settlement","columnName","updated_at","precision",new BigDecimal("0.5"))));assertThrows(ArithmeticException.class,reader::timePrecisions);}
    @Test void validPartialPrecisionMetadataPreservesExistingContract() {assertEquals(Map.of("nx_direct_referral_settlement.updated_at",0),reader.timePrecisions());}
}
