package ffdd.wallet.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.wallet.domain.EarningEvent;
import ffdd.wallet.domain.UserWallet;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.dto.LedgerQueryRequest;
import ffdd.wallet.dto.PostEarningRequest;
import ffdd.wallet.dto.PostEarningsResponse;
import ffdd.wallet.dto.PostPendingEarningsRequest;
import ffdd.wallet.mapper.EarningEventMapper;
import ffdd.wallet.mapper.UserWalletMapper;
import ffdd.wallet.mapper.WalletLedgerMapper;
import ffdd.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class WalletServiceImpl implements WalletService {
    private static final String ASSET_USDT = "USDT";
    private static final String ASSET_NEX = "NEX";
    private static final String DIRECTION_IN = "IN";
    private static final String BIZ_TYPE_EARNING = "EARNING";
    private static final String LEDGER_SUCCESS = "SUCCESS";
    private static final String EARNING_PENDING_WALLET = "PENDING_WALLET";
    private static final String EARNING_POSTED = "POSTED";

    private final UserWalletMapper walletMapper;
    private final WalletLedgerMapper ledgerMapper;
    private final EarningEventMapper earningEventMapper;

    public WalletServiceImpl(
            UserWalletMapper walletMapper,
            WalletLedgerMapper ledgerMapper,
            EarningEventMapper earningEventMapper) {
        this.walletMapper = walletMapper;
        this.ledgerMapper = ledgerMapper;
        this.earningEventMapper = earningEventMapper;
    }

    @Override
    public UserWallet getOrCreateWallet(Long userId) {
        UserWallet wallet = findWallet(userId);
        if (wallet != null) {
            return wallet;
        }

        UserWallet created = new UserWallet();
        created.setUserId(userId);
        created.setUsdtAvailable(BigDecimal.ZERO);
        created.setNexAvailable(BigDecimal.ZERO);
        created.setPendingWithdraw(BigDecimal.ZERO);
        created.setLifetimeEarned(BigDecimal.ZERO);
        created.setVersion(0L);
        created.setIsDeleted(0);
        try {
            walletMapper.insert(created);
            return created;
        } catch (DuplicateKeyException ex) {
            return findWallet(userId);
        }
    }

    @Override
    public PageResult<WalletLedger> pageLedgers(LedgerQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<WalletLedger> wrapper = new LambdaQueryWrapper<WalletLedger>()
                .eq(WalletLedger::getIsDeleted, 0)
                .eq(request.getUserId() != null, WalletLedger::getUserId, request.getUserId())
                .eq(StringUtils.hasText(request.getBizNo()), WalletLedger::getBizNo, request.getBizNo())
                .eq(StringUtils.hasText(request.getAsset()), WalletLedger::getAsset, request.getAsset())
                .eq(StringUtils.hasText(request.getDirection()), WalletLedger::getDirection, request.getDirection())
                .eq(StringUtils.hasText(request.getStatus()), WalletLedger::getStatus, request.getStatus())
                .orderByDesc(WalletLedger::getCreatedAt);
        Page<WalletLedger> page = ledgerMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WalletLedger postEarning(PostEarningRequest request) {
        EarningEvent event = earningEventMapper.selectOne(new LambdaQueryWrapper<EarningEvent>()
                .eq(EarningEvent::getEventNo, request.getEventNo())
                .eq(EarningEvent::getIsDeleted, 0));
        if (event == null) {
            throw new BizException("Earning event not found");
        }
        if (!EARNING_PENDING_WALLET.equals(event.getStatus()) && !EARNING_POSTED.equals(event.getStatus())) {
            throw new BizException("Earning event is not ready for wallet posting");
        }
        return postEarningEvent(event);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PostEarningsResponse postPendingEarnings(PostPendingEarningsRequest request) {
        int limit = normalizeLimit(request.getLimit());
        List<EarningEvent> events = earningEventMapper.selectList(new LambdaQueryWrapper<EarningEvent>()
                .eq(EarningEvent::getStatus, EARNING_PENDING_WALLET)
                .eq(EarningEvent::getIsDeleted, 0)
                .orderByAsc(EarningEvent::getCreatedAt)
                .last("LIMIT " + limit));
        List<WalletLedger> ledgers = new ArrayList<>();
        for (EarningEvent event : events) {
            ledgers.add(postEarningEvent(event));
        }
        return new PostEarningsResponse(events.size(), ledgers.size(), ledgers);
    }

    private WalletLedger postEarningEvent(EarningEvent event) {
        validateAsset(event.getAsset());
        WalletLedger existing = findLedger(event.getEventNo(), event.getAsset(), DIRECTION_IN);
        if (existing != null) {
            markEventPosted(event);
            return existing;
        }

        getOrCreateWallet(event.getUserId());
        WalletLedger ledger = createLedger(event);
        try {
            ledgerMapper.insert(ledger);
        } catch (DuplicateKeyException ex) {
            WalletLedger duplicate = findLedgerAny(event.getEventNo(), event.getAsset(), DIRECTION_IN);
            if (duplicate == null || Integer.valueOf(1).equals(duplicate.getIsDeleted())) {
                throw new BizException("Duplicate wallet ledger exists in an invalid state");
            }
            markEventPosted(event);
            return duplicate;
        }

        UserWallet wallet = addBalance(event);
        WalletLedger patch = new WalletLedger();
        patch.setId(ledger.getId());
        patch.setBalanceAfter(balanceAfter(wallet, event.getAsset()));
        ledgerMapper.updateById(patch);
        ledger.setBalanceAfter(patch.getBalanceAfter());

        markEventPosted(event);
        return ledger;
    }

    private WalletLedger createLedger(EarningEvent event) {
        WalletLedger ledger = new WalletLedger();
        ledger.setUserId(event.getUserId());
        ledger.setBizNo(event.getEventNo());
        ledger.setBizType(BIZ_TYPE_EARNING);
        ledger.setAsset(event.getAsset());
        ledger.setDirection(DIRECTION_IN);
        ledger.setAmount(event.getAmount());
        ledger.setBalanceAfter(BigDecimal.ZERO);
        ledger.setStatus(LEDGER_SUCCESS);
        ledger.setRemark("Earning receipt " + event.getReceiptNo());
        ledger.setIsDeleted(0);
        return ledger;
    }

    private UserWallet addBalance(EarningEvent event) {
        String amount = event.getAmount().toPlainString();
        LambdaUpdateWrapper<UserWallet> wrapper = new LambdaUpdateWrapper<UserWallet>()
                .eq(UserWallet::getUserId, event.getUserId())
                .eq(UserWallet::getIsDeleted, 0)
                .setSql(assetColumn(event.getAsset()) + " = " + assetColumn(event.getAsset()) + " + " + amount)
                .setSql("version = version + 1");
        if (ASSET_USDT.equals(event.getAsset())) {
            wrapper.setSql("lifetime_earned = lifetime_earned + " + amount);
        }
        int updated = walletMapper.update(null, wrapper);
        if (updated == 0) {
            throw new BizException("Wallet update failed");
        }
        return findWallet(event.getUserId());
    }

    private void markEventPosted(EarningEvent event) {
        if (EARNING_POSTED.equals(event.getStatus())) {
            return;
        }
        EarningEvent patch = new EarningEvent();
        patch.setId(event.getId());
        patch.setStatus(EARNING_POSTED);
        patch.setWalletPostedAt(LocalDateTime.now());
        earningEventMapper.updateById(patch);
        event.setStatus(EARNING_POSTED);
        event.setWalletPostedAt(patch.getWalletPostedAt());
    }

    private UserWallet findWallet(Long userId) {
        return walletMapper.selectOne(new LambdaQueryWrapper<UserWallet>()
                .eq(UserWallet::getUserId, userId)
                .eq(UserWallet::getIsDeleted, 0));
    }

    private WalletLedger findLedger(String bizNo, String asset, String direction) {
        return ledgerMapper.selectOne(new LambdaQueryWrapper<WalletLedger>()
                .eq(WalletLedger::getBizNo, bizNo)
                .eq(WalletLedger::getAsset, asset)
                .eq(WalletLedger::getDirection, direction)
                .eq(WalletLedger::getIsDeleted, 0));
    }

    private WalletLedger findLedgerAny(String bizNo, String asset, String direction) {
        return ledgerMapper.selectOne(new LambdaQueryWrapper<WalletLedger>()
                .eq(WalletLedger::getBizNo, bizNo)
                .eq(WalletLedger::getAsset, asset)
                .eq(WalletLedger::getDirection, direction));
    }

    private void validateAsset(String asset) {
        if (!ASSET_USDT.equals(asset) && !ASSET_NEX.equals(asset)) {
            throw new BizException("Unsupported asset: " + asset);
        }
    }

    private String assetColumn(String asset) {
        if (ASSET_USDT.equals(asset)) {
            return "usdt_available";
        }
        if (ASSET_NEX.equals(asset)) {
            return "nex_available";
        }
        throw new BizException("Unsupported asset: " + asset);
    }

    private BigDecimal balanceAfter(UserWallet wallet, String asset) {
        return ASSET_USDT.equals(asset) ? wallet.getUsdtAvailable() : wallet.getNexAvailable();
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

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return 100;
        }
        return Math.min(limit, 500);
    }
}
