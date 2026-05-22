package ffdd.earnings.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.earnings.client.WalletClient;
import ffdd.earnings.client.dto.WalletPostEarningRequest;
import ffdd.earnings.domain.EarningEvent;
import ffdd.earnings.domain.EarningSummary;
import ffdd.earnings.dto.EarningEventQueryRequest;
import ffdd.earnings.dto.EarningSummaryQueryRequest;
import ffdd.earnings.dto.ReceiptSettleRequest;
import ffdd.earnings.dto.ReceiptSettleResponse;
import ffdd.earnings.mapper.EarningEventMapper;
import ffdd.earnings.mapper.EarningSummaryMapper;
import ffdd.earnings.service.EarningsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EarningsServiceImpl implements EarningsService {
    private static final Logger log = LoggerFactory.getLogger(EarningsServiceImpl.class);
    private static final String ASSET_USDT = "USDT";
    private static final String ASSET_NEX = "NEX";
    private static final String STATUS_PENDING_WALLET = "PENDING_WALLET";

    private final EarningEventMapper eventMapper;
    private final EarningSummaryMapper summaryMapper;
    private final WalletClient walletClient;
    private final boolean autoPostWallet;

    public EarningsServiceImpl(
            EarningEventMapper eventMapper,
            EarningSummaryMapper summaryMapper,
            WalletClient walletClient,
            @Value("${nexion.earnings.auto-post-wallet:true}") boolean autoPostWallet) {
        this.eventMapper = eventMapper;
        this.summaryMapper = summaryMapper;
        this.walletClient = walletClient;
        this.autoPostWallet = autoPostWallet;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReceiptSettleResponse settleReceipt(ReceiptSettleRequest request) {
        LocalDate summaryDate = resolveSummaryDate(request.getCompletedAt());
        List<EarningEvent> events = new ArrayList<>();
        BigDecimal newUsdtAmount = BigDecimal.ZERO;
        BigDecimal newNexAmount = BigDecimal.ZERO;

        EventInsertResult usdtResult = insertEventIfNeeded(request, ASSET_USDT, defaultDecimal(request.getRewardUsdt()));
        if (usdtResult.event() != null) {
            events.add(usdtResult.event());
        }
        if (usdtResult.inserted()) {
            newUsdtAmount = usdtResult.event().getAmount();
        }

        EventInsertResult nexResult = insertEventIfNeeded(request, ASSET_NEX, defaultDecimal(request.getRewardNex()));
        if (nexResult.event() != null) {
            events.add(nexResult.event());
        }
        if (nexResult.inserted()) {
            newNexAmount = nexResult.event().getAmount();
        }

        EarningSummary summary = upsertSummary(request.getUserId(), summaryDate, newUsdtAmount, newNexAmount);
        registerWalletPostingAfterCommit(events);
        return new ReceiptSettleResponse(events, summary);
    }

    @Override
    public PageResult<EarningEvent> pageEvents(EarningEventQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<EarningEvent> wrapper = new LambdaQueryWrapper<EarningEvent>()
                .eq(EarningEvent::getIsDeleted, 0)
                .eq(request.getUserId() != null, EarningEvent::getUserId, request.getUserId())
                .eq(request.getUserDeviceId() != null, EarningEvent::getUserDeviceId, request.getUserDeviceId())
                .eq(StringUtils.hasText(request.getReceiptNo()), EarningEvent::getReceiptNo, request.getReceiptNo())
                .eq(StringUtils.hasText(request.getAsset()), EarningEvent::getAsset, request.getAsset())
                .eq(StringUtils.hasText(request.getStatus()), EarningEvent::getStatus, request.getStatus())
                .orderByDesc(EarningEvent::getCreatedAt);
        Page<EarningEvent> page = eventMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    public PageResult<EarningSummary> pageSummaries(EarningSummaryQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<EarningSummary> wrapper = new LambdaQueryWrapper<EarningSummary>()
                .eq(EarningSummary::getIsDeleted, 0)
                .eq(request.getUserId() != null, EarningSummary::getUserId, request.getUserId())
                .ge(request.getStartDate() != null, EarningSummary::getSummaryDate, request.getStartDate())
                .le(request.getEndDate() != null, EarningSummary::getSummaryDate, request.getEndDate())
                .orderByDesc(EarningSummary::getSummaryDate);
        Page<EarningSummary> page = summaryMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    private EventInsertResult insertEventIfNeeded(ReceiptSettleRequest request, String asset, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return new EventInsertResult(null, false);
        }

        EarningEvent existing = findEvent(request.getReceiptNo(), asset);
        if (existing != null) {
            return new EventInsertResult(existing, false);
        }

        EarningEvent event = new EarningEvent();
        event.setEventNo(nextEventNo(request.getReceiptNo(), asset));
        event.setUserId(request.getUserId());
        event.setUserDeviceId(request.getUserDeviceId());
        event.setReceiptNo(request.getReceiptNo());
        event.setAsset(asset);
        event.setAmount(amount);
        event.setStatus(STATUS_PENDING_WALLET);
        event.setIsDeleted(0);

        try {
            eventMapper.insert(event);
            return new EventInsertResult(event, true);
        } catch (DuplicateKeyException ex) {
            return new EventInsertResult(findEvent(request.getReceiptNo(), asset), false);
        }
    }

    private EarningEvent findEvent(String receiptNo, String asset) {
        return eventMapper.selectOne(new LambdaQueryWrapper<EarningEvent>()
                .eq(EarningEvent::getReceiptNo, receiptNo)
                .eq(EarningEvent::getAsset, asset)
                .eq(EarningEvent::getIsDeleted, 0));
    }

    private EarningSummary upsertSummary(Long userId, LocalDate summaryDate, BigDecimal usdtAmount, BigDecimal nexAmount) {
        EarningSummary existing = summaryMapper.selectOne(new LambdaQueryWrapper<EarningSummary>()
                .eq(EarningSummary::getUserId, userId)
                .eq(EarningSummary::getSummaryDate, summaryDate)
                .eq(EarningSummary::getIsDeleted, 0));
        if (existing == null) {
            EarningSummary summary = new EarningSummary();
            summary.setUserId(userId);
            summary.setSummaryDate(summaryDate);
            summary.setUsdtAmount(usdtAmount);
            summary.setNexAmount(nexAmount);
            summary.setIsDeleted(0);
            try {
                summaryMapper.insert(summary);
                return summary;
            } catch (DuplicateKeyException ignored) {
                existing = summaryMapper.selectOne(new LambdaQueryWrapper<EarningSummary>()
                        .eq(EarningSummary::getUserId, userId)
                        .eq(EarningSummary::getSummaryDate, summaryDate)
                        .eq(EarningSummary::getIsDeleted, 0));
            }
        }

        if (existing != null && (usdtAmount.compareTo(BigDecimal.ZERO) > 0 || nexAmount.compareTo(BigDecimal.ZERO) > 0)) {
            summaryMapper.update(null, new LambdaUpdateWrapper<EarningSummary>()
                    .eq(EarningSummary::getId, existing.getId())
                    .setSql("usdt_amount = usdt_amount + " + usdtAmount)
                    .setSql("nex_amount = nex_amount + " + nexAmount));
            return summaryMapper.selectById(existing.getId());
        }
        return existing;
    }

    private LocalDate resolveSummaryDate(LocalDateTime completedAt) {
        return completedAt == null ? LocalDate.now() : completedAt.toLocalDate();
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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

    private String nextEventNo(String receiptNo, String asset) {
        String normalized = receiptNo.replaceAll("[^A-Za-z0-9]", "");
        if (normalized.length() > 72) {
            normalized = normalized.substring(normalized.length() - 72);
        }
        return "EARN-" + normalized + "-" + asset;
    }

    private void registerWalletPostingAfterCommit(List<EarningEvent> events) {
        if (!autoPostWallet) {
            return;
        }
        List<String> eventNos = events.stream()
                .filter(event -> STATUS_PENDING_WALLET.equals(event.getStatus()))
                .map(EarningEvent::getEventNo)
                .toList();
        if (eventNos.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            postWalletEvents(eventNos);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                postWalletEvents(eventNos);
            }
        });
    }

    private void postWalletEvents(List<String> eventNos) {
        for (String eventNo : eventNos) {
            try {
                walletClient.postEarning(new WalletPostEarningRequest(eventNo));
            } catch (RuntimeException ex) {
                log.warn("Failed to post earning event {} to wallet; it remains retryable", eventNo, ex);
            }
        }
    }

    private record EventInsertResult(EarningEvent event, boolean inserted) {
    }
}
