package ffdd.compute.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.common.outbox.EventOutboxService;
import ffdd.compute.client.EarningsClient;
import ffdd.compute.client.dto.EarningsReceiptSettleRequest;
import ffdd.compute.domain.ComputeReceipt;
import ffdd.compute.domain.ComputeTask;
import ffdd.compute.dto.ComputeTaskCompletedPayload;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.DeviceActivateRequest;
import ffdd.compute.dto.DeviceQueryRequest;
import ffdd.compute.dto.ReceiptCreateRequest;
import ffdd.compute.dto.ReceiptQueryRequest;
import ffdd.compute.dto.TaskAckRequest;
import ffdd.compute.dto.TaskCompleteRequest;
import ffdd.compute.dto.TaskDispatchRequest;
import ffdd.compute.dto.TaskFailRequest;
import ffdd.compute.dto.TaskMaintenanceResult;
import ffdd.compute.dto.TaskQueryRequest;
import ffdd.compute.dto.WorkerTaskLeaseRequest;
import ffdd.compute.dto.WorkerTaskLeaseResponse;
import ffdd.compute.mapper.ComputeReceiptMapper;
import ffdd.compute.mapper.ComputeTaskMapper;
import ffdd.compute.mapper.UserDeviceMapper;
import ffdd.compute.service.ComputeService;
import ffdd.compute.service.ComputeTaskCompletedEventFactory;
import ffdd.compute.service.DeviceStatusService;
import ffdd.compute.worker.ComputeOutboxRocketPublisher;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class ComputeServiceImpl implements ComputeService {
    private static final Logger log = LoggerFactory.getLogger(ComputeServiceImpl.class);
    private static final String DEVICE_ONLINE = "ONLINE";
    private static final String DEVICE_BUSY = "BUSY";
    private static final String TASK_RUNNING = "RUNNING";
    private static final String TASK_RETRYING = "RETRYING";
    private static final String TASK_COMPLETED = "COMPLETED";
    private static final String TASK_FAILED = "FAILED";
    private static final String EARNING_PENDING = "PENDING";
    private static final String EARNING_SETTLED = "SETTLED";
    private static final String EARNING_FAILED = "FAILED";
    private static final String AGGREGATE_COMPUTE_RECEIPT = "COMPUTE_RECEIPT";
    private static final int DISPATCH_CANDIDATE_LIMIT = 50;

    private final UserDeviceMapper userDeviceMapper;
    private final ComputeTaskMapper taskMapper;
    private final ComputeReceiptMapper receiptMapper;
    private final EarningsClient earningsClient;
    private final EventOutboxService outboxService;
    private final ComputeTaskCompletedEventFactory eventFactory;
    private final DeviceStatusService deviceStatusService;
    private final boolean autoSettleEarnings;
    private final boolean taskCompletedOutboxEnabled;
    private final long defaultTaskLeaseSeconds;
    private final int defaultTaskMaxAttempts;
    private final long retryInitialBackoffSeconds;

    public ComputeServiceImpl(
            UserDeviceMapper userDeviceMapper,
            ComputeTaskMapper taskMapper,
            ComputeReceiptMapper receiptMapper,
            EarningsClient earningsClient,
            EventOutboxService outboxService,
            ComputeTaskCompletedEventFactory eventFactory,
            DeviceStatusService deviceStatusService,
            @Value("${nexion.compute.auto-settle-earnings:true}") boolean autoSettleEarnings,
            @Value("${nexion.compute.task-completed-outbox-enabled:true}") boolean taskCompletedOutboxEnabled,
            @Value("${nexion.compute.task.default-lease-seconds:300}") long defaultTaskLeaseSeconds,
            @Value("${nexion.compute.task.default-max-attempts:3}") int defaultTaskMaxAttempts,
            @Value("${nexion.compute.task.retry.initial-backoff-seconds:30}") long retryInitialBackoffSeconds) {
        this.userDeviceMapper = userDeviceMapper;
        this.taskMapper = taskMapper;
        this.receiptMapper = receiptMapper;
        this.earningsClient = earningsClient;
        this.outboxService = outboxService;
        this.eventFactory = eventFactory;
        this.deviceStatusService = deviceStatusService;
        this.autoSettleEarnings = autoSettleEarnings;
        this.taskCompletedOutboxEnabled = taskCompletedOutboxEnabled;
        this.defaultTaskLeaseSeconds = Math.max(1, defaultTaskLeaseSeconds);
        this.defaultTaskMaxAttempts = Math.max(1, Math.min(defaultTaskMaxAttempts, 20));
        this.retryInitialBackoffSeconds = Math.max(1, retryInitialBackoffSeconds);
    }

    @Override
    public PageResult<UserDevice> pageDevices(DeviceQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<UserDevice> wrapper = new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getIsDeleted, 0)
                .eq(request.getUserId() != null, UserDevice::getUserId, request.getUserId())
                .eq(StringUtils.hasText(request.getStatus()), UserDevice::getStatus, request.getStatus())
                .eq(StringUtils.hasText(request.getSourceOrderNo()), UserDevice::getSourceOrderNo, request.getSourceOrderNo())
                .orderByDesc(UserDevice::getCreatedAt);
        Page<UserDevice> page = userDeviceMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    public UserDevice getDevice(Long id) {
        UserDevice device = userDeviceMapper.selectById(id);
        if (device == null || Integer.valueOf(1).equals(device.getIsDeleted())) {
            throw new BizException("Device not found");
        }
        return device;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<UserDevice> activateDevices(DeviceActivateRequest request) {
        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        if (quantity < 1) {
            throw new BizException("Quantity must be greater than zero");
        }

        List<UserDevice> existing = userDeviceMapper.selectList(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getSourceOrderNo, request.getSourceOrderNo())
                .eq(UserDevice::getIsDeleted, 0)
                .orderByAsc(UserDevice::getId));
        if (existing.size() >= quantity) {
            return existing;
        }

        List<UserDevice> devices = new ArrayList<>(existing);
        LocalDateTime now = LocalDateTime.now();
        for (int i = existing.size(); i < quantity; i++) {
            UserDevice device = new UserDevice();
            device.setUserId(request.getUserId());
            device.setSourceOrderNo(request.getSourceOrderNo());
            device.setProductId(request.getProductId());
            device.setInstanceNo(nextInstanceNo(request.getSourceOrderNo(), i + 1));
            device.setName(quantity > 1 ? request.getProductName() + " #" + (i + 1) : request.getProductName());
            device.setDeviceType(request.getDeviceType());
            device.setStatus(DEVICE_ONLINE);
            device.setHashrate(defaultDecimal(request.getHashrate()));
            device.setDailyUsdt(defaultDecimal(request.getDailyUsdt()));
            device.setDailyNex(defaultDecimal(request.getDailyNex()));
            device.setLastSeenAt(now);
            device.setActivatedAt(now);
            device.setIsDeleted(0);
            userDeviceMapper.insert(device);
            devices.add(device);
        }
        return devices;
    }

    @Override
    public PageResult<ComputeTask> pageTasks(TaskQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<ComputeTask> wrapper = new LambdaQueryWrapper<ComputeTask>()
                .eq(ComputeTask::getIsDeleted, 0)
                .eq(request.getUserId() != null, ComputeTask::getUserId, request.getUserId())
                .eq(request.getUserDeviceId() != null, ComputeTask::getUserDeviceId, request.getUserDeviceId())
                .eq(StringUtils.hasText(request.getTaskType()), ComputeTask::getTaskType, request.getTaskType())
                .eq(StringUtils.hasText(request.getStatus()), ComputeTask::getStatus, request.getStatus())
                .orderByDesc(ComputeTask::getCreatedAt);
        Page<ComputeTask> page = taskMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ComputeTask dispatchTask(TaskDispatchRequest request) {
        return dispatchTaskInternal(request, LocalDateTime.now(), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkerTaskLeaseResponse leaseNextWorkerTask(WorkerTaskLeaseRequest request) {
        WorkerTaskLeaseRequest safeRequest = request == null ? new WorkerTaskLeaseRequest() : request;
        String taskType = requireText(safeRequest.getTaskType(), "Task type is required");
        String clientName = requireText(safeRequest.getClientName(), "Client name is required");
        LocalDateTime now = LocalDateTime.now();

        ComputeTask existing = findWorkerRunningTask(clientName, taskType);
        if (existing != null) {
            validateWorkerLeaseRequest(existing, safeRequest);
            if (existing.getLeaseExpiresAt() != null && !existing.getLeaseExpiresAt().isAfter(now)) {
                throw new BizException("Compute task lease expired");
            }
            ComputeTask renewed = renewRunningTaskLease(
                    existing, clientName, now, normalizeLeaseSeconds(safeRequest.getLeaseSeconds()));
            return WorkerTaskLeaseResponse.from(renewed, getDevice(renewed.getUserDeviceId()));
        }

        TaskDispatchRequest dispatchRequest = new TaskDispatchRequest();
        dispatchRequest.setUserId(safeRequest.getUserId());
        dispatchRequest.setPreferredDeviceId(safeRequest.getPreferredDeviceId());
        dispatchRequest.setTaskType(taskType);
        dispatchRequest.setClientName(clientName);
        dispatchRequest.setMaxAttempts(safeRequest.getMaxAttempts());
        dispatchRequest.setLeaseSeconds(safeRequest.getLeaseSeconds());
        ComputeTask task = dispatchTaskInternal(dispatchRequest, now, true);
        return WorkerTaskLeaseResponse.from(task, getDevice(task.getUserDeviceId()));
    }

    private ComputeTask dispatchTaskInternal(
            TaskDispatchRequest request, LocalDateTime now, boolean acknowledgeImmediately) {
        String taskType = requireText(request.getTaskType(), "Task type is required");
        String clientName = requireText(request.getClientName(), "Client name is required");
        long leaseSeconds = normalizeLeaseSeconds(request.getLeaseSeconds());

        for (UserDevice candidate : findDispatchCandidates(request)) {
            if (!claimDevice(candidate.getId(), now)) {
                continue;
            }
            candidate.setStatus(DEVICE_BUSY);
            candidate.setLastSeenAt(now);

            ComputeTask task = new ComputeTask();
            task.setTaskNo(nextBizNo("TASK"));
            task.setUserId(candidate.getUserId());
            task.setUserDeviceId(candidate.getId());
            task.setTaskType(taskType);
            task.setClientName(clientName);
            task.setStatus(TASK_RUNNING);
            task.setStartedAt(now);
            if (acknowledgeImmediately) {
                task.setWorkerAckAt(now);
            }
            task.setLeaseExpiresAt(now.plusSeconds(leaseSeconds));
            task.setAttemptCount(1);
            task.setMaxAttempts(normalizeMaxAttempts(request.getMaxAttempts()));
            task.setIsDeleted(0);
            taskMapper.insert(task);
            deviceStatusService.writeTaskLifecycleState(candidate, DEVICE_BUSY, task.getTaskNo(), clientName, now);
            return task;
        }
        throw new BizException("No available compute device");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ComputeTask ackTask(String taskNo, TaskAckRequest request) {
        TaskAckRequest safeRequest = request == null ? new TaskAckRequest() : request;
        String clientName = requireText(safeRequest.getClientName(), "Client name is required");
        ComputeTask task = requireTask(taskNo);
        if (!TASK_RUNNING.equals(task.getStatus())) {
            throw new BizException("Compute task is not running");
        }
        validateTaskClient(task, clientName);

        LocalDateTime now = LocalDateTime.now();
        if (task.getLeaseExpiresAt() != null && !task.getLeaseExpiresAt().isAfter(now)) {
            throw new BizException("Compute task lease expired");
        }
        return renewRunningTaskLease(task, clientName, now, normalizeLeaseSeconds(safeRequest.getLeaseSeconds()));
    }

    private ComputeTask renewRunningTaskLease(
            ComputeTask task, String clientName, LocalDateTime now, long leaseSeconds) {
        LocalDateTime leaseExpiresAt = now.plusSeconds(leaseSeconds);
        ComputeTask patch = new ComputeTask();
        patch.setWorkerAckAt(now);
        patch.setLeaseExpiresAt(leaseExpiresAt);
        int updated = taskMapper.update(patch, new LambdaUpdateWrapper<ComputeTask>()
                .eq(ComputeTask::getTaskNo, task.getTaskNo())
                .eq(ComputeTask::getClientName, clientName)
                .eq(ComputeTask::getStatus, TASK_RUNNING)
                .eq(ComputeTask::getIsDeleted, 0)
                .and(wrapper -> wrapper
                        .isNull(ComputeTask::getLeaseExpiresAt)
                        .or()
                        .gt(ComputeTask::getLeaseExpiresAt, now)));
        if (updated != 1) {
            throw new BizException("Compute task is not running");
        }
        task.setWorkerAckAt(now);
        task.setLeaseExpiresAt(leaseExpiresAt);
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ComputeReceipt completeTask(String taskNo, TaskCompleteRequest request) {
        ComputeTask task = requireTask(taskNo);
        validateTaskClient(task, request == null ? null : request.getClientName());
        if (TASK_COMPLETED.equals(task.getStatus())) {
            return requireReceiptByTaskNo(task.getTaskNo());
        }
        if (!TASK_RUNNING.equals(task.getStatus())) {
            throw new BizException("Compute task is not running");
        }

        UserDevice device = getDevice(task.getUserDeviceId());
        LocalDateTime now = LocalDateTime.now();
        if (!transitionRunningTask(task.getTaskNo(), TASK_COMPLETED, now)) {
            ComputeTask latest = requireTask(task.getTaskNo());
            if (TASK_COMPLETED.equals(latest.getStatus())) {
                return requireReceiptByTaskNo(task.getTaskNo());
            }
            throw new BizException("Compute task is not running");
        }

        task.setStatus(TASK_COMPLETED);
        task.setCompletedAt(now);
        ComputeReceipt receipt = createReceiptForTask(task, device, request, now);
        releaseDevice(device, task.getClientName(), now);
        return receipt;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ComputeTask failTask(String taskNo, TaskFailRequest request) {
        ComputeTask task = requireTask(taskNo);
        validateTaskClient(task, request == null ? null : request.getClientName());
        if (TASK_FAILED.equals(task.getStatus())) {
            return task;
        }
        if (TASK_COMPLETED.equals(task.getStatus())) {
            throw new BizException("Completed compute task cannot be failed");
        }
        if (!TASK_RUNNING.equals(task.getStatus())) {
            throw new BizException("Compute task is not running");
        }

        UserDevice device = getDevice(task.getUserDeviceId());
        LocalDateTime now = LocalDateTime.now();
        if (!transitionRunningTask(task.getTaskNo(), TASK_FAILED, now)) {
            ComputeTask latest = requireTask(task.getTaskNo());
            if (TASK_FAILED.equals(latest.getStatus())) {
                return latest;
            }
            if (TASK_COMPLETED.equals(latest.getStatus())) {
                throw new BizException("Completed compute task cannot be failed");
            }
            throw new BizException("Compute task is not running");
        }

        task.setStatus(TASK_FAILED);
        task.setCompletedAt(now);
        if (request != null && StringUtils.hasText(request.getReason())) {
            log.warn("Compute task failed taskNo={}, reason={}", taskNo, request.getReason());
        }
        releaseDevice(device, task.getClientName(), now);
        return task;
    }

    @Override
    public TaskMaintenanceResult processTaskTimeouts(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<ComputeTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<ComputeTask>()
                .eq(ComputeTask::getStatus, TASK_RUNNING)
                .eq(ComputeTask::getIsDeleted, 0)
                .isNotNull(ComputeTask::getLeaseExpiresAt)
                .le(ComputeTask::getLeaseExpiresAt, now)
                .orderByAsc(ComputeTask::getLeaseExpiresAt)
                .last("LIMIT " + normalizeLimit(limit)));
        TaskMaintenanceResult result = new TaskMaintenanceResult();
        result.setScanned(tasks.size());
        for (ComputeTask task : tasks) {
            try {
                handleTimedOutTask(task, now, result);
            } catch (RuntimeException ex) {
                result.incrementSkipped();
                log.warn("Failed to process timed out compute task taskNo={}, error={}",
                        task.getTaskNo(), ex.getMessage());
            }
        }
        return result;
    }

    @Override
    public TaskMaintenanceResult retryDueTasks(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<ComputeTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<ComputeTask>()
                .eq(ComputeTask::getStatus, TASK_RETRYING)
                .eq(ComputeTask::getIsDeleted, 0)
                .and(wrapper -> wrapper
                        .isNull(ComputeTask::getNextRetryAt)
                        .or()
                        .le(ComputeTask::getNextRetryAt, now))
                .orderByAsc(ComputeTask::getNextRetryAt)
                .last("LIMIT " + normalizeLimit(limit)));
        TaskMaintenanceResult result = new TaskMaintenanceResult();
        result.setScanned(tasks.size());
        for (ComputeTask task : tasks) {
            try {
                retryDueTask(task, now, result);
            } catch (RuntimeException ex) {
                result.incrementSkipped();
                log.warn("Failed to retry compute task taskNo={}, error={}", task.getTaskNo(), ex.getMessage());
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ComputeReceipt createReceipt(ReceiptCreateRequest request) {
        UserDevice device = getDevice(request.getUserDeviceId());
        LocalDateTime now = LocalDateTime.now();
        String taskNo = nextBizNo("TASK");

        ComputeTask task = new ComputeTask();
        task.setTaskNo(taskNo);
        task.setUserId(device.getUserId());
        task.setUserDeviceId(device.getId());
        task.setTaskType(request.getTaskType());
        task.setClientName(request.getClientName());
        task.setStatus(TASK_COMPLETED);
        task.setStartedAt(now);
        task.setCompletedAt(now);
        task.setAttemptCount(1);
        task.setMaxAttempts(1);
        task.setIsDeleted(0);
        taskMapper.insert(task);
        TaskCompleteRequest completeRequest = new TaskCompleteRequest();
        completeRequest.setRewardUsdt(request.getRewardUsdt());
        completeRequest.setRewardNex(request.getRewardNex());
        return createReceiptForTask(task, device, completeRequest, now);
    }

    @Override
    public ComputeReceipt settleReceiptEarnings(Long receiptId) {
        ComputeReceipt receipt = receiptMapper.selectById(receiptId);
        if (receipt == null || Integer.valueOf(1).equals(receipt.getIsDeleted())) {
            throw new BizException("Compute receipt not found");
        }
        if (EARNING_SETTLED.equals(receipt.getEarningStatus())) {
            return receipt;
        }
        updateEarningStatus(receipt.getId(), EARNING_PENDING);
        receipt.setEarningStatus(EARNING_PENDING);
        settleEarnings(receipt);
        return receiptMapper.selectById(receiptId);
    }

    @Override
    public PageResult<ComputeReceipt> pageReceipts(ReceiptQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<ComputeReceipt> wrapper = new LambdaQueryWrapper<ComputeReceipt>()
                .eq(ComputeReceipt::getIsDeleted, 0)
                .eq(request.getUserId() != null, ComputeReceipt::getUserId, request.getUserId())
                .eq(request.getUserDeviceId() != null, ComputeReceipt::getUserDeviceId, request.getUserDeviceId())
                .eq(StringUtils.hasText(request.getTaskType()), ComputeReceipt::getTaskType, request.getTaskType())
                .orderByDesc(ComputeReceipt::getCompletedAt);
        Page<ComputeReceipt> page = receiptMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    private long normalizePageNum(Long pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private long normalizePageSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, 100);
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private List<UserDevice> findDispatchCandidates(TaskDispatchRequest request) {
        if (request.getPreferredDeviceId() != null) {
            UserDevice device = getDevice(request.getPreferredDeviceId());
            if (request.getUserId() != null && !request.getUserId().equals(device.getUserId())) {
                throw new BizException("Preferred device does not belong to user");
            }
            if (!DEVICE_ONLINE.equals(device.getStatus())) {
                throw new BizException("Preferred device is not online");
            }
            return List.of(device);
        }
        return userDeviceMapper.selectList(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getIsDeleted, 0)
                .eq(UserDevice::getStatus, DEVICE_ONLINE)
                .eq(request.getUserId() != null, UserDevice::getUserId, request.getUserId())
                .orderByDesc(UserDevice::getLastSeenAt)
                .last("LIMIT " + DISPATCH_CANDIDATE_LIMIT));
    }

    private boolean claimDevice(Long deviceId, LocalDateTime now) {
        UserDevice patch = new UserDevice();
        patch.setStatus(DEVICE_BUSY);
        patch.setLastSeenAt(now);
        int updated = userDeviceMapper.update(patch, new LambdaUpdateWrapper<UserDevice>()
                .eq(UserDevice::getId, deviceId)
                .eq(UserDevice::getStatus, DEVICE_ONLINE)
                .eq(UserDevice::getIsDeleted, 0));
        return updated == 1;
    }

    private ComputeTask findWorkerRunningTask(String clientName, String taskType) {
        return taskMapper.selectOne(new LambdaQueryWrapper<ComputeTask>()
                .eq(ComputeTask::getClientName, clientName)
                .eq(ComputeTask::getTaskType, taskType)
                .eq(ComputeTask::getStatus, TASK_RUNNING)
                .eq(ComputeTask::getIsDeleted, 0)
                .orderByDesc(ComputeTask::getStartedAt)
                .last("LIMIT 1"));
    }

    private void validateWorkerLeaseRequest(ComputeTask task, WorkerTaskLeaseRequest request) {
        if (request.getUserId() != null && !request.getUserId().equals(task.getUserId())) {
            throw new BizException("Worker task user mismatch");
        }
        if (request.getPreferredDeviceId() != null
                && !request.getPreferredDeviceId().equals(task.getUserDeviceId())) {
            throw new BizException("Worker task device mismatch");
        }
    }

    private boolean transitionRunningTask(String taskNo, String targetStatus, LocalDateTime completedAt) {
        ComputeTask patch = new ComputeTask();
        patch.setStatus(targetStatus);
        patch.setCompletedAt(completedAt);
        int updated = taskMapper.update(patch, new LambdaUpdateWrapper<ComputeTask>()
                .eq(ComputeTask::getTaskNo, taskNo)
                .eq(ComputeTask::getStatus, TASK_RUNNING)
                .eq(ComputeTask::getIsDeleted, 0));
        return updated == 1;
    }

    private void releaseDevice(UserDevice device, String clientName, LocalDateTime now) {
        UserDevice patch = new UserDevice();
        patch.setId(device.getId());
        patch.setStatus(DEVICE_ONLINE);
        patch.setLastSeenAt(now);
        userDeviceMapper.updateById(patch);
        deviceStatusService.writeTaskLifecycleState(device, DEVICE_ONLINE, null, clientName, now);
    }

    private void handleTimedOutTask(ComputeTask task, LocalDateTime now, TaskMaintenanceResult result) {
        boolean finalFailure = attempts(task) >= maxAttempts(task);
        ComputeTask patch = new ComputeTask();
        patch.setStatus(finalFailure ? TASK_FAILED : TASK_RETRYING);
        patch.setLastError("Task lease expired");
        if (finalFailure) {
            patch.setCompletedAt(now);
        } else {
            patch.setNextRetryAt(now.plusSeconds(backoffSeconds(attempts(task))));
        }

        UpdateWrapper<ComputeTask> wrapper = new UpdateWrapper<ComputeTask>()
                .eq("task_no", task.getTaskNo())
                .eq("status", TASK_RUNNING)
                .eq("is_deleted", 0)
                .le("lease_expires_at", now);
        if (finalFailure) {
            wrapper.set("next_retry_at", null);
        } else {
            wrapper.set("completed_at", null);
        }
        int updated = taskMapper.update(patch, wrapper);
        if (updated != 1) {
            result.incrementSkipped();
            return;
        }

        releaseDeviceForMaintenance(task, now);
        if (finalFailure) {
            result.incrementFailed(task.getTaskNo());
        } else {
            result.incrementRetryScheduled(task.getTaskNo());
        }
    }

    private void retryDueTask(ComputeTask task, LocalDateTime now, TaskMaintenanceResult result) {
        if (attempts(task) >= maxAttempts(task)) {
            markRetryingTaskDead(task, now, result);
            return;
        }

        for (UserDevice candidate : findRetryCandidates(task)) {
            if (!claimDevice(candidate.getId(), now)) {
                continue;
            }
            candidate.setStatus(DEVICE_BUSY);
            candidate.setLastSeenAt(now);

            ComputeTask patch = new ComputeTask();
            patch.setUserDeviceId(candidate.getId());
            patch.setStatus(TASK_RUNNING);
            patch.setStartedAt(now);
            patch.setLeaseExpiresAt(now.plusSeconds(defaultTaskLeaseSeconds));
            patch.setAttemptCount(attempts(task) + 1);
            int updated = taskMapper.update(patch, new UpdateWrapper<ComputeTask>()
                    .set("worker_ack_at", null)
                    .set("next_retry_at", null)
                    .set("completed_at", null)
                    .set("last_error", null)
                    .eq("task_no", task.getTaskNo())
                    .eq("status", TASK_RETRYING)
                    .eq("is_deleted", 0)
                    .and(wrapper -> wrapper
                            .isNull("next_retry_at")
                            .or()
                            .le("next_retry_at", now)));
            if (updated != 1) {
                releaseDevice(candidate, task.getClientName(), now);
                result.incrementSkipped();
                return;
            }
            deviceStatusService.writeTaskLifecycleState(
                    candidate, DEVICE_BUSY, task.getTaskNo(), task.getClientName(), now);
            result.incrementRetried(task.getTaskNo());
            return;
        }
        result.incrementSkipped();
    }

    private List<UserDevice> findRetryCandidates(ComputeTask task) {
        return userDeviceMapper.selectList(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getIsDeleted, 0)
                .eq(UserDevice::getStatus, DEVICE_ONLINE)
                .eq(UserDevice::getUserId, task.getUserId())
                .orderByDesc(UserDevice::getLastSeenAt)
                .last("LIMIT " + DISPATCH_CANDIDATE_LIMIT));
    }

    private void markRetryingTaskDead(ComputeTask task, LocalDateTime now, TaskMaintenanceResult result) {
        ComputeTask patch = new ComputeTask();
        patch.setStatus(TASK_FAILED);
        patch.setCompletedAt(now);
        patch.setLastError("Task retry attempts exhausted");
        int updated = taskMapper.update(patch, new UpdateWrapper<ComputeTask>()
                .set("next_retry_at", null)
                .eq("task_no", task.getTaskNo())
                .eq("status", TASK_RETRYING)
                .eq("is_deleted", 0));
        if (updated == 1) {
            result.incrementFailed(task.getTaskNo());
        } else {
            result.incrementSkipped();
        }
    }

    private void releaseDeviceForMaintenance(ComputeTask task, LocalDateTime now) {
        try {
            UserDevice device = getDevice(task.getUserDeviceId());
            releaseDevice(device, task.getClientName(), now);
        } catch (RuntimeException ex) {
            log.warn("Failed to release timed out compute device taskNo={}, deviceId={}, error={}",
                    task.getTaskNo(), task.getUserDeviceId(), ex.getMessage());
        }
    }

    private ComputeTask requireTask(String taskNo) {
        String normalized = requireText(taskNo, "Task no is required");
        ComputeTask task = taskMapper.selectOne(new LambdaQueryWrapper<ComputeTask>()
                .eq(ComputeTask::getTaskNo, normalized)
                .eq(ComputeTask::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (task == null) {
            throw new BizException("Compute task not found");
        }
        return task;
    }

    private ComputeReceipt requireReceiptByTaskNo(String taskNo) {
        ComputeReceipt receipt = receiptMapper.selectOne(new LambdaQueryWrapper<ComputeReceipt>()
                .eq(ComputeReceipt::getTaskNo, taskNo)
                .eq(ComputeReceipt::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (receipt == null) {
            throw new BizException("Compute receipt not found");
        }
        return receipt;
    }

    private ComputeReceipt createReceiptForTask(
            ComputeTask task, UserDevice device, TaskCompleteRequest request, LocalDateTime completedAt) {
        TaskCompleteRequest safeRequest = request == null ? new TaskCompleteRequest() : request;
        String receiptNo = nextBizNo("POC");
        ComputeReceipt receipt = new ComputeReceipt();
        receipt.setUserId(task.getUserId());
        receipt.setUserDeviceId(task.getUserDeviceId());
        receipt.setTaskNo(task.getTaskNo());
        receipt.setReceiptNo(receiptNo);
        receipt.setTaskType(task.getTaskType());
        receipt.setClientName(task.getClientName());
        receipt.setRewardUsdt(defaultDecimal(safeRequest.getRewardUsdt()));
        receipt.setRewardNex(defaultDecimal(safeRequest.getRewardNex()));
        receipt.setEarningStatus(EARNING_PENDING);
        receipt.setProofHash(StringUtils.hasText(safeRequest.getProofHash())
                ? safeRequest.getProofHash().trim()
                : proofHash(receiptNo, device.getInstanceNo(), completedAt));
        receipt.setCompletedAt(completedAt);
        receipt.setIsDeleted(0);
        receiptMapper.insert(receipt);
        publishTaskCompletedEvent(receipt);
        registerEarningSettlementAfterCommit(receipt);
        return receipt;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(message);
        }
        return value.trim();
    }

    private void validateTaskClient(ComputeTask task, String clientName) {
        if (!StringUtils.hasText(clientName)) {
            return;
        }
        String normalized = clientName.trim();
        if (!normalized.equals(task.getClientName())) {
            throw new BizException("Task client mismatch");
        }
    }

    private int normalizeMaxAttempts(Integer requested) {
        if (requested == null) {
            return defaultTaskMaxAttempts;
        }
        return Math.max(1, Math.min(requested, 20));
    }

    private long normalizeLeaseSeconds(Long requested) {
        if (requested == null) {
            return defaultTaskLeaseSeconds;
        }
        return Math.max(1, Math.min(requested, 86400));
    }

    private int attempts(ComputeTask task) {
        return task.getAttemptCount() == null ? 1 : Math.max(1, task.getAttemptCount());
    }

    private int maxAttempts(ComputeTask task) {
        return task.getMaxAttempts() == null ? defaultTaskMaxAttempts : Math.max(1, task.getMaxAttempts());
    }

    private long backoffSeconds(int attempts) {
        int exponent = Math.min(Math.max(0, attempts - 1), 10);
        return retryInitialBackoffSeconds * (1L << exponent);
    }

    private String nextInstanceNo(String sourceOrderNo, int index) {
        String normalized = sourceOrderNo.replaceAll("[^A-Za-z0-9]", "");
        if (normalized.length() > 24) {
            normalized = normalized.substring(normalized.length() - 24);
        }
        return "UD-" + normalized + "-" + index;
    }

    private String nextBizNo(String prefix) {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int suffix = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return prefix + "-" + date + "-" + suffix;
    }

    private String proofHash(String receiptNo, String instanceNo, LocalDateTime completedAt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((receiptNo + "|" + instanceNo + "|" + completedAt)
                    .getBytes(StandardCharsets.UTF_8));
            return "0x" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private void registerEarningSettlementAfterCommit(ComputeReceipt receipt) {
        if (!autoSettleEarnings) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            settleEarnings(receipt);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                settleEarnings(receipt);
            }
        });
    }

    private void publishTaskCompletedEvent(ComputeReceipt receipt) {
        if (!taskCompletedOutboxEnabled) {
            return;
        }
        ComputeTaskCompletedPayload payload = eventFactory.fromReceipt(receipt);
        outboxService.publish(
                AGGREGATE_COMPUTE_RECEIPT,
                receipt.getReceiptNo(),
                ComputeOutboxRocketPublisher.EVENT_COMPUTE_TASK_COMPLETED,
                payload);
    }

    private void settleEarnings(ComputeReceipt receipt) {
        try {
            EarningsReceiptSettleRequest request = new EarningsReceiptSettleRequest();
            request.setUserId(receipt.getUserId());
            request.setUserDeviceId(receipt.getUserDeviceId());
            request.setReceiptNo(receipt.getReceiptNo());
            request.setRewardUsdt(receipt.getRewardUsdt());
            request.setRewardNex(receipt.getRewardNex());
            request.setCompletedAt(receipt.getCompletedAt());
            var response = earningsClient.settleReceipt(request);
            if (response != null && response.getCode() == 0) {
                updateEarningStatus(receipt.getId(), EARNING_SETTLED);
                receipt.setEarningStatus(EARNING_SETTLED);
                return;
            }
            updateEarningStatus(receipt.getId(), EARNING_FAILED);
            receipt.setEarningStatus(EARNING_FAILED);
        } catch (RuntimeException ex) {
            updateEarningStatus(receipt.getId(), EARNING_FAILED);
            receipt.setEarningStatus(EARNING_FAILED);
            log.warn("Failed to settle earnings for compute receipt {}; it remains retryable",
                    receipt.getReceiptNo(), ex);
        }
    }

    private void updateEarningStatus(Long receiptId, String status) {
        ComputeReceipt patch = new ComputeReceipt();
        patch.setId(receiptId);
        patch.setEarningStatus(status);
        receiptMapper.updateById(patch);
    }
}
