package ffdd.opsconsole.device.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.ComputeConfigView;
import ffdd.opsconsole.device.domain.DeviceDatacenterView;
import ffdd.opsconsole.device.domain.DeviceOrderView;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.domain.DevicePhoneTierRewardView;
import ffdd.opsconsole.device.domain.DeviceReviewView;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.device.domain.DeviceTaskView;
import ffdd.opsconsole.device.domain.DeviceTradeinOverviewView;
import ffdd.opsconsole.device.dto.ComputeConfigParamResponse;
import ffdd.opsconsole.device.dto.ComputeConfigParamUpdateRequest;
import ffdd.opsconsole.device.dto.DatacenterOpsRequest;
import ffdd.opsconsole.device.dto.DeviceDatacenterUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceGenerationGateArchiveRequest;
import ffdd.opsconsole.device.dto.DeviceGenerationGatePatchRequest;
import ffdd.opsconsole.device.dto.DeviceGenerationGateUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.device.dto.DeviceOrderQueryRequest;
import ffdd.opsconsole.device.dto.DeviceOrderStateRequest;
import ffdd.opsconsole.device.dto.DevicePhaseArchiveRequest;
import ffdd.opsconsole.device.dto.DevicePhaseCurrentRequest;
import ffdd.opsconsole.device.dto.DevicePhaseUpsertRequest;
import ffdd.opsconsole.device.dto.DevicePhoneTierRewardUpdateRequest;
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
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/devices")
@RequiredArgsConstructor
public class OpsDeviceController {
    private final OpsDeviceService deviceService;

    @GetMapping("/overview")
    @PreAuthorize("hasAnyAuthority('device_e1_read','device_e2_read','device_e3_read','device_e4_read','device_e5_read','device_e6_read')")
    public ApiResult<Map<String, Object>> overview() {
        return deviceService.overview();
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('device_e1_read','device_e2_read','device_e3_read','device_e4_read','device_e5_read','device_e6_read')")
    public ApiResult<PageResult<DeviceOpsView>> devices(DeviceOpsQueryRequest request) {
        return deviceService.devices(request);
    }

    @GetMapping("/skus")
    @PreAuthorize("hasAuthority('device_e1_read')")
    public ApiResult<PageResult<DeviceSkuView>> skus(DeviceSkuQueryRequest request) {
        return deviceService.skus(request);
    }

    @GetMapping("/skus/{skuId}")
    @PreAuthorize("hasAuthority('device_e1_read')")
    public ApiResult<DeviceSkuView> sku(@PathVariable String skuId) {
        return deviceService.sku(skuId);
    }

    @PostMapping("/skus")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<DeviceSkuView> createSku(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceSkuUpsertRequest request) {
        return deviceService.createSku(idempotencyKey, request);
    }

    @PutMapping("/skus/{skuId}")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<DeviceSkuView> updateSku(
            @PathVariable String skuId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceSkuUpsertRequest request) {
        return deviceService.updateSku(skuId, idempotencyKey, request);
    }

    @PatchMapping("/skus/{skuId}/status")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<DeviceSkuView> updateSkuStatus(
            @PathVariable String skuId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceSkuStatusRequest request) {
        return deviceService.updateSkuStatus(skuId, idempotencyKey, request);
    }

    @DeleteMapping("/skus/{skuId}")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<Map<String, Object>> deleteSku(
            @PathVariable String skuId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceSkuStatusRequest request) {
        return deviceService.deleteSku(skuId, idempotencyKey, request);
    }

    @GetMapping("/reviews")
    @PreAuthorize("hasAuthority('device_e1_read')")
    public ApiResult<PageResult<DeviceReviewView>> reviews(DeviceReviewQueryRequest request) {
        return deviceService.reviews(request);
    }

    @PostMapping("/reviews")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<DeviceReviewView> createReview(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceReviewUpsertRequest request) {
        return deviceService.createReview(idempotencyKey, request);
    }

    @PutMapping("/reviews/{reviewId}")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<DeviceReviewView> updateReview(
            @PathVariable String reviewId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceReviewUpsertRequest request) {
        return deviceService.updateReview(reviewId, idempotencyKey, request);
    }

    @PatchMapping("/reviews/{reviewId}/status")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<DeviceReviewView> updateReviewStatus(
            @PathVariable String reviewId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceReviewStatusRequest request) {
        return deviceService.updateReviewStatus(reviewId, idempotencyKey, request);
    }

    @DeleteMapping("/reviews/{reviewId}")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<Map<String, Object>> deleteReview(
            @PathVariable String reviewId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceReviewStatusRequest request) {
        return deviceService.deleteReview(reviewId, idempotencyKey, request);
    }

    @GetMapping("/tasks")
    @PreAuthorize("hasAuthority('device_e2_read')")
    public ApiResult<PageResult<DeviceTaskView>> tasks(DeviceTaskQueryRequest request) {
        return deviceService.tasks(request);
    }

    @GetMapping("/phone-tiers")
    @PreAuthorize("hasAuthority('device_e2_read')")
    public ApiResult<List<DevicePhoneTierRewardView>> phoneTierRewards() {
        return deviceService.phoneTierRewards();
    }

    @PatchMapping("/phone-tiers/{tier}")
    @PreAuthorize("hasAnyAuthority('device_e2_phone_tier_usdt','device_e2_phone_tier_nex')")
    public ApiResult<DevicePhoneTierRewardView> updatePhoneTierReward(
            @PathVariable Integer tier,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DevicePhoneTierRewardUpdateRequest request) {
        return deviceService.updatePhoneTierReward(tier, idempotencyKey, request);
    }

    @GetMapping("/compute-config")
    @PreAuthorize("hasAuthority('device_e6_read')")
    public ApiResult<ComputeConfigView> computeConfig() {
        return deviceService.computeConfig();
    }

    @PatchMapping("/compute-config/params/{paramKey}")
    @PreAuthorize("hasAuthority('device_e6_write')")
    public ApiResult<ComputeConfigParamResponse> updateComputeConfigParam(
            @PathVariable String paramKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ComputeConfigParamUpdateRequest request) {
        return deviceService.updateComputeConfigParam(paramKey, idempotencyKey, request);
    }

    @PostMapping("/tasks")
    @PreAuthorize("hasAuthority('device_e2_write')")
    public ApiResult<DeviceTaskView> createTask(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceTaskUpsertRequest request) {
        return deviceService.createTask(idempotencyKey, request);
    }

    @PutMapping("/tasks/{taskId}")
    @PreAuthorize("hasAuthority('device_e2_write')")
    public ApiResult<DeviceTaskView> updateTask(
            @PathVariable String taskId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceTaskUpsertRequest request) {
        return deviceService.updateTask(taskId, idempotencyKey, request);
    }

    @PatchMapping("/tasks/{taskId}/price")
    @PreAuthorize("hasAuthority('device_e2_write')")
    public ApiResult<DeviceTaskView> updateTaskPrice(
            @PathVariable String taskId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceTaskPriceRequest request) {
        return deviceService.updateTaskPrice(taskId, idempotencyKey, request);
    }

    @PatchMapping("/tasks/{taskId}/status")
    @PreAuthorize("hasAuthority('device_e2_write')")
    public ApiResult<DeviceTaskView> updateTaskStatus(
            @PathVariable String taskId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceTaskStatusRequest request) {
        return deviceService.updateTaskStatus(taskId, idempotencyKey, request);
    }

    @DeleteMapping("/tasks/{taskId}")
    @PreAuthorize("hasAuthority('device_e2_write')")
    public ApiResult<Map<String, Object>> deleteTask(
            @PathVariable String taskId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceTaskStatusRequest request) {
        return deviceService.deleteTask(taskId, idempotencyKey, request);
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('device_e4_read')")
    public ApiResult<PageResult<DeviceOrderView>> orders(DeviceOrderQueryRequest request) {
        return deviceService.orders(request);
    }

    @PatchMapping("/orders/{orderNo}/refund")
    @PreAuthorize("hasAuthority('device_e4_order_refund')")
    public ApiResult<DeviceOrderView> refundOrder(
            @PathVariable String orderNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceOrderActionRequest request) {
        return deviceService.refundOrder(orderNo, idempotencyKey, request);
    }

    @PatchMapping("/orders/{orderNo}/cancel")
    @PreAuthorize("hasAuthority('device_e4_write')")
    public ApiResult<DeviceOrderView> cancelOrder(
            @PathVariable String orderNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceOrderActionRequest request) {
        return deviceService.cancelOrder(orderNo, idempotencyKey, request);
    }

    @PatchMapping("/orders/{orderNo}/terminal")
    @PreAuthorize("hasAuthority('device_e4_write')")
    public ApiResult<DeviceOrderView> terminalOrder(
            @PathVariable String orderNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceOrderActionRequest request) {
        return deviceService.terminalOrder(orderNo, idempotencyKey, request);
    }

    @PatchMapping("/orders/{orderNo}/state")
    @PreAuthorize("hasAuthority('device_e4_write')")
    public ApiResult<DeviceOrderView> updateOrderState(
            @PathVariable String orderNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceOrderStateRequest request) {
        return deviceService.updateOrderState(orderNo, idempotencyKey, request);
    }

    @GetMapping("/e1/generation-gates")
    @PreAuthorize("hasAuthority('device_e1_read')")
    public ApiResult<Map<String, Object>> e1GenerationGates() {
        return deviceService.e1GenerationGates();
    }

    @PostMapping("/e1/phases")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<Map<String, Object>> createE1Phase(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DevicePhaseUpsertRequest request) {
        return deviceService.createE1Phase(idempotencyKey, request);
    }

    @PatchMapping("/e1/phases/{phaseId}")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<Map<String, Object>> patchE1Phase(
            @PathVariable String phaseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DevicePhaseUpsertRequest request) {
        return deviceService.patchE1Phase(phaseId, idempotencyKey, request);
    }

    @PatchMapping("/e1/phases/{phaseId}/current")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<Map<String, Object>> setE1CurrentPhase(
            @PathVariable String phaseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DevicePhaseCurrentRequest request) {
        return deviceService.setE1CurrentPhase(phaseId, idempotencyKey, request);
    }

    @DeleteMapping("/e1/phases/{phaseId}")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<Map<String, Object>> archiveE1Phase(
            @PathVariable String phaseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DevicePhaseArchiveRequest request) {
        return deviceService.archiveE1Phase(phaseId, idempotencyKey, request);
    }

    @PatchMapping("/e1/generation-gates")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<Map<String, Object>> updateE1GenerationGate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody E3ConfigUpdateRequest request) {
        return deviceService.updateE1GenerationGate(idempotencyKey, request);
    }

    @PostMapping("/e1/generation-gates")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<Map<String, Object>> createE1GenerationGate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceGenerationGateUpsertRequest request) {
        return deviceService.createE1GenerationGate(idempotencyKey, request);
    }

    @PatchMapping("/e1/generation-gates/{skuId}")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<Map<String, Object>> patchE1GenerationGate(
            @PathVariable String skuId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceGenerationGatePatchRequest request) {
        return deviceService.patchE1GenerationGate(skuId, idempotencyKey, request);
    }

    @DeleteMapping("/e1/generation-gates/{skuId}")
    @PreAuthorize("hasAuthority('device_e1_write')")
    public ApiResult<Map<String, Object>> archiveE1GenerationGate(
            @PathVariable String skuId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceGenerationGateArchiveRequest request) {
        return deviceService.archiveE1GenerationGate(skuId, idempotencyKey, request);
    }

    @GetMapping("/e3/overview")
    @PreAuthorize("hasAuthority('device_e3_read')")
    public ApiResult<Map<String, Object>> e3Overview() {
        return deviceService.e3Overview();
    }

    @PatchMapping("/e3/config")
    @PreAuthorize("hasAuthority('device_e3_write')")
    public ApiResult<Map<String, Object>> updateE3Config(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody E3ConfigUpdateRequest request) {
        return deviceService.updateE3Config(idempotencyKey, request);
    }

    @GetMapping("/e3/tradein/overview")
    @PreAuthorize("hasAuthority('device_e3_read')")
    public ApiResult<DeviceTradeinOverviewView> e3TradeinOverview() {
        return deviceService.e3TradeinOverview();
    }

    @PostMapping("/e3/tradein/{operation}")
    @PreAuthorize("hasAuthority('device_e3_write')")
    public ApiResult<DeviceOpsView> executeTradeinAction(
            @PathVariable String operation,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceTradeinActionRequest request) {
        return deviceService.executeTradeinAction(operation, idempotencyKey, request);
    }

    @PostMapping("/{deviceId}/restore")
    @PreAuthorize("hasAuthority('device_e5_write')")
    public ApiResult<DeviceOpsView> restoreDevice(
            @PathVariable Long deviceId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceRestoreRequest request) {
        return deviceService.restoreDevice(deviceId, idempotencyKey, request);
    }

    @GetMapping("/datacenters")
    @PreAuthorize("hasAuthority('device_e5_read')")
    public ApiResult<List<DeviceDatacenterView>> datacenters() {
        return deviceService.datacenters();
    }

    @PostMapping("/datacenters")
    @PreAuthorize("hasAuthority('device_e5_write')")
    public ApiResult<DeviceDatacenterView> createDatacenter(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceDatacenterUpsertRequest request) {
        return deviceService.createDatacenter(idempotencyKey, request);
    }

    @PatchMapping("/datacenters/{dcLocation}")
    @PreAuthorize("hasAuthority('device_e5_write')")
    public ApiResult<DeviceDatacenterView> updateDatacenter(
            @PathVariable String dcLocation,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceDatacenterUpsertRequest request) {
        return deviceService.updateDatacenter(dcLocation, idempotencyKey, request);
    }

    @DeleteMapping("/datacenters/{dcLocation}")
    @PreAuthorize("hasAuthority('device_e5_write')")
    public ApiResult<Map<String, Object>> deleteDatacenter(
            @PathVariable String dcLocation,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DatacenterOpsRequest request) {
        return deviceService.deleteDatacenter(dcLocation, idempotencyKey, request);
    }

    @PostMapping("/datacenters/{dcLocation}/pause")
    @PreAuthorize("hasAuthority('device_e5_datacenter_pause')")
    public ApiResult<Map<String, Object>> pauseDatacenter(
            @PathVariable String dcLocation,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DatacenterOpsRequest request) {
        return deviceService.pauseDatacenter(dcLocation, idempotencyKey, request);
    }

    @PostMapping("/datacenters/{dcLocation}/resume")
    @PreAuthorize("hasAuthority('device_e5_write')")
    public ApiResult<Map<String, Object>> resumeDatacenter(
            @PathVariable String dcLocation,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DatacenterOpsRequest request) {
        return deviceService.resumeDatacenter(dcLocation, idempotencyKey, request);
    }
}
