package ffdd.compute.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import ffdd.compute.mapper.ComputeReceiptMapper;
import ffdd.compute.mapper.ComputeTaskMapper;
import ffdd.compute.mapper.UserDeviceMapper;
import ffdd.compute.service.ComputeService;
import ffdd.compute.service.ComputeTaskCompletedEventFactory;
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
    private static final String TASK_COMPLETED = "COMPLETED";
    private static final String EARNING_PENDING = "PENDING";
    private static final String EARNING_SETTLED = "SETTLED";
    private static final String EARNING_FAILED = "FAILED";
    private static final String AGGREGATE_COMPUTE_RECEIPT = "COMPUTE_RECEIPT";

    private final UserDeviceMapper userDeviceMapper;
    private final ComputeTaskMapper taskMapper;
    private final ComputeReceiptMapper receiptMapper;
    private final EarningsClient earningsClient;
    private final EventOutboxService outboxService;
    private final ComputeTaskCompletedEventFactory eventFactory;
    private final boolean autoSettleEarnings;
    private final boolean taskCompletedOutboxEnabled;

    public ComputeServiceImpl(
            UserDeviceMapper userDeviceMapper,
            ComputeTaskMapper taskMapper,
            ComputeReceiptMapper receiptMapper,
            EarningsClient earningsClient,
            EventOutboxService outboxService,
            ComputeTaskCompletedEventFactory eventFactory,
            @Value("${nexion.compute.auto-settle-earnings:true}") boolean autoSettleEarnings,
            @Value("${nexion.compute.task-completed-outbox-enabled:true}") boolean taskCompletedOutboxEnabled) {
        this.userDeviceMapper = userDeviceMapper;
        this.taskMapper = taskMapper;
        this.receiptMapper = receiptMapper;
        this.earningsClient = earningsClient;
        this.outboxService = outboxService;
        this.eventFactory = eventFactory;
        this.autoSettleEarnings = autoSettleEarnings;
        this.taskCompletedOutboxEnabled = taskCompletedOutboxEnabled;
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
    @Transactional(rollbackFor = Exception.class)
    public ComputeReceipt createReceipt(ReceiptCreateRequest request) {
        UserDevice device = getDevice(request.getUserDeviceId());
        LocalDateTime now = LocalDateTime.now();
        String taskNo = nextBizNo("TASK");
        String receiptNo = nextBizNo("POC");

        ComputeTask task = new ComputeTask();
        task.setTaskNo(taskNo);
        task.setUserId(device.getUserId());
        task.setUserDeviceId(device.getId());
        task.setTaskType(request.getTaskType());
        task.setClientName(request.getClientName());
        task.setStatus(TASK_COMPLETED);
        task.setStartedAt(now);
        task.setCompletedAt(now);
        task.setIsDeleted(0);
        taskMapper.insert(task);

        ComputeReceipt receipt = new ComputeReceipt();
        receipt.setUserId(device.getUserId());
        receipt.setUserDeviceId(device.getId());
        receipt.setTaskNo(taskNo);
        receipt.setReceiptNo(receiptNo);
        receipt.setTaskType(request.getTaskType());
        receipt.setClientName(request.getClientName());
        receipt.setRewardUsdt(defaultDecimal(request.getRewardUsdt()));
        receipt.setRewardNex(defaultDecimal(request.getRewardNex()));
        receipt.setEarningStatus(EARNING_PENDING);
        receipt.setProofHash(proofHash(receiptNo, device.getInstanceNo(), now));
        receipt.setCompletedAt(now);
        receipt.setIsDeleted(0);
        receiptMapper.insert(receipt);
        publishTaskCompletedEvent(receipt);
        registerEarningSettlementAfterCommit(receipt);
        return receipt;
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

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
