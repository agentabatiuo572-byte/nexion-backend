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
import ffdd.opsconsole.device.domain.TaskSchedulingSettings;
import ffdd.opsconsole.device.domain.DeviceTaskView;
import ffdd.opsconsole.device.dto.DeviceTaskUpsertRequest;
import ffdd.opsconsole.device.mapper.TaskSchedulingPolicyMapper;
import java.math.BigDecimal;

class MybatisDeviceCatalogRepositoryTest {
    @Test void computePolicySchemaIsReadyBeforeReadsAndPrecisionMigrationRefusesOverflow() {
        var policies=mock(TaskSchedulingPolicyMapper.class);
        var repository=new MybatisDeviceCatalogRepository(mapper,new ObjectMapper(),policies);
        when(mapper.taskRewardScale()).thenReturn(5);
        repository.ensureSchema();
        verify(mapper).widenTaskRewardScale(); verify(policies).ensureSchema();
        when(mapper.oversizedTaskRewardCount()).thenReturn(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(repository::ensureSchema).hasMessage("TASK_REWARD_SCALE_MIGRATION_OVERFLOW");
    }

    @Test void policySaveAndReadBackUseTheActualMapperAndOmittedFieldsPreserveExistingValues() {
        var policies=mock(TaskSchedulingPolicyMapper.class);
        var repository=new MybatisDeviceCatalogRepository(mapper,new ObjectMapper(),policies);
        var raw=new DeviceTaskView("EM-1","Embedding",BigDecimal.ONE,"/1k","手机+",BigDecimal.ZERO,"paused","EM","BGE-M3",BigDecimal.ZERO,BigDecimal.ONE,"8GB","派发中",now,now);
        when(mapper.findTask("EM-1")).thenReturn(raw);
        when(mapper.updateTask(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        var saved=new java.util.concurrent.atomic.AtomicReference<TaskSchedulingSettings>(new TaskSchedulingSettings(2,5,8,30,"test-bge-m3-v1","test-bge-m3-v1","{}"));
        when(policies.find("EM-1")).thenAnswer(call -> { var s=saved.get(); return new TaskSchedulingPolicyMapper.Policy("EM-1",s.selectionWeight(),s.expectedDurationMinSec(),s.expectedDurationMaxSec(),s.executionTimeoutSec(),s.runtimeKey(),s.validatorKey(),s.workloadConfigJson(),1); });
        when(policies.save(org.mockito.ArgumentMatchers.eq("EM-1"),org.mockito.ArgumentMatchers.any())).thenAnswer(call -> { saved.set(call.getArgument(1)); return 1; });
        var request=new DeviceTaskUpsertRequest("Embedding",BigDecimal.ONE,"/1k","手机+",BigDecimal.ZERO,"paused","EM","BGE-M3",BigDecimal.ZERO,BigDecimal.ONE,"8GB","派发中","test reason","operator",3,null,null,null,null,null,"{\"texts\":[\"hello\"]}");
        var updated=repository.updateTask("EM-1",request,now).orElseThrow();
        assertThat(updated.selectionWeight()).isEqualTo(3); assertThat(updated.expectedDurationMaxSec()).isEqualTo(8);
        assertThat(updated.runtimeKey()).isEqualTo("test-bge-m3-v1"); assertThat(updated.workloadConfigJson()).isEqualTo("{\"texts\":[\"hello\"]}");
        assertThat(repository.findTask("EM-1").orElseThrow().scheduling()).isEqualTo(saved.get());
        assertThat(updated.status()).isEqualTo("paused");
    }
    @Test void unreservedUnpaidOrderClosureNeverRestocksOrTouchesAssets() {
        when(mapper.orderRestockPlan("UNPAID-0")).thenReturn(
                new DeviceCatalogMapper.OrderRestockPlan("SINGLE",1,1,1L,1L,0L,1L,false));
        assertThat(repository.rollbackOrderAssets("UNPAID-0",now)).isTrue();
        verify(mapper,never()).rollbackOrderDevices("UNPAID-0",now);
        verify(mapper,never()).restockOrderProduct("UNPAID-0",now);
        verify(mapper,never()).restockOrderItemProducts("UNPAID-0",now);
    }

    private final DeviceCatalogMapper mapper = mock(DeviceCatalogMapper.class);
    private final MybatisDeviceCatalogRepository repository =
            new MybatisDeviceCatalogRepository(mapper, new ObjectMapper());
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 15, 12, 0);

    @Test
    void existingPurchaseGateColumnPreservesExplicitNoGateOnEveryStartup() {
        when(mapper.countSkuPurchaseGateColumn()).thenReturn(1);

        repository.ensureSchema();
        repository.ensureSchema();

        verify(mapper, never()).addSkuPurchaseGateColumn();
        verify(mapper, never()).seedGen2PurchaseGatesIfMissing();
    }

    @Test
    void legacySchemaGetsDefaultsOnlyWhenThePurchaseGateColumnIsIntroduced() {
        when(mapper.countSkuPurchaseGateColumn()).thenReturn(0, 1);

        repository.ensureSchema();
        repository.ensureSchema();

        var ordered = org.mockito.Mockito.inOrder(mapper);
        ordered.verify(mapper).addSkuPurchaseGateColumn();
        ordered.verify(mapper).seedGen2PurchaseGatesIfMissing();
        verify(mapper, org.mockito.Mockito.times(1)).seedGen2PurchaseGatesIfMissing();
    }

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
    void refundRestocksAllUnitsWhenBundleRowCountDiffersFromQuantity() {
        when(mapper.orderRestockPlan("OD-BUNDLE-MULTI")).thenReturn(
                new DeviceCatalogMapper.OrderRestockPlan("BUNDLE", 5, 2, 2L, 5L, 0L, 2L));
        when(mapper.restockOrderItemProducts("OD-BUNDLE-MULTI", now)).thenReturn(2);

        assertThat(repository.rollbackOrderAssets("OD-BUNDLE-MULTI", now)).isTrue();
        verify(mapper).restockOrderItemProducts("OD-BUNDLE-MULTI", now);
        verify(mapper, never()).restockOrderProduct("OD-BUNDLE-MULTI", now);
    }

    @Test
    void refundRejectsDuplicateBundleSkuRowsEvenWhenTotalQuantityMatches() {
        when(mapper.orderRestockPlan("OD-BUNDLE-DUP")).thenReturn(
                new DeviceCatalogMapper.OrderRestockPlan("BUNDLE", 5, 2, 2L, 5L, 0L, 1L));

        assertThat(repository.rollbackOrderAssets("OD-BUNDLE-DUP", now)).isFalse();
        verify(mapper, never()).restockOrderItemProducts("OD-BUNDLE-DUP", now);
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
