package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.market.mapper.SupportExchangeSourceMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class SupportExchangeReadServiceTest {
    @Test void timestampPrecisionComesOnlyFromExactOwningMetadataRows(){
        var mapper=mock(SupportExchangeSourceMapper.class);var service=new SupportExchangeReadService(mapper);
        var row=Map.<String,Object>of("tableName","nx_exchange_order","columnName","updated_at","precision",0);when(mapper.timePrecisions()).thenReturn(List.of(row));assertThat(service.timePrecisions()).containsExactlyEntriesOf(Map.of("nx_exchange_order.updated_at",0));
        when(mapper.timePrecisions()).thenReturn(List.of());assertThat(service.timePrecisions()).isEmpty();when(mapper.timePrecisions()).thenReturn(List.of(row,row));assertThatThrownBy(service::timePrecisions).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void owningFacadeProducesTheSameEventsAndNeverInventsALegacyHold(){
        var mapper=mock(SupportExchangeSourceMapper.class);var service=new SupportExchangeReadService(mapper);
        var order=SupportExchangeFundBindingTest.order("COMPLETED");
        var source=new ffdd.opsconsole.market.facade.SupportExchangeReadFacade.Source(order,List.of(),List.of(SupportExchangeFundBindingTest.fee()));
        assertThat(service.events(source,"IMMEDIATE")).isEqualTo(SupportExchangeFundAdapter.events(7,"EX-abcdefgh","IMMEDIATE","USDT",new java.math.BigDecimal("20"),new java.math.BigDecimal("6.333333"),java.math.BigDecimal.ONE));
        assertThat(service.events(source,"CANCEL")).isEmpty();
        assertThat(service.events(source,"COMPLETE")).isEqualTo(service.events(source,"IMMEDIATE"));
        verifyNoInteractions(mapper);
    }
    @Test void fullSourceBatchRetainsContradictoryAndDeletedOriginalsWithoutPerOrderReads(){
        var mapper=mock(SupportExchangeSourceMapper.class);var service=new SupportExchangeReadService(mapper);
        var scope=List.of(1L,2L);var orders=List.of(Map.<String,Object>of("exchangeNo","EX-one","deleted",1),Map.<String,Object>of("exchangeNo","EX-two","status","CANCELLED"));
        when(mapper.byCustomers(new TreeSet<>(scope))).thenReturn(orders);
        when(mapper.ledgers(List.of("EX-one","EX-two"))).thenReturn(List.of(Map.of("exchangeNo","EX-one","customerId",99L,"amount",3)));
        when(mapper.fees(List.of("EX-one","EX-two"))).thenReturn(List.of(Map.of("exchangeNo","EX-one","totalFeeUsdt",1),Map.of("exchangeNo","EX-one","totalFeeUsdt",2)));
        var sources=service.readByCustomers(scope);assertThat(sources).hasSize(2);
        assertThat(sources.get(0).order().get("deleted")).isEqualTo(1);assertThat(sources.get(0).ledgers().get(0).get("customerId")).isEqualTo(99L);assertThat(sources.get(0).feeAllocations()).hasSize(2);
        assertThat(sources.get(1).ledgers()).isEmpty();assertThat(sources.get(1).feeAllocations()).isEmpty();
        verify(mapper,times(1)).byCustomers(any());verify(mapper,times(1)).ledgers(any());verify(mapper,times(1)).fees(any());verifyNoMoreInteractions(mapper);
        assertThatThrownBy(()->sources.get(0).order().put("deleted",0)).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void emptyScopeDoesNotTouchDatabaseAndInvalidScopeFailsBeforeQuery(){
        var mapper=mock(SupportExchangeSourceMapper.class);var service=new SupportExchangeReadService(mapper);
        assertThat(service.readByCustomers(List.of())).isEmpty();assertThat(service.readByOrderNos(List.of())).isEmpty();
        assertThatThrownBy(()->service.readByCustomers(List.of(0L))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(mapper);
    }
}
