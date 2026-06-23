package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.DeviceOrderView;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.domain.DevicePhoneTierRewardView;
import ffdd.opsconsole.device.domain.DeviceReviewView;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.device.domain.DeviceTaskView;
import ffdd.opsconsole.device.domain.DeviceTradeinOverviewView;
import ffdd.opsconsole.device.dto.DatacenterOpsRequest;
import ffdd.opsconsole.device.dto.DevicePhoneTierRewardUpdateRequest;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.device.dto.DeviceOrderQueryRequest;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import ffdd.opsconsole.device.dto.DeviceReviewQueryRequest;
import ffdd.opsconsole.device.dto.DeviceReviewStatusRequest;
import ffdd.opsconsole.device.dto.DeviceReviewUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceRestoreRequest;
import ffdd.opsconsole.device.dto.DeviceSkuQueryRequest;
import ffdd.opsconsole.device.dto.DeviceSkuStatusRequest;
import ffdd.opsconsole.device.dto.DeviceSkuUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceTaskPriceRequest;
import ffdd.opsconsole.device.dto.DeviceTaskQueryRequest;
import ffdd.opsconsole.device.dto.DeviceTaskStatusRequest;
import ffdd.opsconsole.device.dto.DeviceTaskUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceTradeinActionRequest;
import ffdd.opsconsole.device.dto.E3ConfigUpdateRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsDeviceControllerTest {
    private final OpsDeviceService deviceService = mock(OpsDeviceService.class);
    private final OpsDeviceController controller = new OpsDeviceController(deviceService);

    @Test
    void devicesDelegatesQueryToService() {
        DeviceOpsQueryRequest request = new DeviceOpsQueryRequest("OFFLINE", "HK-1", "NX", 1L, 20L);
        when(deviceService.devices(request)).thenReturn(ApiResult.ok(new PageResult<>(0, 1, 20, List.of())));

        assertThat(controller.devices(request).getData().getPageNum()).isEqualTo(1);

        verify(deviceService).devices(request);
    }

    @Test
    void restoreDelegatesWithIdempotencyHeader() {
        DeviceRestoreRequest request = new DeviceRestoreRequest("mistaken recycle", "superadmin");
        when(deviceService.restoreDevice(1L, "idem-restore", request)).thenReturn(ApiResult.ok(mock(DeviceOpsView.class)));

        assertThat(controller.restoreDevice(1L, "idem-restore", request).getCode()).isZero();

        verify(deviceService).restoreDevice(1L, "idem-restore", request);
    }

    @Test
    void e3ConfigDelegatesWithIdempotencyHeader() {
        E3ConfigUpdateRequest request = new E3ConfigUpdateRequest("promoCooldownDays", "21", "holiday", "superadmin");
        when(deviceService.updateE3Config("idem-e3", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateE3Config("idem-e3", request).getData()).containsEntry("ok", true);

        verify(deviceService).updateE3Config("idem-e3", request);
    }

    @Test
    void e1GenerationGateDelegatesWithIdempotencyHeader() {
        E3ConfigUpdateRequest request =
                new E3ConfigUpdateRequest("E.gen.stellarbox-pro-v2.phaseOffset", "2", "stage release", "superadmin");
        when(deviceService.e1GenerationGates()).thenReturn(ApiResult.ok(Map.of("domain", "E1")));
        when(deviceService.updateE1GenerationGate("idem-e1-gate", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.e1GenerationGates().getData()).containsEntry("domain", "E1");
        assertThat(controller.updateE1GenerationGate("idem-e1-gate", request).getData()).containsEntry("ok", true);

        verify(deviceService).e1GenerationGates();
        verify(deviceService).updateE1GenerationGate("idem-e1-gate", request);
    }

    @Test
    void e3TradeinDelegatesWithIdempotencyHeader() {
        DeviceTradeinActionRequest request = new DeviceTradeinActionRequest(1L, "ops replace", "superadmin");
        when(deviceService.e3TradeinOverview()).thenReturn(ApiResult.ok(mock(DeviceTradeinOverviewView.class)));
        when(deviceService.executeTradeinAction("replace", "idem-tradein", request)).thenReturn(ApiResult.ok(mock(DeviceOpsView.class)));

        assertThat(controller.e3TradeinOverview().getCode()).isZero();
        assertThat(controller.executeTradeinAction("replace", "idem-tradein", request).getCode()).isZero();

        verify(deviceService).e3TradeinOverview();
        verify(deviceService).executeTradeinAction("replace", "idem-tradein", request);
    }

    @Test
    void datacenterPauseDelegatesWithIdempotencyHeader() {
        DatacenterOpsRequest request = new DatacenterOpsRequest("maintenance", "superadmin");
        when(deviceService.pauseDatacenter("HK-1", "idem-dc", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.pauseDatacenter("HK-1", "idem-dc", request).getCode()).isZero();

        verify(deviceService).pauseDatacenter("HK-1", "idem-dc", request);
    }

    @Test
    void skuCrudDelegatesWithIdempotencyHeader() {
        DeviceSkuQueryRequest query = new DeviceSkuQueryRequest("on", "box", 1L, 20L);
        DeviceSkuUpsertRequest request = skuRequest();
        DeviceSkuStatusRequest status = new DeviceSkuStatusRequest("off", "maintenance", "superadmin");
        when(deviceService.skus(query)).thenReturn(ApiResult.ok(new PageResult<>(0, 1, 20, List.of())));
        when(deviceService.createSku("idem-sku", request)).thenReturn(ApiResult.ok(mock(DeviceSkuView.class)));
        when(deviceService.updateSku("stellarbox-test", "idem-sku", request)).thenReturn(ApiResult.ok(mock(DeviceSkuView.class)));
        when(deviceService.updateSkuStatus("stellarbox-test", "idem-sku", status)).thenReturn(ApiResult.ok(mock(DeviceSkuView.class)));
        when(deviceService.deleteSku("stellarbox-test", "idem-sku", status)).thenReturn(ApiResult.ok(Map.of("deleted", true)));

        assertThat(controller.skus(query).getData().getTotal()).isZero();
        assertThat(controller.createSku("idem-sku", request).getCode()).isZero();
        assertThat(controller.updateSku("stellarbox-test", "idem-sku", request).getCode()).isZero();
        assertThat(controller.updateSkuStatus("stellarbox-test", "idem-sku", status).getCode()).isZero();
        assertThat(controller.deleteSku("stellarbox-test", "idem-sku", status).getData()).containsEntry("deleted", true);

        verify(deviceService).skus(query);
        verify(deviceService).createSku("idem-sku", request);
        verify(deviceService).updateSku("stellarbox-test", "idem-sku", request);
        verify(deviceService).updateSkuStatus("stellarbox-test", "idem-sku", status);
        verify(deviceService).deleteSku("stellarbox-test", "idem-sku", status);
    }

    @Test
    void reviewCrudDelegatesWithIdempotencyHeader() {
        DeviceReviewQueryRequest query = new DeviceReviewQueryRequest("stellarbox-test", "published", 5, "Maya", 1L, 20L);
        DeviceReviewUpsertRequest request = new DeviceReviewUpsertRequest("stellarbox-test", "Maya", 5, "Great", "刚刚", "published", "content ops", "superadmin");
        DeviceReviewStatusRequest status = new DeviceReviewStatusRequest("hidden", "content ops", "superadmin");
        when(deviceService.reviews(query)).thenReturn(ApiResult.ok(new PageResult<>(0, 1, 20, List.of())));
        when(deviceService.createReview("idem-review", request)).thenReturn(ApiResult.ok(mock(DeviceReviewView.class)));
        when(deviceService.updateReview("rv-1", "idem-review", request)).thenReturn(ApiResult.ok(mock(DeviceReviewView.class)));
        when(deviceService.updateReviewStatus("rv-1", "idem-review", status)).thenReturn(ApiResult.ok(mock(DeviceReviewView.class)));
        when(deviceService.deleteReview("rv-1", "idem-review", status)).thenReturn(ApiResult.ok(Map.of("deleted", true)));

        assertThat(controller.reviews(query).getData().getTotal()).isZero();
        assertThat(controller.createReview("idem-review", request).getCode()).isZero();
        assertThat(controller.updateReview("rv-1", "idem-review", request).getCode()).isZero();
        assertThat(controller.updateReviewStatus("rv-1", "idem-review", status).getCode()).isZero();
        assertThat(controller.deleteReview("rv-1", "idem-review", status).getData()).containsEntry("deleted", true);

        verify(deviceService).reviews(query);
        verify(deviceService).createReview("idem-review", request);
        verify(deviceService).updateReview("rv-1", "idem-review", request);
        verify(deviceService).updateReviewStatus("rv-1", "idem-review", status);
        verify(deviceService).deleteReview("rv-1", "idem-review", status);
    }

    @Test
    void taskCrudDelegatesWithIdempotencyHeader() {
        DeviceTaskQueryRequest query = new DeviceTaskQueryRequest("active", "LLM", 1L, 20L);
        DeviceTaskUpsertRequest request = new DeviceTaskUpsertRequest(
                "LLM 推理 70B",
                new BigDecimal("0.46"),
                "/job",
                "S1+",
                new BigDecimal("0.61"),
                "active",
                "llm-inference",
                "Llama-3.1-70B",
                new BigDecimal("0.30"),
                new BigDecimal("0.90"),
                "24GB",
                "派发中",
                "new task",
                "superadmin");
        DeviceTaskPriceRequest price = new DeviceTaskPriceRequest(new BigDecimal("0.60"), "rebalance", "superadmin");
        DeviceTaskStatusRequest status = new DeviceTaskStatusRequest("inactive", "retire", "superadmin");
        when(deviceService.tasks(query)).thenReturn(ApiResult.ok(new PageResult<>(0, 1, 20, List.of())));
        when(deviceService.createTask("idem-task", request)).thenReturn(ApiResult.ok(mock(DeviceTaskView.class)));
        when(deviceService.updateTaskPrice("TK-1", "idem-task", price)).thenReturn(ApiResult.ok(mock(DeviceTaskView.class)));
        when(deviceService.updateTaskStatus("TK-1", "idem-task", status)).thenReturn(ApiResult.ok(mock(DeviceTaskView.class)));
        when(deviceService.deleteTask("TK-1", "idem-task", status)).thenReturn(ApiResult.ok(Map.of("deleted", true)));

        assertThat(controller.tasks(query).getData().getTotal()).isZero();
        assertThat(controller.createTask("idem-task", request).getCode()).isZero();
        assertThat(controller.updateTaskPrice("TK-1", "idem-task", price).getCode()).isZero();
        assertThat(controller.updateTaskStatus("TK-1", "idem-task", status).getCode()).isZero();
        assertThat(controller.deleteTask("TK-1", "idem-task", status).getData()).containsEntry("deleted", true);

        verify(deviceService).tasks(query);
        verify(deviceService).createTask("idem-task", request);
        verify(deviceService).updateTaskPrice("TK-1", "idem-task", price);
        verify(deviceService).updateTaskStatus("TK-1", "idem-task", status);
        verify(deviceService).deleteTask("TK-1", "idem-task", status);
    }

    @Test
    void phoneTierRewardsDelegateWithIdempotencyHeader() {
        DevicePhoneTierRewardUpdateRequest request =
                new DevicePhoneTierRewardUpdateRequest(new BigDecimal("0.07"), null, "tier rebalance", "superadmin");
        when(deviceService.phoneTierRewards()).thenReturn(ApiResult.ok(List.of(mock(DevicePhoneTierRewardView.class))));
        when(deviceService.updatePhoneTierReward(3, "idem-tier", request)).thenReturn(ApiResult.ok(mock(DevicePhoneTierRewardView.class)));

        assertThat(controller.phoneTierRewards().getData()).hasSize(1);
        assertThat(controller.updatePhoneTierReward(3, "idem-tier", request).getCode()).isZero();

        verify(deviceService).phoneTierRewards();
        verify(deviceService).updatePhoneTierReward(3, "idem-tier", request);
    }

    @Test
    void orderActionsDelegateWithIdempotencyHeader() {
        DeviceOrderQueryRequest query = new DeviceOrderQueryRequest("failed", "OD", 1L, 20L);
        DeviceOrderActionRequest request = new DeviceOrderActionRequest("provisioning_failed", "dc timeout", "superadmin");
        when(deviceService.orders(query)).thenReturn(ApiResult.ok(new PageResult<>(0, 1, 20, List.of())));
        when(deviceService.refundOrder("OD-1", "idem-order", request)).thenReturn(ApiResult.ok(mock(DeviceOrderView.class)));
        when(deviceService.cancelOrder("OD-1", "idem-order", request)).thenReturn(ApiResult.ok(mock(DeviceOrderView.class)));
        when(deviceService.terminalOrder("OD-1", "idem-order", request)).thenReturn(ApiResult.ok(mock(DeviceOrderView.class)));

        assertThat(controller.orders(query).getData().getTotal()).isZero();
        assertThat(controller.refundOrder("OD-1", "idem-order", request).getCode()).isZero();
        assertThat(controller.cancelOrder("OD-1", "idem-order", request).getCode()).isZero();
        assertThat(controller.terminalOrder("OD-1", "idem-order", request).getCode()).isZero();

        verify(deviceService).orders(query);
        verify(deviceService).refundOrder("OD-1", "idem-order", request);
        verify(deviceService).cancelOrder("OD-1", "idem-order", request);
        verify(deviceService).terminalOrder("OD-1", "idem-order", request);
    }

    private static DeviceSkuUpsertRequest skuRequest() {
        return new DeviceSkuUpsertRequest(
                "stellarbox-test",
                "NexionBox Test",
                "Entry",
                "test",
                "New",
                "4x GPU",
                "96GB",
                "100 MH/s",
                "1200W",
                "HK-1",
                new BigDecimal("1299"),
                new BigDecimal("12.3"),
                new BigDecimal("24"),
                null,
                null,
                "$12.30/d",
                1L,
                "10",
                new BigDecimal("4.8"),
                2L,
                100L,
                1000L,
                10L,
                5L,
                "LLM pool",
                List.of("managed"),
                1,
                "active",
                "",
                BigDecimal.ZERO,
                "P1",
                null,
                null,
                null,
                null,
                "popular",
                "pending",
                "catalog update",
                "superadmin");
    }
}
