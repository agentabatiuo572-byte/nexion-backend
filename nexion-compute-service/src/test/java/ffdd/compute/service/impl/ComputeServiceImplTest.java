package ffdd.compute.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import ffdd.common.api.ApiResult;
import ffdd.common.exception.BizException;
import ffdd.common.outbox.EventOutboxService;
import ffdd.compute.client.EarningsClient;
import ffdd.compute.client.dto.EarningsReceiptSettleRequest;
import ffdd.compute.domain.ComputeReceipt;
import ffdd.compute.domain.ComputeTask;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.ComputeTaskCompletedPayload;
import ffdd.compute.dto.DeviceActivateRequest;
import ffdd.compute.dto.ReceiptCreateRequest;
import ffdd.compute.dto.TaskAckRequest;
import ffdd.compute.dto.TaskCompleteRequest;
import ffdd.compute.dto.TaskDispatchRequest;
import ffdd.compute.dto.TaskFailRequest;
import ffdd.compute.dto.TaskMaintenanceResult;
import ffdd.compute.dto.WorkerTaskLeaseRequest;
import ffdd.compute.dto.WorkerTaskLeaseResponse;
import ffdd.compute.mapper.ComputeReceiptMapper;
import ffdd.compute.mapper.ComputeTaskMapper;
import ffdd.compute.mapper.UserDeviceMapper;
import ffdd.compute.service.ComputeTaskCompletedEventFactory;
import ffdd.compute.service.DeviceFleetConfigService;
import ffdd.compute.service.DeviceStatusService;
import ffdd.compute.worker.ComputeOutboxRocketPublisher;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComputeServiceImplTest {
    private final UserDeviceMapper userDeviceMapper = mock(UserDeviceMapper.class);
    private final ComputeTaskMapper taskMapper = mock(ComputeTaskMapper.class);
    private final ComputeReceiptMapper receiptMapper = mock(ComputeReceiptMapper.class);
    private final EarningsClient earningsClient = mock(EarningsClient.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final DeviceStatusService deviceStatusService = mock(DeviceStatusService.class);
    private final DeviceFleetConfigService fleetConfigService = mock(DeviceFleetConfigService.class);

    @Test
    void createReceiptWritesComputeTaskCompletedOutboxAndKeepsSynchronousSettlement() {
        UserDevice device = new UserDevice();
        device.setId(7L);
        device.setUserId(10001L);
        device.setInstanceNo("UD-ORDER-1");
        device.setIsDeleted(0);
        when(userDeviceMapper.selectById(7L)).thenReturn(device);
        when(earningsClient.settleReceipt(any(EarningsReceiptSettleRequest.class)))
                .thenReturn(ApiResult.ok(Map.of("settled", true)));

        ComputeServiceImpl service = new ComputeServiceImpl(
                userDeviceMapper,
                taskMapper,
                receiptMapper,
                earningsClient,
                outboxService,
                new ComputeTaskCompletedEventFactory(),
                deviceStatusService,
                fleetConfigService,
                true,
                true,
                300,
                3,
                30);

        ReceiptCreateRequest request = new ReceiptCreateRequest();
        request.setUserDeviceId(7L);
        request.setTaskType("AI_INFERENCE");
        request.setClientName("worker-a");
        request.setRewardUsdt(new BigDecimal("0.018"));
        request.setRewardNex(new BigDecimal("3.2"));

        ComputeReceipt receipt = service.createReceipt(request);

        ArgumentCaptor<ComputeTaskCompletedPayload> payloadCaptor =
                ArgumentCaptor.forClass(ComputeTaskCompletedPayload.class);
        verify(outboxService).publish(
                eq("COMPUTE_RECEIPT"),
                eq(receipt.getReceiptNo()),
                eq(ComputeOutboxRocketPublisher.EVENT_COMPUTE_TASK_COMPLETED),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().getReceiptNo()).isEqualTo(receipt.getReceiptNo());
        assertThat(payloadCaptor.getValue().getRewardUsdt()).isEqualByComparingTo("0.018");
        assertThat(payloadCaptor.getValue().getRewardNex()).isEqualByComparingTo("3.2");
        verify(earningsClient).settleReceipt(any(EarningsReceiptSettleRequest.class));
        assertThat(receipt.getEarningStatus()).isEqualTo("SETTLED");
    }

    @Test
    void dispatchTaskClaimsOneOnlineDeviceAtomicallyAndCachesBusyState() {
        UserDevice device = device(7L, 10001L, "ONLINE");
        when(userDeviceMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any()))
                .thenReturn(List.of(device));
        when(userDeviceMapper.update(any(UserDevice.class), org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any()))
                .thenReturn(1);
        when(userDeviceMapper.selectById(7L)).thenReturn(device);

        ComputeServiceImpl service = newService(false, true);

        TaskDispatchRequest request = new TaskDispatchRequest();
        request.setTaskType("AI_INFERENCE");
        request.setClientName("scheduler-a");

        ComputeTask task = service.dispatchTask(request);

        assertThat(task.getTaskNo()).startsWith("TASK-");
        assertThat(task.getUserId()).isEqualTo(10001L);
        assertThat(task.getUserDeviceId()).isEqualTo(7L);
        assertThat(task.getTaskType()).isEqualTo("AI_INFERENCE");
        assertThat(task.getClientName()).isEqualTo("scheduler-a");
        assertThat(task.getStatus()).isEqualTo("RUNNING");
        assertThat(task.getStartedAt()).isNotNull();
        verify(taskMapper).insert(task);
        verify(deviceStatusService).writeTaskLifecycleState(
                eq(device), eq("BUSY"), eq(task.getTaskNo()), eq("scheduler-a"), any(LocalDateTime.class));
    }

    @Test
    void dispatchTaskFailsWhenNoOnlineDeviceCanBeClaimed() {
        UserDevice device = device(7L, 10001L, "ONLINE");
        when(userDeviceMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any()))
                .thenReturn(List.of(device));
        when(userDeviceMapper.update(any(UserDevice.class), org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any()))
                .thenReturn(0);

        ComputeServiceImpl service = newService(false, true);

        TaskDispatchRequest request = new TaskDispatchRequest();
        request.setTaskType("AI_INFERENCE");
        request.setClientName("scheduler-a");

        assertThatThrownBy(() -> service.dispatchTask(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("No available compute device");
        verify(taskMapper, never()).insert(any(ComputeTask.class));
    }

    @Test
    void activateDevicesCreatesInactiveInventoryWhenActiveSlotsAreFull() {
        when(userDeviceMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any()))
                .thenReturn(List.of());
        when(userDeviceMapper.selectCount(org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any())).thenReturn(1L);

        ComputeServiceImpl service = newService(false, true);
        when(fleetConfigService.maxActiveSlots()).thenReturn(1);

        DeviceActivateRequest request = new DeviceActivateRequest();
        request.setUserId(10001L);
        request.setSourceOrderNo("ORD-SLOTS");
        request.setProductId(1L);
        request.setProductCode("stellarbox-s1");
        request.setProductName("NexionBox S1");
        request.setDeviceType("NEXION_BOX");
        request.setGeneration(1);
        request.setGpuModel("4x RTX 4090");
        request.setVramTotalGb(96);
        request.setBasePowerW(new BigDecimal("1200"));
        request.setDcLocation("Singapore DC");
        request.setPriceUsdtSnapshot(new BigDecimal("1299"));
        request.setSourceChannel("ORDER");
        request.setQuantity(1);

        List<UserDevice> devices = service.activateDevices(request);

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).getStatus()).isEqualTo("INACTIVE");
        assertThat(devices.get(0).getActivatedAt()).isNull();
        assertThat(devices.get(0).getPurchasedAt()).isNotNull();
        verify(userDeviceMapper).insert(org.mockito.ArgumentMatchers.<UserDevice>argThat(inserted ->
                "INACTIVE".equals(inserted.getStatus())
                        && inserted.getActivatedAt() == null
                        && "stellarbox-s1".equals(inserted.getProductCode())
                        && Integer.valueOf(1).equals(inserted.getGeneration())
                        && "4x RTX 4090".equals(inserted.getGpuModel())
                        && Integer.valueOf(96).equals(inserted.getVramTotalGb())
                        && inserted.getBasePowerW().compareTo(new BigDecimal("1200")) == 0
                        && "Singapore DC".equals(inserted.getDcLocation())
                        && inserted.getPriceUsdtSnapshot().compareTo(new BigDecimal("1299")) == 0
                        && "OWNED".equals(inserted.getOwnershipStatus())
                        && "ORDER".equals(inserted.getSourceChannel())));
    }

    @Test
    void activateInventoryDeviceRejectsWhenConfiguredSlotsAreFull() {
        UserDevice inactive = device(7L, 10001L, "INACTIVE");
        inactive.setActivatedAt(null);
        when(userDeviceMapper.selectById(7L)).thenReturn(inactive);
        when(userDeviceMapper.selectCount(org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any())).thenReturn(1L);

        ComputeServiceImpl service = newService(false, true);
        when(fleetConfigService.maxActiveSlots()).thenReturn(1);

        assertThatThrownBy(() -> service.activateDevice(7L, 10001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Active device slots are full");
        verify(userDeviceMapper, never()).updateById(any(UserDevice.class));
    }

    @Test
    void leaseNextWorkerTaskDispatchesAndAcknowledgesNewTask() {
        UserDevice device = device(7L, 10001L, "ONLINE");
        when(taskMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any())).thenReturn(null);
        when(userDeviceMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any()))
                .thenReturn(List.of(device));
        when(userDeviceMapper.update(any(UserDevice.class), org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any()))
                .thenReturn(1);
        when(userDeviceMapper.selectById(7L)).thenReturn(device);

        ComputeServiceImpl service = newService(false, true);

        WorkerTaskLeaseRequest request = new WorkerTaskLeaseRequest();
        request.setPreferredDeviceId(7L);
        request.setTaskType("AI_INFERENCE");
        request.setClientName("worker-a");

        WorkerTaskLeaseResponse response = service.leaseNextWorkerTask(request);

        assertThat(response.getTaskNo()).startsWith("TASK-");
        assertThat(response.getUserDeviceId()).isEqualTo(7L);
        assertThat(response.getDeviceName()).isEqualTo("AI Node 7");
        assertThat(response.getStatus()).isEqualTo("RUNNING");
        assertThat(response.getWorkerAckAt()).isNotNull();
        assertThat(response.getLeaseExpiresAt()).isAfter(LocalDateTime.now());
        verify(taskMapper).insert(org.mockito.ArgumentMatchers.<ComputeTask>argThat(inserted ->
                "worker-a".equals(inserted.getClientName())
                        && inserted.getWorkerAckAt() != null
                        && inserted.getLeaseExpiresAt() != null));
        verify(deviceStatusService).writeTaskLifecycleState(
                eq(device), eq("BUSY"), eq(response.getTaskNo()), eq("worker-a"), any(LocalDateTime.class));
    }

    @Test
    void leaseNextWorkerTaskRenewsExistingRunningTaskWithoutCreatingDuplicate() {
        UserDevice device = device(7L, 10001L, "BUSY");
        ComputeTask task = runningTask("TASK-WORKER-1", 7L, 10001L);
        task.setClientName("worker-a");
        task.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(30));
        when(taskMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any())).thenReturn(task);
        when(taskMapper.update(any(ComputeTask.class), org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any()))
                .thenReturn(1);
        when(userDeviceMapper.selectById(7L)).thenReturn(device);

        ComputeServiceImpl service = newService(false, true);

        WorkerTaskLeaseRequest request = new WorkerTaskLeaseRequest();
        request.setPreferredDeviceId(7L);
        request.setTaskType("AI_INFERENCE");
        request.setClientName("worker-a");

        WorkerTaskLeaseResponse response = service.leaseNextWorkerTask(request);

        assertThat(response.getTaskNo()).isEqualTo("TASK-WORKER-1");
        assertThat(response.getWorkerAckAt()).isNotNull();
        assertThat(response.getLeaseExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(response.getDeviceStatus()).isEqualTo("BUSY");
        verify(taskMapper, never()).insert(any(ComputeTask.class));
        verify(taskMapper).update(
                org.mockito.ArgumentMatchers.<ComputeTask>argThat(updated ->
                        updated.getWorkerAckAt() != null && updated.getLeaseExpiresAt() != null),
                org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any());
    }

    @Test
    void completeRunningTaskCreatesReceiptPublishesOutboxSettlesAndReleasesDevice() {
        UserDevice device = device(7L, 10001L, "BUSY");
        ComputeTask task = runningTask("TASK-RUN-1", 7L, 10001L);
        when(taskMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any())).thenReturn(task);
        when(taskMapper.update(any(ComputeTask.class), org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any()))
                .thenReturn(1);
        when(userDeviceMapper.selectById(7L)).thenReturn(device);
        when(earningsClient.settleReceipt(any(EarningsReceiptSettleRequest.class)))
                .thenReturn(ApiResult.ok(Map.of("settled", true)));

        ComputeServiceImpl service = newService(true, true);

        TaskCompleteRequest request = new TaskCompleteRequest();
        request.setRewardUsdt(new BigDecimal("0.050000"));
        request.setRewardNex(new BigDecimal("8.000000"));

        ComputeReceipt receipt = service.completeTask("TASK-RUN-1", request);

        assertThat(receipt.getTaskNo()).isEqualTo("TASK-RUN-1");
        assertThat(receipt.getRewardUsdt()).isEqualByComparingTo("0.050000");
        assertThat(receipt.getRewardNex()).isEqualByComparingTo("8.000000");
        assertThat(receipt.getEarningStatus()).isEqualTo("SETTLED");
        verify(taskMapper).update(
                org.mockito.ArgumentMatchers.<ComputeTask>argThat(updated ->
                        "COMPLETED".equals(updated.getStatus()) && updated.getCompletedAt() != null),
                org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any());
        verify(receiptMapper).insert(receipt);
        verify(outboxService).publish(
                eq("COMPUTE_RECEIPT"),
                eq(receipt.getReceiptNo()),
                eq(ComputeOutboxRocketPublisher.EVENT_COMPUTE_TASK_COMPLETED),
                any(ComputeTaskCompletedPayload.class));
        verify(userDeviceMapper).updateById(org.mockito.ArgumentMatchers.<UserDevice>argThat(updated ->
                updated.getId().equals(7L) && "ONLINE".equals(updated.getStatus())));
        verify(deviceStatusService).writeTaskLifecycleState(
                eq(device), eq("ONLINE"), eq(null), eq("scheduler-a"), any(LocalDateTime.class));
    }

    @Test
    void completeRunningTaskWithPendingDeactivationReleasesDeviceAsInactive() {
        UserDevice device = device(7L, 10001L, "BUSY");
        device.setPendingDeactivate(1);
        ComputeTask task = runningTask("TASK-RUN-PENDING", 7L, 10001L);
        when(taskMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any())).thenReturn(task);
        when(taskMapper.update(any(ComputeTask.class), org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any()))
                .thenReturn(1);
        when(userDeviceMapper.selectById(7L)).thenReturn(device);
        when(earningsClient.settleReceipt(any(EarningsReceiptSettleRequest.class)))
                .thenReturn(ApiResult.ok(Map.of("settled", true)));

        ComputeServiceImpl service = newService(true, true);

        TaskCompleteRequest request = new TaskCompleteRequest();
        request.setRewardUsdt(new BigDecimal("0.050000"));
        request.setRewardNex(new BigDecimal("8.000000"));

        service.completeTask("TASK-RUN-PENDING", request);

        verify(userDeviceMapper).update(
                org.mockito.ArgumentMatchers.<UserDevice>argThat(updated ->
                        Long.valueOf(7L).equals(updated.getId()) && "INACTIVE".equals(updated.getStatus())),
                org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any());
        verify(deviceStatusService).writeTaskLifecycleState(
                eq(device), eq("INACTIVE"), eq(null), eq("scheduler-a"), any(LocalDateTime.class));
    }

    @Test
    void completeTaskReturnsExistingReceiptWhenAlreadyCompleted() {
        ComputeTask task = runningTask("TASK-DONE-1", 7L, 10001L);
        task.setStatus("COMPLETED");
        ComputeReceipt existing = new ComputeReceipt();
        existing.setId(99L);
        existing.setTaskNo("TASK-DONE-1");
        existing.setReceiptNo("POC-DONE-1");
        existing.setEarningStatus("SETTLED");
        when(taskMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any())).thenReturn(task);
        when(receiptMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ComputeReceipt>>any())).thenReturn(existing);

        ComputeServiceImpl service = newService(true, true);

        ComputeReceipt receipt = service.completeTask("TASK-DONE-1", new TaskCompleteRequest());

        assertThat(receipt).isSameAs(existing);
        verify(receiptMapper, never()).insert(any(ComputeReceipt.class));
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void completeTaskReturnsExistingReceiptWhenRunningTaskWasCompletedConcurrently() {
        ComputeTask running = runningTask("TASK-RACE-1", 7L, 10001L);
        ComputeTask completed = runningTask("TASK-RACE-1", 7L, 10001L);
        completed.setStatus("COMPLETED");
        ComputeReceipt existing = new ComputeReceipt();
        existing.setId(100L);
        existing.setTaskNo("TASK-RACE-1");
        existing.setReceiptNo("POC-RACE-1");
        existing.setEarningStatus("SETTLED");
        when(taskMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any()))
                .thenReturn(running, completed);
        when(taskMapper.update(any(ComputeTask.class), org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any()))
                .thenReturn(0);
        when(userDeviceMapper.selectById(7L)).thenReturn(device(7L, 10001L, "BUSY"));
        when(receiptMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ComputeReceipt>>any())).thenReturn(existing);

        ComputeServiceImpl service = newService(true, true);

        ComputeReceipt receipt = service.completeTask("TASK-RACE-1", new TaskCompleteRequest());

        assertThat(receipt).isSameAs(existing);
        verify(receiptMapper, never()).insert(any(ComputeReceipt.class));
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void failRunningTaskMarksTaskFailedAndReleasesDevice() {
        UserDevice device = device(7L, 10001L, "BUSY");
        ComputeTask task = runningTask("TASK-RUN-2", 7L, 10001L);
        when(taskMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any())).thenReturn(task);
        when(taskMapper.update(any(ComputeTask.class), org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any()))
                .thenReturn(1);
        when(userDeviceMapper.selectById(7L)).thenReturn(device);

        ComputeServiceImpl service = newService(false, true);

        TaskFailRequest request = new TaskFailRequest();
        request.setReason("worker timeout");

        ComputeTask failed = service.failTask("TASK-RUN-2", request);

        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getCompletedAt()).isNotNull();
        verify(taskMapper).update(
                org.mockito.ArgumentMatchers.<ComputeTask>argThat(updated ->
                        "FAILED".equals(updated.getStatus()) && updated.getCompletedAt() != null),
                org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any());
        verify(userDeviceMapper).updateById(org.mockito.ArgumentMatchers.<UserDevice>argThat(updated ->
                updated.getId().equals(7L) && "ONLINE".equals(updated.getStatus())));
        verify(deviceStatusService).writeTaskLifecycleState(
                eq(device), eq("ONLINE"), eq(null), eq("scheduler-a"), any(LocalDateTime.class));
    }

    @Test
    void ackRunningTaskRecordsWorkerAckAndExtendsLease() {
        ComputeTask task = runningTask("TASK-RUN-3", 7L, 10001L);
        task.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(30));
        when(taskMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any())).thenReturn(task);
        when(taskMapper.update(any(ComputeTask.class), org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any()))
                .thenReturn(1);

        ComputeServiceImpl service = newService(false, true);

        TaskAckRequest request = new TaskAckRequest();
        request.setClientName("scheduler-a");
        ComputeTask acked = service.ackTask("TASK-RUN-3", request);

        assertThat(acked.getWorkerAckAt()).isNotNull();
        assertThat(acked.getLeaseExpiresAt()).isAfter(LocalDateTime.now());
        verify(taskMapper).update(
                org.mockito.ArgumentMatchers.<ComputeTask>argThat(updated ->
                        updated.getWorkerAckAt() != null && updated.getLeaseExpiresAt() != null),
                org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any());
    }

    @Test
    void ackTaskRejectsClientMismatch() {
        ComputeTask task = runningTask("TASK-RUN-4", 7L, 10001L);
        task.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(30));
        when(taskMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any())).thenReturn(task);

        ComputeServiceImpl service = newService(false, true);

        TaskAckRequest request = new TaskAckRequest();
        request.setClientName("worker-b");

        assertThatThrownBy(() -> service.ackTask("TASK-RUN-4", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Task client mismatch");
        verify(taskMapper, never()).update(any(ComputeTask.class), org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any());
    }

    @Test
    void timeoutRunningTaskSchedulesRetryAndReleasesDevice() {
        UserDevice device = device(7L, 10001L, "BUSY");
        ComputeTask task = runningTask("TASK-TIMEOUT-1", 7L, 10001L);
        task.setAttemptCount(1);
        task.setMaxAttempts(3);
        task.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(taskMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any())).thenReturn(List.of(task));
        when(taskMapper.update(any(ComputeTask.class), org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any()))
                .thenReturn(1);
        when(userDeviceMapper.selectById(7L)).thenReturn(device);

        ComputeServiceImpl service = newService(false, true);

        TaskMaintenanceResult result = service.processTaskTimeouts(10);

        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getRetryScheduled()).isEqualTo(1);
        assertThat(result.getFailed()).isZero();
        verify(taskMapper).update(
                org.mockito.ArgumentMatchers.<ComputeTask>argThat(updated ->
                        "RETRYING".equals(updated.getStatus()) && updated.getNextRetryAt() != null),
                org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any());
        verify(userDeviceMapper).updateById(org.mockito.ArgumentMatchers.<UserDevice>argThat(updated ->
                updated.getId().equals(7L) && "ONLINE".equals(updated.getStatus())));
    }

    @Test
    void timeoutRunningTaskFailsAfterMaxAttemptsAndReleasesDevice() {
        UserDevice device = device(7L, 10001L, "BUSY");
        ComputeTask task = runningTask("TASK-TIMEOUT-2", 7L, 10001L);
        task.setAttemptCount(3);
        task.setMaxAttempts(3);
        task.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(taskMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any())).thenReturn(List.of(task));
        when(taskMapper.update(any(ComputeTask.class), org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any()))
                .thenReturn(1);
        when(userDeviceMapper.selectById(7L)).thenReturn(device);

        ComputeServiceImpl service = newService(false, true);

        TaskMaintenanceResult result = service.processTaskTimeouts(10);

        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getRetryScheduled()).isZero();
        assertThat(result.getFailed()).isEqualTo(1);
        verify(taskMapper).update(
                org.mockito.ArgumentMatchers.<ComputeTask>argThat(updated ->
                        "FAILED".equals(updated.getStatus()) && updated.getCompletedAt() != null),
                org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any());
        verify(userDeviceMapper).updateById(org.mockito.ArgumentMatchers.<UserDevice>argThat(updated ->
                updated.getId().equals(7L) && "ONLINE".equals(updated.getStatus())));
    }

    @Test
    void retryDueTaskClaimsOnlineDeviceAndDispatchesSameTask() {
        ComputeTask task = runningTask("TASK-RETRY-1", 7L, 10001L);
        task.setStatus("RETRYING");
        task.setAttemptCount(1);
        task.setMaxAttempts(3);
        task.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        UserDevice nextDevice = device(8L, 10001L, "ONLINE");
        when(taskMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any())).thenReturn(List.of(task));
        when(userDeviceMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any()))
                .thenReturn(List.of(nextDevice));
        when(userDeviceMapper.update(any(UserDevice.class), org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any()))
                .thenReturn(1);
        when(taskMapper.update(any(ComputeTask.class), org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any()))
                .thenReturn(1);

        ComputeServiceImpl service = newService(false, true);

        TaskMaintenanceResult result = service.retryDueTasks(10);

        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getRetried()).isEqualTo(1);
        verify(taskMapper).update(
                org.mockito.ArgumentMatchers.<ComputeTask>argThat(updated ->
                        "RUNNING".equals(updated.getStatus())
                                && Long.valueOf(8L).equals(updated.getUserDeviceId())
                                && Integer.valueOf(2).equals(updated.getAttemptCount())
                                && updated.getLeaseExpiresAt() != null),
                org.mockito.ArgumentMatchers.<Wrapper<ComputeTask>>any());
        verify(deviceStatusService).writeTaskLifecycleState(
                eq(nextDevice), eq("BUSY"), eq("TASK-RETRY-1"), eq("scheduler-a"), any(LocalDateTime.class));
    }

    private ComputeServiceImpl newService(boolean autoSettleEarnings, boolean taskCompletedOutboxEnabled) {
        when(fleetConfigService.maxActiveSlots()).thenReturn(6);
        return new ComputeServiceImpl(
                userDeviceMapper,
                taskMapper,
                receiptMapper,
                earningsClient,
                outboxService,
                new ComputeTaskCompletedEventFactory(),
                deviceStatusService,
                fleetConfigService,
                autoSettleEarnings,
                taskCompletedOutboxEnabled,
                300,
                3,
                30);
    }

    private UserDevice device(Long id, Long userId, String status) {
        UserDevice device = new UserDevice();
        device.setId(id);
        device.setUserId(userId);
        device.setInstanceNo("UD-ORDER-1");
        device.setName("AI Node " + id);
        device.setDeviceType("GPU");
        device.setStatus(status);
        device.setHashrate(new BigDecimal("120.000000"));
        device.setDailyUsdt(new BigDecimal("2.500000"));
        device.setDailyNex(new BigDecimal("50.000000"));
        device.setLastSeenAt(LocalDateTime.of(2026, 5, 24, 23, 45));
        device.setPurchasedAt(LocalDateTime.of(2026, 5, 1, 0, 0));
        if (!"INACTIVE".equals(status)) {
            device.setActivatedAt(LocalDateTime.of(2026, 5, 1, 0, 0));
        }
        device.setIsDeleted(0);
        return device;
    }

    private ComputeTask runningTask(String taskNo, Long userDeviceId, Long userId) {
        ComputeTask task = new ComputeTask();
        task.setId(11L);
        task.setTaskNo(taskNo);
        task.setUserDeviceId(userDeviceId);
        task.setUserId(userId);
        task.setTaskType("AI_INFERENCE");
        task.setClientName("scheduler-a");
        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.of(2026, 5, 24, 23, 50));
        task.setIsDeleted(0);
        return task;
    }
}
