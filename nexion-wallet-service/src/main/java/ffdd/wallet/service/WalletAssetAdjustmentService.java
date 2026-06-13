package ffdd.wallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.wallet.domain.WalletAssetAdjustment;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.dto.AssetAdjustmentQueryRequest;
import ffdd.wallet.dto.CreateAssetAdjustmentRequest;
import ffdd.wallet.dto.PostWalletCreditRequest;
import ffdd.wallet.dto.PostWalletDebitRequest;
import ffdd.wallet.dto.ReviewAssetAdjustmentRequest;
import ffdd.wallet.mapper.WalletAssetAdjustmentMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class WalletAssetAdjustmentService {
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final Set<String> ASSETS = Set.of("USDT", "NEX");
    private static final Set<String> DIRECTIONS = Set.of("CREDIT", "DEBIT");

    private final WalletAssetAdjustmentMapper adjustmentMapper;
    private final WalletService walletService;

    public PageResult<WalletAssetAdjustment> page(AssetAdjustmentQueryRequest request) {
        long current = Math.max(request.getCurrent(), 1);
        long size = Math.min(Math.max(request.getSize(), 1), 100);
        LambdaQueryWrapper<WalletAssetAdjustment> wrapper = new LambdaQueryWrapper<WalletAssetAdjustment>()
                .eq(WalletAssetAdjustment::getIsDeleted, 0)
                .eq(request.getUserId() != null, WalletAssetAdjustment::getUserId, request.getUserId())
                .eq(StringUtils.hasText(request.getStatus()), WalletAssetAdjustment::getStatus, normalizedUpper(request.getStatus()))
                .eq(StringUtils.hasText(request.getAsset()), WalletAssetAdjustment::getAsset, normalizedUpper(request.getAsset()))
                .orderByDesc(WalletAssetAdjustment::getCreatedAt)
                .orderByDesc(WalletAssetAdjustment::getId);
        Page<WalletAssetAdjustment> page = adjustmentMapper.selectPage(Page.of(current, size), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public WalletAssetAdjustment create(CreateAssetAdjustmentRequest request) {
        WalletAssetAdjustment row = new WalletAssetAdjustment();
        row.setAdjustmentNo("ADJ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT));
        row.setUserId(request.getUserId());
        row.setAsset(requireAllowed(normalizedUpper(request.getAsset()), ASSETS, "Asset must be USDT or NEX"));
        row.setDirection(requireAllowed(normalizedUpper(request.getDirection()), DIRECTIONS, "Direction must be CREDIT or DEBIT"));
        row.setAmount(requirePositive(request.getAmount()));
        row.setReasonCode(trimRequired(request.getReasonCode(), "Reason code is required"));
        row.setReason(trimRequired(request.getReason(), "Reason is required"));
        row.setMaker(trimRequired(request.getMaker(), "Maker is required"));
        row.setStatus(STATUS_PENDING);
        row.setIsDeleted(0);
        adjustmentMapper.insert(row);
        return row;
    }

    @Transactional
    public WalletAssetAdjustment review(String adjustmentNo, ReviewAssetAdjustmentRequest request) {
        WalletAssetAdjustment row = adjustmentMapper.selectOne(new LambdaQueryWrapper<WalletAssetAdjustment>()
                .eq(WalletAssetAdjustment::getAdjustmentNo, trimRequired(adjustmentNo, "Adjustment no is required"))
                .eq(WalletAssetAdjustment::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (row == null) {
            throw new BizException("Asset adjustment does not exist");
        }
        if (!STATUS_PENDING.equals(row.getStatus())) {
            throw new BizException("Asset adjustment has already been reviewed");
        }
        String checker = trimRequired(request.getChecker(), "Checker is required");
        if (checker.equalsIgnoreCase(row.getMaker())) {
            throw new BizException("Checker cannot be the maker");
        }
        String decision = normalizedUpper(request.getDecision());
        row.setChecker(checker);
        row.setReviewReason(trimRequired(request.getReviewReason(), "Review reason is required"));
        row.setReviewedAt(LocalDateTime.now());
        if (STATUS_APPROVED.equals(decision)) {
            WalletLedger ledger = postLedger(row);
            row.setStatus(STATUS_APPROVED);
            row.setLedgerId(ledger.getId());
        } else if (STATUS_REJECTED.equals(decision)) {
            row.setStatus(STATUS_REJECTED);
        } else {
            throw new BizException("Decision must be APPROVED or REJECTED");
        }
        adjustmentMapper.updateById(row);
        return row;
    }

    private WalletLedger postLedger(WalletAssetAdjustment row) {
        String bizNo = row.getAdjustmentNo();
        String remark = row.getReasonCode() + " · " + row.getReason();
        if ("CREDIT".equals(row.getDirection())) {
            PostWalletCreditRequest request = new PostWalletCreditRequest();
            request.setUserId(row.getUserId());
            request.setBizNo(bizNo);
            request.setBizType("ASSET_ADJUSTMENT");
            request.setAsset(row.getAsset());
            request.setAmount(row.getAmount());
            request.setRemark(remark);
            return walletService.postCredit(request);
        }
        PostWalletDebitRequest request = new PostWalletDebitRequest();
        request.setUserId(row.getUserId());
        request.setBizNo(bizNo);
        request.setBizType("ASSET_ADJUSTMENT");
        request.setAsset(row.getAsset());
        request.setAmount(row.getAmount());
        request.setRemark(remark);
        return walletService.postDebit(request);
    }

    private String normalizedUpper(String value) {
        return trimRequired(value, "Value is required").toUpperCase(Locale.ROOT);
    }

    private String trimRequired(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized)) {
            throw new BizException(message);
        }
        return normalized;
    }

    private String requireAllowed(String value, Set<String> allowed, String message) {
        if (!allowed.contains(value)) {
            throw new BizException(message);
        }
        return value;
    }

    private BigDecimal requirePositive(BigDecimal value) {
        if (value == null || value.compareTo(new BigDecimal("0.000001")) < 0) {
            throw new BizException("Amount must be positive");
        }
        return value;
    }
}
