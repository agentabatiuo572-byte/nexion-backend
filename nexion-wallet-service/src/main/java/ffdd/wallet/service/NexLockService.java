package ffdd.wallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.wallet.client.SystemConfigClient;
import ffdd.wallet.domain.NexLockOrder;
import ffdd.wallet.dto.CreateNexLockRequest;
import ffdd.wallet.dto.WalletOrderQueryRequest;
import ffdd.wallet.mapper.NexLockOrderMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class NexLockService {
    private static final BigDecimal FOUNDER_APY_BPS = new BigDecimal("25000");
    private static final int DEFAULT_TERM_MONTHS = 24;
    private static final BigDecimal DEFAULT_MIN_AMOUNT_NEX = new BigDecimal("1000");

    private final NexLockOrderMapper lockMapper;
    private final WalletService walletService;
    private final SystemConfigClient systemConfigClient;

    public NexLockService(NexLockOrderMapper lockMapper, WalletService walletService, SystemConfigClient systemConfigClient) {
        this.lockMapper = lockMapper;
        this.walletService = walletService;
        this.systemConfigClient = systemConfigClient;
    }

    public PageResult<NexLockOrder> page(WalletOrderQueryRequest request) {
        long pageNum = Math.max(1L, request.getPageNum() == null ? 1L : request.getPageNum());
        long pageSize = Math.min(100L, Math.max(1L, request.getPageSize() == null ? 10L : request.getPageSize()));
        LambdaQueryWrapper<NexLockOrder> wrapper = new LambdaQueryWrapper<NexLockOrder>()
                .eq(NexLockOrder::getIsDeleted, 0)
                .eq(request.getUserId() != null, NexLockOrder::getUserId, request.getUserId())
                .eq(StringUtils.hasText(request.getStatus()), NexLockOrder::getStatus, request.getStatus())
                .orderByDesc(NexLockOrder::getCreatedAt);
        Page<NexLockOrder> page = lockMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public NexLockOrder create(CreateNexLockRequest request) {
        if (request.getUserId() == null) {
            throw new BizException("User id is required");
        }
        NexLockConfig config = nexLockConfig();
        if (request.getAmountNex().compareTo(config.minAmountNex()) < 0) {
            throw new BizException("NEX lock amount is below minimum");
        }
        int termMonths = request.getTermMonths() == null || request.getTermMonths() <= 0 ? config.defaultTermMonths() : request.getTermMonths();
        String lockNo = "NEXLOCK-" + UUID.randomUUID();
        walletService.postDebit(debitRequest(request, lockNo));
        LocalDateTime now = LocalDateTime.now();
        NexLockOrder order = new NexLockOrder();
        order.setUserId(request.getUserId());
        order.setLockNo(lockNo);
        order.setAmountNex(request.getAmountNex());
        order.setApyBps(config.apyBps());
        order.setTermMonths(termMonths);
        order.setLockedAt(now);
        order.setUnlockAt(now.plusMonths(termMonths));
        order.setEstimatedRewardNex(request.getAmountNex()
                .multiply(config.apyBps())
                .multiply(BigDecimal.valueOf(termMonths))
                .divide(BigDecimal.valueOf(1200000), 6, RoundingMode.HALF_UP));
        order.setStatus("ACTIVE");
        order.setIsDeleted(0);
        lockMapper.insert(order);
        return order;
    }

    private NexLockConfig nexLockConfig() {
        try {
            ApiResult<Map<String, Object>> response = systemConfigClient.wallet();
            Map<String, Object> data = response == null ? null : response.getData();
            return new NexLockConfig(
                    configDecimal(data, "nex_lock.apy_bps", FOUNDER_APY_BPS),
                    configInt(data, "nex_lock.default_term_months", DEFAULT_TERM_MONTHS),
                    configDecimal(data, "nex_lock.min_amount_nex", DEFAULT_MIN_AMOUNT_NEX));
        } catch (RuntimeException ex) {
            return new NexLockConfig(FOUNDER_APY_BPS, DEFAULT_TERM_MONTHS, DEFAULT_MIN_AMOUNT_NEX);
        }
    }

    private BigDecimal configDecimal(Map<String, Object> data, String key, BigDecimal fallback) {
        if (data == null || !data.containsKey(key) || data.get(key) == null) {
            return fallback;
        }
        try {
            BigDecimal decimal = new BigDecimal(String.valueOf(data.get(key)));
            return decimal.compareTo(BigDecimal.ZERO) <= 0 ? fallback : decimal;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int configInt(Map<String, Object> data, String key, int fallback) {
        if (data == null || !data.containsKey(key) || data.get(key) == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(data.get(key)));
            return parsed < 1 ? fallback : parsed;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private ffdd.wallet.dto.PostWalletDebitRequest debitRequest(CreateNexLockRequest request, String lockNo) {
        ffdd.wallet.dto.PostWalletDebitRequest debit = new ffdd.wallet.dto.PostWalletDebitRequest();
        debit.setUserId(request.getUserId());
        debit.setBizNo(lockNo);
        debit.setBizType("NEX_LOCK");
        debit.setAsset("NEX");
        debit.setAmount(request.getAmountNex());
        debit.setRemark("NEX v2 lock");
        if (walletService.getOrCreateWallet(request.getUserId()).getNexAvailable().compareTo(request.getAmountNex()) < 0) {
            throw new BizException("Insufficient NEX balance");
        }
        return debit;
    }

    private record NexLockConfig(BigDecimal apyBps, int defaultTermMonths, BigDecimal minAmountNex) {
    }
}
