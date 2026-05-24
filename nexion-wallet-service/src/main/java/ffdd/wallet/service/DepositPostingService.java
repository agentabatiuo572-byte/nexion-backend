package ffdd.wallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.wallet.chain.DepositChainConfirmation;
import ffdd.wallet.chain.DepositChainProvider;
import ffdd.wallet.domain.DepositOrder;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.dto.ConfirmDepositRequest;
import ffdd.wallet.dto.PostWalletCreditRequest;
import ffdd.wallet.mapper.DepositOrderMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DepositPostingService {
    private static final String ASSET_USDT = "USDT";
    private static final String ASSET_NEX = "NEX";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_DEAD = "DEAD";
    private static final String BIZ_TYPE_DEPOSIT = "DEPOSIT";

    private final DepositOrderMapper depositOrderMapper;
    private final WalletService walletService;
    private final DepositChainProvider chainProvider;

    public DepositPostingService(
            DepositOrderMapper depositOrderMapper,
            WalletService walletService,
            DepositChainProvider chainProvider) {
        this.depositOrderMapper = depositOrderMapper;
        this.walletService = walletService;
        this.chainProvider = chainProvider;
    }

    @Transactional(rollbackFor = Exception.class)
    public DepositOrder confirm(ConfirmDepositRequest request) {
        validateRequest(request);
        String chain = request.getChain().trim();
        String txHash = request.getChainTxHash().trim();
        String asset = request.getAsset().trim();

        DepositOrder existing = findByTxAndAsset(txHash, asset);
        if (existing != null) {
            ensureSameConfirmation(existing, request);
            if (STATUS_SUCCESS.equals(existing.getStatus())) {
                return existing;
            }
            DepositChainConfirmation confirmation = confirmOnChain(request);
            return creditAndMarkSuccess(existing, confirmation);
        }

        DepositChainConfirmation confirmation = confirmOnChain(request);
        DepositOrder order = new DepositOrder();
        order.setUserId(request.getUserId());
        order.setDepositNo(depositNo(asset, txHash));
        order.setChain(chain);
        order.setChainTxHash(txHash);
        order.setAsset(asset);
        order.setAmount(request.getAmount());
        order.setConfirmations(normalizeConfirmations(confirmation.getConfirmations()));
        order.setStatus(STATUS_PENDING);
        order.setConfirmedAt(LocalDateTime.now());
        order.setIsDeleted(0);

        try {
            depositOrderMapper.insert(order);
        } catch (DuplicateKeyException ex) {
            DepositOrder duplicate = findAnyByTxAndAsset(txHash, asset);
            if (duplicate == null || Integer.valueOf(1).equals(duplicate.getIsDeleted())) {
                throw new BizException("Duplicate deposit order exists in an invalid state");
            }
            ensureSameConfirmation(duplicate, request);
            if (STATUS_SUCCESS.equals(duplicate.getStatus())) {
                return duplicate;
            }
            return creditAndMarkSuccess(duplicate, confirmation);
        }

        return creditAndMarkSuccess(order, confirmation);
    }

    public List<DepositOrder> listByStatus(String status, Integer limit) {
        return depositOrderMapper.selectList(new LambdaQueryWrapper<DepositOrder>()
                .eq(DepositOrder::getStatus, status)
                .eq(DepositOrder::getIsDeleted, 0)
                .orderByDesc(DepositOrder::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit)));
    }

    public List<DepositOrder> findRecords(String chainTxHash, String asset) {
        LambdaQueryWrapper<DepositOrder> wrapper = new LambdaQueryWrapper<DepositOrder>()
                .eq(DepositOrder::getIsDeleted, 0)
                .eq(StringUtils.hasText(chainTxHash), DepositOrder::getChainTxHash, trim(chainTxHash))
                .eq(StringUtils.hasText(asset), DepositOrder::getAsset, trim(asset))
                .orderByDesc(DepositOrder::getCreatedAt);
        return depositOrderMapper.selectList(wrapper);
    }

    private DepositOrder creditAndMarkSuccess(DepositOrder order, DepositChainConfirmation confirmation) {
        if (!STATUS_PENDING.equals(order.getStatus()) && !STATUS_DEAD.equals(order.getStatus())) {
            throw new BizException("Deposit order is not confirmable");
        }
        ensureProviderMatches(order, confirmation);
        WalletLedger ledger = walletService.postCredit(creditRequest(order));
        DepositOrder patch = new DepositOrder();
        patch.setId(order.getId());
        patch.setStatus(STATUS_SUCCESS);
        patch.setConfirmations(normalizeConfirmations(confirmation.getConfirmations()));
        patch.setLedgerId(ledger.getId());
        patch.setConfirmedAt(order.getConfirmedAt() == null ? LocalDateTime.now() : order.getConfirmedAt());
        patch.setCreditedAt(LocalDateTime.now());
        patch.setFailureReason(null);
        depositOrderMapper.updateById(patch);

        order.setStatus(patch.getStatus());
        order.setConfirmations(patch.getConfirmations());
        order.setLedgerId(patch.getLedgerId());
        order.setConfirmedAt(patch.getConfirmedAt());
        order.setCreditedAt(patch.getCreditedAt());
        order.setFailureReason(null);
        return order;
    }

    private PostWalletCreditRequest creditRequest(DepositOrder order) {
        PostWalletCreditRequest request = new PostWalletCreditRequest();
        request.setUserId(order.getUserId());
        request.setBizNo(order.getDepositNo());
        request.setBizType(BIZ_TYPE_DEPOSIT);
        request.setAsset(order.getAsset());
        request.setAmount(order.getAmount());
        request.setRemark("Deposit " + order.getChainTxHash());
        return request;
    }

    private DepositChainConfirmation confirmOnChain(ConfirmDepositRequest request) {
        DepositChainConfirmation confirmation = chainProvider.confirm(request);
        if (confirmation == null) {
            throw new BizException("Deposit chain confirmation unavailable");
        }
        return confirmation;
    }

    private void ensureSameConfirmation(DepositOrder existing, ConfirmDepositRequest request) {
        if (!existing.getUserId().equals(request.getUserId())
                || !safeEquals(existing.getChain(), request.getChain())
                || !safeEquals(existing.getChainTxHash(), request.getChainTxHash())
                || !safeEquals(existing.getAsset(), request.getAsset())
                || existing.getAmount().compareTo(request.getAmount()) != 0) {
            throw new BizException("Deposit confirmation does not match existing order");
        }
    }

    private void ensureProviderMatches(DepositOrder order, DepositChainConfirmation confirmation) {
        if (!safeEquals(order.getChain(), confirmation.getChain())
                || !safeEquals(order.getChainTxHash(), confirmation.getChainTxHash())
                || !safeEquals(order.getAsset(), confirmation.getAsset())
                || order.getAmount().compareTo(confirmation.getAmount()) != 0) {
            throw new BizException("Deposit chain confirmation does not match order");
        }
    }

    private DepositOrder findByTxAndAsset(String txHash, String asset) {
        return depositOrderMapper.selectOne(new LambdaQueryWrapper<DepositOrder>()
                .eq(DepositOrder::getChainTxHash, txHash)
                .eq(DepositOrder::getAsset, asset)
                .eq(DepositOrder::getIsDeleted, 0));
    }

    private DepositOrder findAnyByTxAndAsset(String txHash, String asset) {
        return depositOrderMapper.selectOne(new LambdaQueryWrapper<DepositOrder>()
                .eq(DepositOrder::getChainTxHash, txHash)
                .eq(DepositOrder::getAsset, asset));
    }

    private void validateRequest(ConfirmDepositRequest request) {
        if (request == null) {
            throw new BizException("Deposit confirmation request is required");
        }
        if (request.getUserId() == null) {
            throw new BizException("User id is required");
        }
        if (!StringUtils.hasText(request.getChain())) {
            throw new BizException("Deposit chain is required");
        }
        if (request.getChain().trim().length() > 32) {
            throw new BizException("Deposit chain is too long");
        }
        if (!StringUtils.hasText(request.getChainTxHash())) {
            throw new BizException("Chain tx hash is required");
        }
        if (request.getChainTxHash().trim().length() > 128) {
            throw new BizException("Chain tx hash is too long");
        }
        validateAsset(trim(request.getAsset()));
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("Deposit amount must be positive");
        }
        if (request.getConfirmations() != null && request.getConfirmations() < 0) {
            throw new BizException("Deposit confirmations must not be negative");
        }
    }

    private void validateAsset(String asset) {
        if (!ASSET_USDT.equals(asset) && !ASSET_NEX.equals(asset)) {
            throw new BizException("Unsupported asset: " + asset);
        }
    }

    private String depositNo(String asset, String txHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((asset + ":" + txHash).getBytes(StandardCharsets.UTF_8));
            return "DEP-" + asset + "-" + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private int normalizeConfirmations(Integer confirmations) {
        return confirmations == null ? 0 : confirmations;
    }

    private boolean safeEquals(String left, String right) {
        return trim(left).equals(trim(right));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
