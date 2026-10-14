package ffdd.opsconsole.market.application;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.market.mapper.SupportGenesisSourceMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
class SupportGenesisReadServiceTest {
    final SupportGenesisSourceMapper mapper=mock(SupportGenesisSourceMapper.class);
    final SupportGenesisReadService service=new SupportGenesisReadService(mapper);
    @Test void explicitSortedScopeReadsBuyerAndSellerOrdersAndOneFixedLedgerBatchWithoutFilteringDeletedOriginals() {
        when(mapper.ordersByCustomers(List.of(3L,7L))).thenReturn(List.of(Map.of("orderNo","B","deleted",1),Map.of("orderNo","A","deleted",0)));
        when(mapper.ledgers(List.of("A","A-BUY","A-SELL","B","B-BUY","B-SELL"))).thenReturn(List.of(
            Map.of("businessId","A-BUY","id",1),Map.of("businessId","A-SELL","id",2),Map.of("businessId","B","id",3)));
        var rows=service.readByCustomers(List.of(7L,3L,7L));assertThat(rows).hasSize(2);
        assertThat(rows.get(0).order()).containsEntry("deleted",1);assertThat(rows.get(0).ledgers()).hasSize(1);assertThat(rows.get(1).ledgers()).hasSize(2);
        verify(mapper,times(1)).ordersByCustomers(List.of(3L,7L));verify(mapper,times(1)).ledgers(anyList());
        assertThatThrownBy(()->rows.get(0).order().put("deleted",0)).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void emptyScopeIsForbiddenAndMissingReadIsNotAnEmptySource() {
        assertThatThrownBy(()->service.readByCustomers(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.readByOrderNos(List.of())).isInstanceOf(IllegalArgumentException.class);
        when(mapper.ordersByNos(List.of("A"))).thenReturn(null);
        assertThatThrownBy(()->service.readByOrderNos(List.of("A"))).hasMessage("GENESIS_SOURCE_READ_MISSING");
        when(mapper.ordersByNos(List.of("A"))).thenReturn(List.of(Map.of("orderNo","A")));
        when(mapper.ledgers(anyList())).thenReturn(null);
        assertThatThrownBy(()->service.readByOrderNos(List.of("A"))).hasMessage("GENESIS_LEDGER_READ_MISSING");
    }
    @Test void provenEmptyOrderReadDoesNotQueryLedgers() {
        when(mapper.ordersByNos(List.of("A"))).thenReturn(List.of());assertThat(service.readByOrderNos(List.of("A"))).isEmpty();verify(mapper,never()).ledgers(anyList());
    }
    @Test void missingLedgerIdentityIsAReadFailureRatherThanDroppingAnUnattributableRow() {
        when(mapper.ordersByNos(List.of("A"))).thenReturn(List.of(Map.of("orderNo","A")));
        when(mapper.ledgers(anyList())).thenReturn(List.of(Map.of("id",1)));
        assertThatThrownBy(()->service.readByOrderNos(List.of("A"))).isInstanceOf(org.springframework.dao.DataRetrievalFailureException.class);
    }
}
