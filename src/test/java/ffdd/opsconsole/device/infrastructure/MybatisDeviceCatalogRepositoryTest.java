package ffdd.opsconsole.device.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.mapper.DeviceCatalogMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MybatisDeviceCatalogRepositoryTest {

    private final DeviceCatalogMapper mapper = mock(DeviceCatalogMapper.class);
    private final MybatisDeviceCatalogRepository repository =
            new MybatisDeviceCatalogRepository(mapper, new ObjectMapper());
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 15, 12, 0);

    @Test
    void rollbackOrderAssetsRestocksEveryDistinctOrderItemProductForBundles() {
        when(mapper.orderRestockPlan("OD-BUNDLE")).thenReturn(
                new DeviceCatalogMapper.OrderRestockPlan("BUNDLE", 2, 2, 2L, 2L, 0L, 2L));
        when(mapper.restockOrderItemProducts("OD-BUNDLE", now)).thenReturn(2);

        boolean restored = repository.rollbackOrderAssets("OD-BUNDLE", now);

        assertThat(restored).isTrue();
        verify(mapper).rollbackOrderDevices("OD-BUNDLE", now);
        verify(mapper, never()).restockOrderProduct("OD-BUNDLE", now);
    }

    @Test
    void rollbackOrderAssetsFailsWhenAnyBundleProductCannotBeRestocked() {
        when(mapper.orderRestockPlan("OD-BUNDLE")).thenReturn(
                new DeviceCatalogMapper.OrderRestockPlan("BUNDLE", 2, 2, 2L, 2L, 0L, 2L));
        when(mapper.restockOrderItemProducts("OD-BUNDLE", now)).thenReturn(1);

        assertThat(repository.rollbackOrderAssets("OD-BUNDLE", now)).isFalse();
    }

    @Test
    void rollbackOrderAssetsRestocksCapacityKeepOrdersFromTheirOrderItem() {
        when(mapper.orderRestockPlan("OD-CAPACITY-KEEP")).thenReturn(
                new DeviceCatalogMapper.OrderRestockPlan("CAPACITY_KEEP", 1, 1, 1L, 1L, 0L, 1L));
        when(mapper.restockOrderItemProducts("OD-CAPACITY-KEEP", now)).thenReturn(1);

        boolean restored = repository.rollbackOrderAssets("OD-CAPACITY-KEEP", now);

        assertThat(restored).isTrue();
        verify(mapper).rollbackOrderDevices("OD-CAPACITY-KEEP", now);
        verify(mapper, never()).restockOrderProduct("OD-CAPACITY-KEEP", now);
    }

    @Test
    void rollbackOrderAssetsFallsBackForHistoricalSingleOrdersWithoutItems() {
        when(mapper.orderRestockPlan("OD-SINGLE")).thenReturn(
                new DeviceCatalogMapper.OrderRestockPlan("SINGLE", 1, 1, 0L, 0L, 0L, 0L));
        when(mapper.restockOrderProduct("OD-SINGLE", now)).thenReturn(1);

        boolean restored = repository.rollbackOrderAssets("OD-SINGLE", now);

        assertThat(restored).isTrue();
        verify(mapper, never()).restockOrderItemProducts("OD-SINGLE", now);
    }

    @Test
    void rollbackOrderAssetsRejectsBundleWithMissingItemRows() {
        when(mapper.orderRestockPlan("OD-BUNDLE-PARTIAL")).thenReturn(
                new DeviceCatalogMapper.OrderRestockPlan("BUNDLE", 2, 2, 1L, 1L, 0L, 1L));

        assertThat(repository.rollbackOrderAssets("OD-BUNDLE-PARTIAL", now)).isFalse();
        verify(mapper, never()).restockOrderItemProducts("OD-BUNDLE-PARTIAL", now);
        verify(mapper, never()).restockOrderProduct("OD-BUNDLE-PARTIAL", now);
    }

    @Test
    void rollbackOrderAssetsNeverUsesSingleProductFallbackForBundleWithoutItems() {
        when(mapper.orderRestockPlan("OD-BUNDLE-EMPTY")).thenReturn(
                new DeviceCatalogMapper.OrderRestockPlan("BUNDLE", 2, 2, 0L, 0L, 0L, 0L));

        assertThat(repository.rollbackOrderAssets("OD-BUNDLE-EMPTY", now)).isFalse();
        verify(mapper, never()).restockOrderProduct("OD-BUNDLE-EMPTY", now);
    }
}
