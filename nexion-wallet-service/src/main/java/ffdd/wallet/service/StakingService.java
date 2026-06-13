package ffdd.wallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.wallet.domain.StakingPosition;
import ffdd.wallet.domain.StakingProduct;
import ffdd.wallet.dto.CreateStakingPositionRequest;
import ffdd.wallet.dto.PostWalletCreditRequest;
import ffdd.wallet.dto.PostWalletDebitRequest;
import ffdd.wallet.dto.StakingPositionQueryRequest;
import ffdd.wallet.dto.StakingProductQueryRequest;
import ffdd.wallet.mapper.StakingPositionMapper;
import ffdd.wallet.mapper.StakingProductMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class StakingService {
    private static final String ASSET_USDT = "USDT";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_MATURED = "MATURED";
    private static final String STATUS_CLAIMED = "CLAIMED";
    private static final String STATUS_EARLY_WITHDRAWN = "EARLY_WITHDRAWN";

    private final StakingProductMapper productMapper;
    private final StakingPositionMapper positionMapper;
    private final WalletService walletService;

    public StakingService(
            StakingProductMapper productMapper,
            StakingPositionMapper positionMapper,
            WalletService walletService) {
        this.productMapper = productMapper;
        this.positionMapper = positionMapper;
        this.walletService = walletService;
    }

    public PageResult<StakingProduct> pageProducts(StakingProductQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<StakingProduct> wrapper = new LambdaQueryWrapper<StakingProduct>()
                .eq(StakingProduct::getIsDeleted, 0)
                .eq(StringUtils.hasText(request.getStatus()), StakingProduct::getStatus, request.getStatus())
                .eq(StringUtils.hasText(request.getAsset()), StakingProduct::getAsset, request.getAsset())
                .orderByAsc(StakingProduct::getSortOrder)
                .orderByAsc(StakingProduct::getTermDays);
        Page<StakingProduct> page = productMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public StakingProduct saveProduct(StakingProduct product) {
        validateProduct(product);
        product.setAsset(ASSET_USDT);
        product.setStatus(StringUtils.hasText(product.getStatus()) ? product.getStatus().trim().toUpperCase() : STATUS_ACTIVE);
        product.setIsDeleted(product.getIsDeleted() == null ? 0 : product.getIsDeleted());
        if (product.getId() == null) {
            productMapper.insert(product);
        } else {
            productMapper.updateById(product);
        }
        return product;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteProduct(Long id) {
        if (id == null) {
            throw new BizException("Staking product id is required");
        }
        Long activeCount = positionMapper.selectCount(new LambdaQueryWrapper<StakingPosition>()
                .eq(StakingPosition::getProductId, id)
                .eq(StakingPosition::getIsDeleted, 0)
                .in(StakingPosition::getStatus, STATUS_ACTIVE, STATUS_MATURED));
        if (activeCount != null && activeCount > 0) {
            throw new BizException("Staking product has active positions");
        }
        StakingProduct patch = new StakingProduct();
        patch.setId(id);
        patch.setIsDeleted(1);
        productMapper.updateById(patch);
    }

    public PageResult<StakingPosition> pagePositions(StakingPositionQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<StakingPosition> wrapper = new LambdaQueryWrapper<StakingPosition>()
                .eq(StakingPosition::getIsDeleted, 0)
                .eq(request.getUserId() != null, StakingPosition::getUserId, request.getUserId())
                .eq(StringUtils.hasText(request.getStatus()), StakingPosition::getStatus, request.getStatus())
                .orderByDesc(StakingPosition::getCreatedAt);
        Page<StakingPosition> page = positionMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public StakingPosition createPosition(CreateStakingPositionRequest request) {
        if (request.getUserId() == null) {
            throw new BizException("User id is required");
        }
        StakingProduct product = requireProduct(request.getProductId());
        if (!STATUS_ACTIVE.equals(product.getStatus())) {
            throw new BizException("Staking product is not active");
        }
        if (request.getAmountUsdt().compareTo(product.getMinAmount()) < 0) {
            throw new BizException("Staking amount is below minimum");
        }
        if (walletService.getOrCreateWallet(request.getUserId()).getUsdtAvailable().compareTo(request.getAmountUsdt()) < 0) {
            throw new BizException("Insufficient USDT balance");
        }

        String positionNo = "STAKE-" + UUID.randomUUID();
        walletService.postDebit(debitRequest(request, positionNo));

        LocalDateTime now = LocalDateTime.now();
        StakingPosition position = new StakingPosition();
        position.setUserId(request.getUserId());
        position.setPositionNo(positionNo);
        position.setProductId(product.getId());
        position.setProductCode(product.getProductCode());
        position.setProductName(product.getName());
        position.setAmountUsdt(request.getAmountUsdt());
        position.setApyBps(product.getApyBps());
        position.setEarlyPenaltyBps(product.getEarlyPenaltyBps());
        position.setTermDays(product.getTermDays());
        position.setLockedAt(now);
        position.setUnlockAt(now.plusDays(product.getTermDays()));
        position.setEstimatedInterestUsdt(interest(request.getAmountUsdt(), product.getApyBps(), product.getTermDays()));
        position.setStatus(STATUS_ACTIVE);
        position.setIsDeleted(0);
        positionMapper.insert(position);
        return position;
    }

    @Transactional(rollbackFor = Exception.class)
    public StakingPosition claim(String positionNo) {
        StakingPosition position = requirePosition(positionNo);
        refreshMaturity(position);
        if (!STATUS_MATURED.equals(position.getStatus())) {
            throw new BizException("Staking position is not matured");
        }
        BigDecimal payout = position.getAmountUsdt().add(position.getEstimatedInterestUsdt());
        walletService.postCredit(creditRequest(position, position.getPositionNo() + "-CLAIM", payout, "Staking maturity claim"));
        StakingPosition patch = new StakingPosition();
        patch.setId(position.getId());
        patch.setStatus(STATUS_CLAIMED);
        patch.setClaimedAt(LocalDateTime.now());
        positionMapper.updateById(patch);
        position.setStatus(STATUS_CLAIMED);
        position.setClaimedAt(patch.getClaimedAt());
        return position;
    }

    @Transactional(rollbackFor = Exception.class)
    public StakingPosition earlyWithdraw(String positionNo) {
        StakingPosition position = requirePosition(positionNo);
        refreshMaturity(position);
        if (!STATUS_ACTIVE.equals(position.getStatus())) {
            throw new BizException("Only active staking positions can be early withdrawn");
        }
        BigDecimal penalty = position.getAmountUsdt()
                .multiply(position.getEarlyPenaltyBps())
                .divide(BigDecimal.valueOf(10000), 6, RoundingMode.HALF_UP);
        BigDecimal payout = position.getAmountUsdt().subtract(penalty).max(BigDecimal.ZERO);
        walletService.postCredit(creditRequest(position, position.getPositionNo() + "-EARLY", payout, "Staking early withdraw"));
        StakingPosition patch = new StakingPosition();
        patch.setId(position.getId());
        patch.setStatus(STATUS_EARLY_WITHDRAWN);
        patch.setEarlyWithdrawnAt(LocalDateTime.now());
        positionMapper.updateById(patch);
        position.setStatus(STATUS_EARLY_WITHDRAWN);
        position.setEarlyWithdrawnAt(patch.getEarlyWithdrawnAt());
        return position;
    }

    private void refreshMaturity(StakingPosition position) {
        if (STATUS_ACTIVE.equals(position.getStatus()) && position.getUnlockAt() != null && !position.getUnlockAt().isAfter(LocalDateTime.now())) {
            StakingPosition patch = new StakingPosition();
            patch.setId(position.getId());
            patch.setStatus(STATUS_MATURED);
            positionMapper.updateById(patch);
            position.setStatus(STATUS_MATURED);
        }
    }

    private StakingProduct requireProduct(Long id) {
        if (id == null) {
            throw new BizException("Staking product id is required");
        }
        StakingProduct product = productMapper.selectOne(new LambdaQueryWrapper<StakingProduct>()
                .eq(StakingProduct::getId, id)
                .eq(StakingProduct::getIsDeleted, 0));
        if (product == null) {
            throw new BizException("Staking product not found");
        }
        return product;
    }

    private StakingPosition requirePosition(String positionNo) {
        if (!StringUtils.hasText(positionNo)) {
            throw new BizException("Staking position no is required");
        }
        StakingPosition position = positionMapper.selectOne(new LambdaQueryWrapper<StakingPosition>()
                .eq(StakingPosition::getPositionNo, positionNo)
                .eq(StakingPosition::getIsDeleted, 0));
        if (position == null) {
            throw new BizException("Staking position not found");
        }
        return position;
    }

    private PostWalletDebitRequest debitRequest(CreateStakingPositionRequest request, String positionNo) {
        PostWalletDebitRequest debit = new PostWalletDebitRequest();
        debit.setUserId(request.getUserId());
        debit.setBizNo(positionNo);
        debit.setBizType("STAKING_LOCK");
        debit.setAsset(ASSET_USDT);
        debit.setAmount(request.getAmountUsdt());
        debit.setRemark("USDT staking lock");
        return debit;
    }

    private PostWalletCreditRequest creditRequest(StakingPosition position, String bizNo, BigDecimal amount, String remark) {
        PostWalletCreditRequest credit = new PostWalletCreditRequest();
        credit.setUserId(position.getUserId());
        credit.setBizNo(bizNo);
        credit.setBizType("STAKING_SETTLEMENT");
        credit.setAsset(ASSET_USDT);
        credit.setAmount(amount);
        credit.setRemark(remark);
        return credit;
    }

    private BigDecimal interest(BigDecimal amount, BigDecimal apyBps, int termDays) {
        return amount.multiply(apyBps)
                .multiply(BigDecimal.valueOf(termDays))
                .divide(BigDecimal.valueOf(3650000), 6, RoundingMode.HALF_UP);
    }

    private void validateProduct(StakingProduct product) {
        if (!StringUtils.hasText(product.getProductCode())) {
            throw new BizException("Staking product code is required");
        }
        if (!StringUtils.hasText(product.getName())) {
            throw new BizException("Staking product name is required");
        }
        if (product.getTermDays() == null || product.getTermDays() < 1) {
            throw new BizException("Staking term days must be positive");
        }
        if (product.getApyBps() == null || product.getApyBps().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("Staking APY must be positive");
        }
        if (product.getEarlyPenaltyBps() == null || product.getEarlyPenaltyBps().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("Early penalty must not be negative");
        }
        if (product.getMinAmount() == null || product.getMinAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("Minimum amount must be positive");
        }
        if (product.getSortOrder() == null) {
            product.setSortOrder(product.getTermDays());
        }
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
}
