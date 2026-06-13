package ffdd.wallet.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.wallet.client.ComplianceClient;
import ffdd.wallet.client.SystemConfigClient;
import ffdd.wallet.client.dto.ComplianceGateRequest;
import ffdd.wallet.client.dto.ComplianceGateResponse;
import ffdd.wallet.domain.EarningEvent;
import ffdd.wallet.domain.ExchangeOrder;
import ffdd.wallet.domain.UserWallet;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.domain.WithdrawalOrder;
import ffdd.wallet.dto.ApplyRiskDecisionRequest;
import ffdd.wallet.dto.CreateExchangeRequest;
import ffdd.wallet.dto.CreateWithdrawalRequest;
import ffdd.wallet.dto.FailWithdrawalRequest;
import ffdd.wallet.dto.LedgerQueryRequest;
import ffdd.wallet.dto.PostEarningRequest;
import ffdd.wallet.dto.PostEarningsResponse;
import ffdd.wallet.dto.PostPendingEarningsRequest;
import ffdd.wallet.dto.PostWalletCreditRequest;
import ffdd.wallet.dto.PostWalletDebitRequest;
import ffdd.wallet.dto.RiskDecisionApplyResult;
import ffdd.wallet.dto.SubmitWithdrawalChainRequest;
import ffdd.wallet.dto.SucceedWithdrawalRequest;
import ffdd.wallet.dto.WalletOrderQueryRequest;
import ffdd.wallet.mapper.EarningEventMapper;
import ffdd.wallet.mapper.ExchangeOrderMapper;
import ffdd.wallet.mapper.UserWalletMapper;
import ffdd.wallet.mapper.WalletLedgerMapper;
import ffdd.wallet.mapper.WithdrawalOrderMapper;
import ffdd.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class WalletServiceImpl implements WalletService {
    private static final String ASSET_USDT = "USDT";
    private static final String ASSET_NEX = "NEX";
    private static final String DIRECTION_IN = "IN";
    private static final String DIRECTION_OUT = "OUT";
    private static final String BIZ_TYPE_EARNING = "EARNING";
    private static final String BIZ_TYPE_WITHDRAWAL = "WITHDRAWAL";
    private static final String BIZ_TYPE_WITHDRAWAL_REFUND = "WITHDRAWAL_REFUND";
    private static final String BIZ_TYPE_EXCHANGE = "EXCHANGE";
    private static final String BIZ_TYPE_EXCHANGE_IN = "EXCHANGE_IN";
    private static final String BIZ_TYPE_EXCHANGE_OUT = "EXCHANGE_OUT";
    private static final String LEDGER_SUCCESS = "SUCCESS";
    private static final String EARNING_PENDING_WALLET = "PENDING_WALLET";
    private static final String EARNING_POSTED = "POSTED";
    private static final String DECISION_APPROVE = "APPROVE";
    private static final String DECISION_REJECT = "REJECT";
    private static final String DECISION_REVIEW = "REVIEW";
    private static final String ORDER_REJECTED = "REJECTED";
    private static final String ORDER_REVIEWING = "REVIEWING";
    private static final String WITHDRAWAL_PENDING_CHAIN = "PENDING_CHAIN";
    private static final String WITHDRAWAL_CHAIN_SUBMITTED = "CHAIN_SUBMITTED";
    private static final String WITHDRAWAL_SUCCESS = "SUCCESS";
    private static final String WITHDRAWAL_FAILED = "FAILED";
    private static final String WITHDRAWAL_DEAD = "DEAD";
    private static final String EXCHANGE_COMPLETED = "COMPLETED";
    private static final String DEFAULT_WITHDRAWAL_CHAIN = "USDT-TRC20";
    private static final BigDecimal DEFAULT_WITHDRAWAL_MIN_USDT = new BigDecimal("20");
    private static final BigDecimal DEFAULT_WITHDRAWAL_FEE_RATE = new BigDecimal("0.02");
    private static final BigDecimal DEFAULT_WITHDRAWAL_MAX_BALANCE_PCT = new BigDecimal("0.80");
    private static final int DEFAULT_WITHDRAWAL_DAILY_COUNT_LIMIT = 1;
    private static final BigDecimal DEFAULT_EXCHANGE_MIN_USDT_VALUE = BigDecimal.ZERO;
    private static final BigDecimal DEFAULT_EXCHANGE_MAX_USDT_VALUE = BigDecimal.ZERO;
    private static final BigDecimal DEFAULT_EXCHANGE_NEX_USDT_PRICE = new BigDecimal("0.171");
    private static final int DEFAULT_EXCHANGE_DAILY_COUNT_LIMIT = 99;
    private static final String WITHDRAWAL_CHAIN_TRC20 = "USDT-TRC20";
    private static final String WITHDRAWAL_CHAIN_ERC20 = "USDT-ERC20";

    private final UserWalletMapper walletMapper;
    private final WalletLedgerMapper ledgerMapper;
    private final EarningEventMapper earningEventMapper;
    private final WithdrawalOrderMapper withdrawalOrderMapper;
    private final ExchangeOrderMapper exchangeOrderMapper;
    private final ComplianceClient complianceClient;
    private final SystemConfigClient systemConfigClient;

    public WalletServiceImpl(
            UserWalletMapper walletMapper,
            WalletLedgerMapper ledgerMapper,
            EarningEventMapper earningEventMapper,
            WithdrawalOrderMapper withdrawalOrderMapper,
            ExchangeOrderMapper exchangeOrderMapper,
            ComplianceClient complianceClient,
            SystemConfigClient systemConfigClient) {
        this.walletMapper = walletMapper;
        this.ledgerMapper = ledgerMapper;
        this.earningEventMapper = earningEventMapper;
        this.withdrawalOrderMapper = withdrawalOrderMapper;
        this.exchangeOrderMapper = exchangeOrderMapper;
        this.complianceClient = complianceClient;
        this.systemConfigClient = systemConfigClient;
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
    public PageResult<WithdrawalOrder> pageWithdrawals(WalletOrderQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<WithdrawalOrder> wrapper = new LambdaQueryWrapper<WithdrawalOrder>()
                .eq(WithdrawalOrder::getIsDeleted, 0)
                .eq(request.getUserId() != null, WithdrawalOrder::getUserId, request.getUserId())
                .eq(StringUtils.hasText(request.getStatus()), WithdrawalOrder::getStatus, request.getStatus())
                .orderByDesc(WithdrawalOrder::getCreatedAt);
        Page<WithdrawalOrder> page = withdrawalOrderMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    public PageResult<ExchangeOrder> pageExchanges(WalletOrderQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<ExchangeOrder> wrapper = new LambdaQueryWrapper<ExchangeOrder>()
                .eq(ExchangeOrder::getIsDeleted, 0)
                .eq(request.getUserId() != null, ExchangeOrder::getUserId, request.getUserId())
                .eq(StringUtils.hasText(request.getStatus()), ExchangeOrder::getStatus, request.getStatus())
                .orderByDesc(ExchangeOrder::getCreatedAt);
        Page<ExchangeOrder> page = exchangeOrderMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    public ExchangeOrder getExchange(String exchangeNo) {
        return requireExchange(exchangeNo);
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WalletLedger postCredit(PostWalletCreditRequest request) {
        return postCredit(
                request.getUserId(),
                request.getBizNo(),
                request.getBizType(),
                request.getAsset(),
                request.getAmount(),
                request.getRemark());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WalletLedger postDebit(PostWalletDebitRequest request) {
        return postDebit(
                request.getUserId(),
                request.getBizNo(),
                request.getBizType(),
                request.getAsset(),
                request.getAmount(),
                request.getRemark());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WalletLedger reverseLedger(Long ledgerId, String reason) {
        WalletLedger original = ledgerMapper.selectById(ledgerId);
        if (original == null || Integer.valueOf(1).equals(original.getIsDeleted())) {
            throw new BizException("Wallet ledger not found");
        }
        if (StringUtils.hasText(original.getBizType()) && original.getBizType().endsWith("_REVERSAL")) {
            throw new BizException("Reversal ledger cannot be reversed again");
        }
        String reverseBizNo = original.getBizNo() + "-REV-" + original.getId();
        String reverseBizType = (StringUtils.hasText(original.getBizType()) ? original.getBizType() : "WALLET") + "_REVERSAL";
        String remark = "Reverse ledger " + original.getId() + ": " + reason;
        if (DIRECTION_IN.equals(original.getDirection())) {
            return postDebit(original.getUserId(), reverseBizNo, reverseBizType, original.getAsset(), original.getAmount(), remark);
        }
        if (DIRECTION_OUT.equals(original.getDirection())) {
            return postCredit(original.getUserId(), reverseBizNo, reverseBizType, original.getAsset(), original.getAmount(), remark);
        }
        throw new BizException("Unsupported ledger direction");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WithdrawalOrder createWithdrawal(CreateWithdrawalRequest request) {
        WithdrawalConfig config = withdrawalConfig();
        validateWithdrawal(request, config);
        String withdrawalNo = resolveWithdrawalNo(request);
        request.setWithdrawalNo(withdrawalNo);
        WithdrawalOrder existing = findWithdrawal(request.getWithdrawalNo());
        if (existing != null) {
            return existing;
        }

        if (request.getAmount().compareTo(config.minUsdt()) < 0) {
            throw new BizException("Withdrawal amount is below minimum");
        }
        ensureDailyWithdrawalLimit(request, config);
        ensureWithdrawalBalanceLimit(request, config);
        BigDecimal fee = withdrawalFee(request.getAmount(), config.feeRate());
        BigDecimal totalDebit = request.getAmount().add(fee);
        ComplianceGateResponse gate = checkCompliance(
                request.getUserId(), BIZ_TYPE_WITHDRAWAL, request.getWithdrawalNo(), request.getAsset(), totalDebit);

        WithdrawalOrder order = new WithdrawalOrder();
        order.setUserId(request.getUserId());
        order.setWithdrawalNo(request.getWithdrawalNo());
        order.setAsset(request.getAsset());
        order.setChain(withdrawalChain(request.getChain()));
        order.setAmount(request.getAmount());
        order.setFee(fee);
        order.setTargetAddress(request.getTargetAddress());
        order.setRiskDecisionId(gate.getDecisionId());
        order.setStatus(withdrawalStatus(gate));
        order.setIsDeleted(0);
        boolean inserted = insertWithdrawal(order);
        if (!inserted || !isApproved(gate)) {
            return order;
        }

        reserveWithdrawalBalance(
                request.getUserId(),
                request.getWithdrawalNo(),
                request.getAsset(),
                totalDebit,
                "Withdrawal reserve " + request.getWithdrawalNo());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WithdrawalOrder submitWithdrawalChain(String withdrawalNo, SubmitWithdrawalChainRequest request) {
        if (!StringUtils.hasText(withdrawalNo)) {
            throw new BizException("Withdrawal no is required");
        }
        if (request == null || !StringUtils.hasText(request.getChainTxHash())) {
            throw new BizException("Chain tx hash is required");
        }
        if (request.getChainTxHash().length() > 128) {
            throw new BizException("Chain tx hash is too long");
        }
        WithdrawalOrder order = requireWithdrawal(withdrawalNo);
        if (WITHDRAWAL_CHAIN_SUBMITTED.equals(order.getStatus())
                || WITHDRAWAL_SUCCESS.equals(order.getStatus())
                || WITHDRAWAL_FAILED.equals(order.getStatus())) {
            return order;
        }
        if (!WITHDRAWAL_PENDING_CHAIN.equals(order.getStatus()) && !WITHDRAWAL_DEAD.equals(order.getStatus())) {
            throw new BizException("Withdrawal is not pending chain submission");
        }
        WithdrawalOrder patch = new WithdrawalOrder();
        patch.setId(order.getId());
        patch.setStatus(WITHDRAWAL_CHAIN_SUBMITTED);
        patch.setChainTxHash(request.getChainTxHash());
        patch.setChainSubmittedAt(LocalDateTime.now());
        withdrawalOrderMapper.updateById(patch);
        order.setStatus(patch.getStatus());
        order.setChainTxHash(patch.getChainTxHash());
        order.setChainSubmittedAt(patch.getChainSubmittedAt());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WithdrawalOrder succeedWithdrawal(String withdrawalNo, SucceedWithdrawalRequest request) {
        if (!StringUtils.hasText(withdrawalNo)) {
            throw new BizException("Withdrawal no is required");
        }
        WithdrawalOrder order = requireWithdrawal(withdrawalNo);
        if (WITHDRAWAL_SUCCESS.equals(order.getStatus())) {
            return order;
        }
        if (WITHDRAWAL_FAILED.equals(order.getStatus())) {
            throw new BizException("Withdrawal already failed");
        }
        if (!WITHDRAWAL_PENDING_CHAIN.equals(order.getStatus())
                && !WITHDRAWAL_CHAIN_SUBMITTED.equals(order.getStatus())
                && !WITHDRAWAL_DEAD.equals(order.getStatus())) {
            throw new BizException("Withdrawal is not pending settlement");
        }
        releasePendingWithdrawal(order, false);
        WithdrawalOrder patch = new WithdrawalOrder();
        patch.setId(order.getId());
        patch.setStatus(WITHDRAWAL_SUCCESS);
        if (request != null && StringUtils.hasText(request.getChainTxHash())) {
            if (request.getChainTxHash().length() > 128) {
                throw new BizException("Chain tx hash is too long");
            }
            patch.setChainTxHash(request.getChainTxHash());
            order.setChainTxHash(request.getChainTxHash());
        }
        patch.setCompletedAt(LocalDateTime.now());
        withdrawalOrderMapper.updateById(patch);
        order.setStatus(patch.getStatus());
        order.setCompletedAt(patch.getCompletedAt());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WithdrawalOrder failWithdrawal(String withdrawalNo, FailWithdrawalRequest request) {
        if (!StringUtils.hasText(withdrawalNo)) {
            throw new BizException("Withdrawal no is required");
        }
        if (request == null || !StringUtils.hasText(request.getReason())) {
            throw new BizException("Withdrawal failure reason is required");
        }
        if (request.getReason().length() > 255) {
            throw new BizException("Withdrawal failure reason is too long");
        }
        WithdrawalOrder order = requireWithdrawal(withdrawalNo);
        if (WITHDRAWAL_FAILED.equals(order.getStatus())) {
            return order;
        }
        if (WITHDRAWAL_SUCCESS.equals(order.getStatus())) {
            throw new BizException("Withdrawal already succeeded");
        }
        if (!WITHDRAWAL_PENDING_CHAIN.equals(order.getStatus())
                && !WITHDRAWAL_CHAIN_SUBMITTED.equals(order.getStatus())
                && !WITHDRAWAL_DEAD.equals(order.getStatus())) {
            throw new BizException("Withdrawal is not pending settlement");
        }
        releasePendingWithdrawal(order, true);
        recordWithdrawalRefund(order);
        WithdrawalOrder patch = new WithdrawalOrder();
        patch.setId(order.getId());
        patch.setStatus(WITHDRAWAL_FAILED);
        patch.setFailedAt(LocalDateTime.now());
        patch.setFailureReason(request.getReason());
        withdrawalOrderMapper.updateById(patch);
        order.setStatus(patch.getStatus());
        order.setFailedAt(patch.getFailedAt());
        order.setFailureReason(patch.getFailureReason());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExchangeOrder createExchange(CreateExchangeRequest request) {
        validateExchange(request);
        String exchangeNo = resolveExchangeNo(request);
        request.setExchangeNo(exchangeNo);
        ExchangeOrder existing = findExchange(request.getExchangeNo());
        if (existing != null) {
            return existing;
        }

        ExchangeConfig config = exchangeConfig();
        ExchangeQuote quote = quoteExchange(request, config);
        if (config.minUsdtValue().compareTo(BigDecimal.ZERO) > 0 && quote.usdtValue().compareTo(config.minUsdtValue()) < 0) {
            throw new BizException("Exchange amount is below minimum");
        }
        if (config.maxUsdtValue().compareTo(BigDecimal.ZERO) > 0 && quote.usdtValue().compareTo(config.maxUsdtValue()) > 0) {
            throw new BizException("Exchange amount exceeds single limit");
        }
        ensureDailyExchangeLimit(request, config);

        ComplianceGateResponse gate = checkCompliance(
                request.getUserId(), BIZ_TYPE_EXCHANGE, request.getExchangeNo(), request.getFromAsset(), request.getFromAmount());

        ExchangeOrder order = new ExchangeOrder();
        order.setUserId(request.getUserId());
        order.setExchangeNo(request.getExchangeNo());
        order.setFromAsset(request.getFromAsset());
        order.setToAsset(request.getToAsset());
        order.setFromAmount(request.getFromAmount());
        order.setToAmount(quote.toAmount());
        order.setRate(quote.rate());
        order.setStatus(exchangeStatus(gate));
        order.setIsDeleted(0);
        boolean inserted = insertExchange(order);
        if (!inserted || !isApproved(gate)) {
            return order;
        }

        postDebit(
                request.getUserId(),
                request.getExchangeNo(),
                BIZ_TYPE_EXCHANGE_OUT,
                request.getFromAsset(),
                request.getFromAmount(),
                "Exchange debit " + request.getExchangeNo());
        postCredit(
                request.getUserId(),
                request.getExchangeNo(),
                BIZ_TYPE_EXCHANGE_IN,
                request.getToAsset(),
                quote.toAmount(),
                "Exchange credit " + request.getExchangeNo());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RiskDecisionApplyResult applyRiskDecision(ApplyRiskDecisionRequest request) {
        validateRiskDecision(request);
        if (BIZ_TYPE_WITHDRAWAL.equals(request.getBizType())) {
            return applyWithdrawalRiskDecision(request);
        }
        if (BIZ_TYPE_EXCHANGE.equals(request.getBizType())) {
            return applyExchangeRiskDecision(request);
        }
        throw new BizException("Unsupported risk biz type: " + request.getBizType());
    }

    private RiskDecisionApplyResult applyWithdrawalRiskDecision(ApplyRiskDecisionRequest request) {
        WithdrawalOrder order = requireWithdrawal(request.getBizNo());
        ensureRiskDecisionMatches(order.getRiskDecisionId(), request.getDecisionId());
        if (DECISION_APPROVE.equals(request.getDecision())) {
            if (isWithdrawalApprovedState(order.getStatus())) {
                return riskDecisionResult(request, order.getStatus());
            }
            if (!ORDER_REVIEWING.equals(order.getStatus())) {
                throw new BizException("Withdrawal is not awaiting risk review");
            }
            reserveWithdrawalBalance(
                    order.getUserId(),
                    order.getWithdrawalNo(),
                    order.getAsset(),
                    withdrawalTotal(order),
                    "Withdrawal reserve " + order.getWithdrawalNo());
            updateWithdrawalStatus(order, WITHDRAWAL_PENDING_CHAIN);
            return riskDecisionResult(request, order.getStatus());
        }

        if (ORDER_REJECTED.equals(order.getStatus())) {
            return riskDecisionResult(request, order.getStatus());
        }
        if (!ORDER_REVIEWING.equals(order.getStatus())) {
            throw new BizException("Withdrawal is not awaiting risk review");
        }
        updateWithdrawalStatus(order, ORDER_REJECTED);
        return riskDecisionResult(request, order.getStatus());
    }

    private RiskDecisionApplyResult applyExchangeRiskDecision(ApplyRiskDecisionRequest request) {
        ExchangeOrder order = requireExchange(request.getBizNo());
        if (DECISION_APPROVE.equals(request.getDecision())) {
            if (EXCHANGE_COMPLETED.equals(order.getStatus())) {
                return riskDecisionResult(request, order.getStatus());
            }
            if (!ORDER_REVIEWING.equals(order.getStatus())) {
                throw new BizException("Exchange is not awaiting risk review");
            }
            postDebit(
                    order.getUserId(),
                    order.getExchangeNo(),
                    BIZ_TYPE_EXCHANGE_OUT,
                    order.getFromAsset(),
                    order.getFromAmount(),
                    "Exchange debit " + order.getExchangeNo());
            postCredit(
                    order.getUserId(),
                    order.getExchangeNo(),
                    BIZ_TYPE_EXCHANGE_IN,
                    order.getToAsset(),
                    order.getToAmount(),
                    "Exchange credit " + order.getExchangeNo());
            updateExchangeStatus(order, EXCHANGE_COMPLETED);
            return riskDecisionResult(request, order.getStatus());
        }

        if (ORDER_REJECTED.equals(order.getStatus())) {
            return riskDecisionResult(request, order.getStatus());
        }
        if (!ORDER_REVIEWING.equals(order.getStatus())) {
            throw new BizException("Exchange is not awaiting risk review");
        }
        updateExchangeStatus(order, ORDER_REJECTED);
        return riskDecisionResult(request, order.getStatus());
    }

    private WalletLedger postEarningEvent(EarningEvent event) {
        WalletLedger ledger = postCredit(
                event.getUserId(),
                event.getEventNo(),
                BIZ_TYPE_EARNING,
                event.getAsset(),
                event.getAmount(),
                "Earning receipt " + event.getReceiptNo());
        markEventPosted(event);
        return ledger;
    }

    private WalletLedger postCredit(Long userId, String bizNo, String bizType, String asset, BigDecimal amount, String remark) {
        validateCredit(userId, bizNo, bizType, asset, amount);
        WalletLedger existing = findLedger(bizNo, asset, DIRECTION_IN);
        if (existing != null) {
            return existing;
        }

        getOrCreateWallet(userId);
        WalletLedger ledger = createLedger(userId, bizNo, bizType, asset, DIRECTION_IN, amount, remark);
        try {
            ledgerMapper.insert(ledger);
        } catch (DuplicateKeyException ex) {
            WalletLedger duplicate = findLedgerAny(bizNo, asset, DIRECTION_IN);
            if (duplicate == null || Integer.valueOf(1).equals(duplicate.getIsDeleted())) {
                throw new BizException("Duplicate wallet ledger exists in an invalid state");
            }
            return duplicate;
        }

        UserWallet wallet = addBalance(userId, asset, amount);
        WalletLedger patch = new WalletLedger();
        patch.setId(ledger.getId());
        patch.setBalanceAfter(balanceAfter(wallet, asset));
        ledgerMapper.updateById(patch);
        ledger.setBalanceAfter(patch.getBalanceAfter());

        return ledger;
    }

    private WalletLedger postDebit(Long userId, String bizNo, String bizType, String asset, BigDecimal amount, String remark) {
        validateDebit(userId, bizNo, bizType, asset, amount);
        WalletLedger existing = findLedger(bizNo, asset, DIRECTION_OUT);
        if (existing != null) {
            return existing;
        }

        getOrCreateWallet(userId);
        WalletLedger ledger = createLedger(userId, bizNo, bizType, asset, DIRECTION_OUT, amount, remark);
        try {
            ledgerMapper.insert(ledger);
        } catch (DuplicateKeyException ex) {
            WalletLedger duplicate = findLedgerAny(bizNo, asset, DIRECTION_OUT);
            if (duplicate == null || Integer.valueOf(1).equals(duplicate.getIsDeleted())) {
                throw new BizException("Duplicate wallet ledger exists in an invalid state");
            }
            return duplicate;
        }

        UserWallet wallet = subtractBalance(userId, asset, amount);
        WalletLedger patch = new WalletLedger();
        patch.setId(ledger.getId());
        patch.setBalanceAfter(balanceAfter(wallet, asset));
        ledgerMapper.updateById(patch);
        ledger.setBalanceAfter(patch.getBalanceAfter());

        return ledger;
    }

    private WalletLedger reserveWithdrawalBalance(
            Long userId, String withdrawalNo, String asset, BigDecimal amount, String remark) {
        validateDebit(userId, withdrawalNo, BIZ_TYPE_WITHDRAWAL, asset, amount);
        WalletLedger existing = findLedger(withdrawalNo, asset, DIRECTION_OUT);
        if (existing != null) {
            return existing;
        }

        getOrCreateWallet(userId);
        WalletLedger ledger = createLedger(userId, withdrawalNo, BIZ_TYPE_WITHDRAWAL, asset, DIRECTION_OUT, amount, remark);
        try {
            ledgerMapper.insert(ledger);
        } catch (DuplicateKeyException ex) {
            WalletLedger duplicate = findLedgerAny(withdrawalNo, asset, DIRECTION_OUT);
            if (duplicate == null || Integer.valueOf(1).equals(duplicate.getIsDeleted())) {
                throw new BizException("Duplicate wallet ledger exists in an invalid state");
            }
            return duplicate;
        }

        UserWallet wallet = holdWithdrawalBalance(userId, amount);
        WalletLedger patch = new WalletLedger();
        patch.setId(ledger.getId());
        patch.setBalanceAfter(wallet.getUsdtAvailable());
        ledgerMapper.updateById(patch);
        ledger.setBalanceAfter(patch.getBalanceAfter());

        return ledger;
    }

    private WalletLedger createLedger(
            Long userId,
            String bizNo,
            String bizType,
            String asset,
            String direction,
            BigDecimal amount,
            String remark) {
        WalletLedger ledger = new WalletLedger();
        ledger.setUserId(userId);
        ledger.setBizNo(bizNo);
        ledger.setBizType(bizType);
        ledger.setAsset(asset);
        ledger.setDirection(direction);
        ledger.setAmount(amount);
        ledger.setBalanceAfter(BigDecimal.ZERO);
        ledger.setStatus(LEDGER_SUCCESS);
        ledger.setRemark(remark);
        ledger.setIsDeleted(0);
        return ledger;
    }

    private UserWallet addBalance(Long userId, String asset, BigDecimal creditAmount) {
        String amount = creditAmount.toPlainString();
        LambdaUpdateWrapper<UserWallet> wrapper = new LambdaUpdateWrapper<UserWallet>()
                .eq(UserWallet::getUserId, userId)
                .eq(UserWallet::getIsDeleted, 0)
                .setSql(assetColumn(asset) + " = " + assetColumn(asset) + " + " + amount)
                .setSql("version = version + 1");
        if (ASSET_USDT.equals(asset)) {
            wrapper.setSql("lifetime_earned = lifetime_earned + " + amount);
        }
        int updated = walletMapper.update(null, wrapper);
        if (updated == 0) {
            throw new BizException("Wallet update failed");
        }
        return findWallet(userId);
    }

    private UserWallet subtractBalance(Long userId, String asset, BigDecimal debitAmount) {
        String amount = debitAmount.toPlainString();
        LambdaUpdateWrapper<UserWallet> wrapper = new LambdaUpdateWrapper<UserWallet>()
                .eq(UserWallet::getUserId, userId)
                .eq(UserWallet::getIsDeleted, 0)
                .setSql(assetColumn(asset) + " = " + assetColumn(asset) + " - " + amount)
                .setSql("version = version + 1");
        if (ASSET_USDT.equals(asset)) {
            wrapper.ge(UserWallet::getUsdtAvailable, debitAmount);
        } else if (ASSET_NEX.equals(asset)) {
            wrapper.ge(UserWallet::getNexAvailable, debitAmount);
        } else {
            throw new BizException("Unsupported asset: " + asset);
        }
        int updated = walletMapper.update(null, wrapper);
        if (updated == 0) {
            throw new BizException("Insufficient wallet balance");
        }
        return findWallet(userId);
    }

    private UserWallet holdWithdrawalBalance(Long userId, BigDecimal amountToHold) {
        String amount = amountToHold.toPlainString();
        LambdaUpdateWrapper<UserWallet> wrapper = new LambdaUpdateWrapper<UserWallet>()
                .eq(UserWallet::getUserId, userId)
                .eq(UserWallet::getIsDeleted, 0)
                .ge(UserWallet::getUsdtAvailable, amountToHold)
                .setSql("usdt_available = usdt_available - " + amount)
                .setSql("pending_withdraw = pending_withdraw + " + amount)
                .setSql("version = version + 1");
        int updated = walletMapper.update(null, wrapper);
        if (updated == 0) {
            throw new BizException("Insufficient wallet balance");
        }
        return findWallet(userId);
    }

    private void releasePendingWithdrawal(WithdrawalOrder order, boolean refundAvailable) {
        BigDecimal amountToRelease = withdrawalTotal(order);
        String amount = amountToRelease.toPlainString();
        LambdaUpdateWrapper<UserWallet> wrapper = new LambdaUpdateWrapper<UserWallet>()
                .eq(UserWallet::getUserId, order.getUserId())
                .eq(UserWallet::getIsDeleted, 0)
                .ge(UserWallet::getPendingWithdraw, amountToRelease)
                .setSql("pending_withdraw = pending_withdraw - " + amount)
                .setSql("version = version + 1");
        if (refundAvailable) {
            wrapper.setSql("usdt_available = usdt_available + " + amount);
        }
        int updated = walletMapper.update(null, wrapper);
        if (updated == 0) {
            throw new BizException("Pending withdrawal update failed");
        }
    }

    private WalletLedger recordWithdrawalRefund(WithdrawalOrder order) {
        BigDecimal amount = withdrawalTotal(order);
        String refundBizNo = order.getWithdrawalNo() + "-REFUND";
        WalletLedger existing = findLedger(refundBizNo, order.getAsset(), DIRECTION_IN);
        if (existing != null) {
            return existing;
        }
        WalletLedger ledger = createLedger(
                order.getUserId(),
                refundBizNo,
                BIZ_TYPE_WITHDRAWAL_REFUND,
                order.getAsset(),
                DIRECTION_IN,
                amount,
                "Withdrawal refund " + order.getWithdrawalNo());
        try {
            ledgerMapper.insert(ledger);
        } catch (DuplicateKeyException ex) {
            WalletLedger duplicate = findLedgerAny(refundBizNo, order.getAsset(), DIRECTION_IN);
            if (duplicate == null || Integer.valueOf(1).equals(duplicate.getIsDeleted())) {
                throw new BizException("Duplicate wallet ledger exists in an invalid state");
            }
            return duplicate;
        }
        UserWallet wallet = findWallet(order.getUserId());
        WalletLedger patch = new WalletLedger();
        patch.setId(ledger.getId());
        patch.setBalanceAfter(balanceAfter(wallet, order.getAsset()));
        ledgerMapper.updateById(patch);
        ledger.setBalanceAfter(patch.getBalanceAfter());
        return ledger;
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

    private WithdrawalOrder findWithdrawal(String withdrawalNo) {
        return withdrawalOrderMapper.selectOne(new LambdaQueryWrapper<WithdrawalOrder>()
                .eq(WithdrawalOrder::getWithdrawalNo, withdrawalNo)
                .eq(WithdrawalOrder::getIsDeleted, 0));
    }

    private WithdrawalOrder findWithdrawalAny(String withdrawalNo) {
        return withdrawalOrderMapper.selectOne(new LambdaQueryWrapper<WithdrawalOrder>()
                .eq(WithdrawalOrder::getWithdrawalNo, withdrawalNo));
    }

    private WithdrawalOrder requireWithdrawal(String withdrawalNo) {
        WithdrawalOrder order = findWithdrawal(withdrawalNo);
        if (order == null) {
            throw new BizException("Withdrawal order not found");
        }
        return order;
    }

    private ExchangeOrder findExchange(String exchangeNo) {
        return exchangeOrderMapper.selectOne(new LambdaQueryWrapper<ExchangeOrder>()
                .eq(ExchangeOrder::getExchangeNo, exchangeNo)
                .eq(ExchangeOrder::getIsDeleted, 0));
    }

    private ExchangeOrder requireExchange(String exchangeNo) {
        ExchangeOrder order = findExchange(exchangeNo);
        if (order == null) {
            throw new BizException("Exchange order not found");
        }
        return order;
    }

    private ExchangeOrder findExchangeAny(String exchangeNo) {
        return exchangeOrderMapper.selectOne(new LambdaQueryWrapper<ExchangeOrder>()
                .eq(ExchangeOrder::getExchangeNo, exchangeNo));
    }

    private boolean insertWithdrawal(WithdrawalOrder order) {
        try {
            withdrawalOrderMapper.insert(order);
            return true;
        } catch (DuplicateKeyException ex) {
            WithdrawalOrder duplicate = findWithdrawalAny(order.getWithdrawalNo());
            if (duplicate == null || Integer.valueOf(1).equals(duplicate.getIsDeleted())) {
                throw new BizException("Duplicate withdrawal order exists in an invalid state");
            }
            copyWithdrawal(order, duplicate);
            return false;
        }
    }

    private boolean insertExchange(ExchangeOrder order) {
        try {
            exchangeOrderMapper.insert(order);
            return true;
        } catch (DuplicateKeyException ex) {
            ExchangeOrder duplicate = findExchangeAny(order.getExchangeNo());
            if (duplicate == null || Integer.valueOf(1).equals(duplicate.getIsDeleted())) {
                throw new BizException("Duplicate exchange order exists in an invalid state");
            }
            copyExchange(order, duplicate);
            return false;
        }
    }

    private void copyWithdrawal(WithdrawalOrder target, WithdrawalOrder source) {
        target.setId(source.getId());
        target.setUserId(source.getUserId());
        target.setWithdrawalNo(source.getWithdrawalNo());
        target.setAsset(source.getAsset());
        target.setAmount(source.getAmount());
        target.setFee(source.getFee());
        target.setTargetAddress(source.getTargetAddress());
        target.setRiskDecisionId(source.getRiskDecisionId());
        target.setChainTxHash(source.getChainTxHash());
        target.setStatus(source.getStatus());
        target.setChainSubmittedAt(source.getChainSubmittedAt());
        target.setCompletedAt(source.getCompletedAt());
        target.setFailedAt(source.getFailedAt());
        target.setFailureReason(source.getFailureReason());
        target.setIsDeleted(source.getIsDeleted());
    }

    private void copyExchange(ExchangeOrder target, ExchangeOrder source) {
        target.setId(source.getId());
        target.setUserId(source.getUserId());
        target.setExchangeNo(source.getExchangeNo());
        target.setFromAsset(source.getFromAsset());
        target.setToAsset(source.getToAsset());
        target.setFromAmount(source.getFromAmount());
        target.setToAmount(source.getToAmount());
        target.setRate(source.getRate());
        target.setStatus(source.getStatus());
        target.setIsDeleted(source.getIsDeleted());
    }

    private ComplianceGateResponse checkCompliance(
            Long userId, String bizType, String bizNo, String asset, BigDecimal amount) {
        ComplianceGateRequest request = new ComplianceGateRequest();
        request.setUserId(userId);
        request.setBizType(bizType);
        request.setBizNo(bizNo);
        request.setAsset(asset);
        request.setAmount(amount);
        try {
            ApiResult<ComplianceGateResponse> result = complianceClient.check(request);
            if (result == null || result.getCode() != 0 || result.getData() == null) {
                throw new BizException("Compliance gate unavailable");
            }
            return result.getData();
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException("Compliance gate unavailable");
        }
    }

    private boolean isApproved(ComplianceGateResponse response) {
        return response != null && DECISION_APPROVE.equals(response.getDecision());
    }

    private boolean isReview(ComplianceGateResponse response) {
        return response != null && DECISION_REVIEW.equals(response.getDecision());
    }

    private String withdrawalStatus(ComplianceGateResponse response) {
        if (isApproved(response)) {
            return WITHDRAWAL_PENDING_CHAIN;
        }
        return isReview(response) ? ORDER_REVIEWING : ORDER_REJECTED;
    }

    private String exchangeStatus(ComplianceGateResponse response) {
        if (isApproved(response)) {
            return EXCHANGE_COMPLETED;
        }
        return isReview(response) ? ORDER_REVIEWING : ORDER_REJECTED;
    }

    private boolean isWithdrawalApprovedState(String status) {
        return WITHDRAWAL_PENDING_CHAIN.equals(status)
                || WITHDRAWAL_CHAIN_SUBMITTED.equals(status)
                || WITHDRAWAL_SUCCESS.equals(status)
                || WITHDRAWAL_FAILED.equals(status)
                || WITHDRAWAL_DEAD.equals(status);
    }

    private void updateWithdrawalStatus(WithdrawalOrder order, String status) {
        WithdrawalOrder patch = new WithdrawalOrder();
        patch.setId(order.getId());
        patch.setStatus(status);
        withdrawalOrderMapper.updateById(patch);
        order.setStatus(status);
    }

    private void updateExchangeStatus(ExchangeOrder order, String status) {
        ExchangeOrder patch = new ExchangeOrder();
        patch.setId(order.getId());
        patch.setStatus(status);
        exchangeOrderMapper.updateById(patch);
        order.setStatus(status);
    }

    private RiskDecisionApplyResult riskDecisionResult(ApplyRiskDecisionRequest request, String status) {
        return new RiskDecisionApplyResult(request.getBizType(), request.getBizNo(), status);
    }

    private void ensureRiskDecisionMatches(Long orderRiskDecisionId, Long requestDecisionId) {
        if (orderRiskDecisionId != null && requestDecisionId != null && !orderRiskDecisionId.equals(requestDecisionId)) {
            throw new BizException("Risk decision does not match wallet order");
        }
    }

    private void validateAsset(String asset) {
        if (!ASSET_USDT.equals(asset) && !ASSET_NEX.equals(asset)) {
            throw new BizException("Unsupported asset: " + asset);
        }
    }

    private void validateCredit(Long userId, String bizNo, String bizType, String asset, BigDecimal amount) {
        if (userId == null) {
            throw new BizException("User id is required");
        }
        if (!StringUtils.hasText(bizNo)) {
            throw new BizException("Biz no is required");
        }
        if (!StringUtils.hasText(bizType)) {
            throw new BizException("Biz type is required");
        }
        validateAsset(asset);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("Credit amount must be positive");
        }
    }

    private void validateDebit(Long userId, String bizNo, String bizType, String asset, BigDecimal amount) {
        if (userId == null) {
            throw new BizException("User id is required");
        }
        if (!StringUtils.hasText(bizNo)) {
            throw new BizException("Biz no is required");
        }
        if (!StringUtils.hasText(bizType)) {
            throw new BizException("Biz type is required");
        }
        validateAsset(asset);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("Debit amount must be positive");
        }
    }

    private void validateWithdrawal(CreateWithdrawalRequest request, WithdrawalConfig config) {
        if (request.getUserId() == null) {
            throw new BizException("User id is required");
        }
        if (StringUtils.hasText(request.getWithdrawalNo()) && request.getWithdrawalNo().length() > 64) {
            throw new BizException("Withdrawal no is too long");
        }
        validateAsset(request.getAsset());
        if (!ASSET_USDT.equals(request.getAsset())) {
            throw new BizException("Withdrawal only supports USDT");
        }
        validateWithdrawalChain(request.getChain(), config);
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("Withdrawal amount must be positive");
        }
        if (!StringUtils.hasText(request.getTargetAddress())) {
            throw new BizException("Target address is required");
        }
        if (request.getTargetAddress().length() > 128) {
            throw new BizException("Target address is too long");
        }
    }

    private void validateExchange(CreateExchangeRequest request) {
        if (request.getUserId() == null) {
            throw new BizException("User id is required");
        }
        if (StringUtils.hasText(request.getExchangeNo()) && request.getExchangeNo().length() > 64) {
            throw new BizException("Exchange no is too long");
        }
        validateAsset(request.getFromAsset());
        validateAsset(request.getToAsset());
        if (request.getFromAsset().equals(request.getToAsset())) {
            throw new BizException("Exchange assets must be different");
        }
        if (request.getFromAmount() == null || request.getFromAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("Exchange from amount must be positive");
        }
    }

    private void validateRiskDecision(ApplyRiskDecisionRequest request) {
        if (request == null) {
            throw new BizException("Risk decision request is required");
        }
        if (request.getDecisionId() == null) {
            throw new BizException("Risk decision id is required");
        }
        if (!StringUtils.hasText(request.getDecisionNo())) {
            throw new BizException("Risk decision no is required");
        }
        if (!StringUtils.hasText(request.getBizType())) {
            throw new BizException("Risk biz type is required");
        }
        if (!StringUtils.hasText(request.getBizNo())) {
            throw new BizException("Risk biz no is required");
        }
        if (!DECISION_APPROVE.equals(request.getDecision()) && !DECISION_REJECT.equals(request.getDecision())) {
            throw new BizException("Unsupported risk decision: " + request.getDecision());
        }
    }

    private BigDecimal normalizeMoney(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private String withdrawalChain(String chain) {
        return StringUtils.hasText(chain) ? chain.trim().toUpperCase() : DEFAULT_WITHDRAWAL_CHAIN;
    }

    private void validateWithdrawalChain(String chain, WithdrawalConfig config) {
        String normalized = withdrawalChain(chain);
        boolean supported = (WITHDRAWAL_CHAIN_TRC20.equals(normalized) && config.trc20Enabled())
                || (WITHDRAWAL_CHAIN_ERC20.equals(normalized) && config.erc20Enabled());
        if (!supported) {
            throw new BizException("Unsupported withdrawal chain");
        }
    }

    private WithdrawalConfig withdrawalConfig() {
        try {
            Map<String, Object> data = walletConfigData();
            return new WithdrawalConfig(
                    configDecimal(data, "withdrawal.min_usdt", DEFAULT_WITHDRAWAL_MIN_USDT),
                    configDecimal(data, "withdrawal.fee_rate", DEFAULT_WITHDRAWAL_FEE_RATE),
                    configDecimal(data, "withdrawal.max_balance_pct", DEFAULT_WITHDRAWAL_MAX_BALANCE_PCT),
                    configInt(data, "withdrawal.daily_count_limit", DEFAULT_WITHDRAWAL_DAILY_COUNT_LIMIT),
                    configBoolean(data, "withdrawal.trc20.enabled", true),
                    configBoolean(data, "withdrawal.erc20.enabled", true));
        } catch (RuntimeException ex) {
            return new WithdrawalConfig(
                    DEFAULT_WITHDRAWAL_MIN_USDT,
                    DEFAULT_WITHDRAWAL_FEE_RATE,
                    DEFAULT_WITHDRAWAL_MAX_BALANCE_PCT,
                    DEFAULT_WITHDRAWAL_DAILY_COUNT_LIMIT,
                    true,
                    true);
        }
    }

    private ExchangeConfig exchangeConfig() {
        try {
            Map<String, Object> data = walletConfigData();
            return new ExchangeConfig(
                    configDecimal(data, "exchange.nex_usdt_price", DEFAULT_EXCHANGE_NEX_USDT_PRICE),
                    configDecimal(data, "exchange.min_usdt_value", DEFAULT_EXCHANGE_MIN_USDT_VALUE),
                    configDecimal(data, "exchange.max_usdt_value", DEFAULT_EXCHANGE_MAX_USDT_VALUE),
                    configInt(data, "exchange.daily_count_limit", DEFAULT_EXCHANGE_DAILY_COUNT_LIMIT));
        } catch (RuntimeException ex) {
            return new ExchangeConfig(
                    DEFAULT_EXCHANGE_NEX_USDT_PRICE,
                    DEFAULT_EXCHANGE_MIN_USDT_VALUE,
                    DEFAULT_EXCHANGE_MAX_USDT_VALUE,
                    DEFAULT_EXCHANGE_DAILY_COUNT_LIMIT);
        }
    }

    private Map<String, Object> walletConfigData() {
        ApiResult<Map<String, Object>> response = systemConfigClient.wallet();
        return response == null ? null : response.getData();
    }

    private BigDecimal configDecimal(Map<String, Object> data, String key, BigDecimal fallback) {
        if (data == null || !data.containsKey(key)) {
            return fallback;
        }
        Object value = data.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            BigDecimal decimal = new BigDecimal(String.valueOf(value));
            return decimal.compareTo(BigDecimal.ZERO) < 0 ? fallback : decimal;
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

    private boolean configBoolean(Map<String, Object> data, String key, boolean fallback) {
        if (data == null || !data.containsKey(key) || data.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(data.get(key)).trim();
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) {
            return false;
        }
        return fallback;
    }

    private void ensureDailyWithdrawalLimit(CreateWithdrawalRequest request, WithdrawalConfig config) {
        LocalDateTime since = LocalDateTime.now().toLocalDate().atStartOfDay();
        Long count = withdrawalOrderMapper.selectCount(new LambdaQueryWrapper<WithdrawalOrder>()
                .eq(WithdrawalOrder::getUserId, request.getUserId())
                .eq(WithdrawalOrder::getIsDeleted, 0)
                .ge(WithdrawalOrder::getCreatedAt, since)
                .notIn(WithdrawalOrder::getStatus, ORDER_REJECTED));
        if (count != null && count >= config.dailyCountLimit()) {
            throw new BizException("Daily withdrawal limit reached");
        }
    }

    private void ensureWithdrawalBalanceLimit(CreateWithdrawalRequest request, WithdrawalConfig config) {
        if (config.maxBalancePct().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        UserWallet wallet = getOrCreateWallet(request.getUserId());
        BigDecimal available = normalizeMoney(wallet.getUsdtAvailable());
        BigDecimal maxAmount = available.multiply(config.maxBalancePct()).setScale(6, RoundingMode.DOWN);
        if (request.getAmount().compareTo(maxAmount) > 0) {
            throw new BizException("Withdrawal amount exceeds balance percentage limit");
        }
    }

    private void ensureDailyExchangeLimit(CreateExchangeRequest request, ExchangeConfig config) {
        LocalDateTime since = LocalDateTime.now().toLocalDate().atStartOfDay();
        Long count = exchangeOrderMapper.selectCount(new LambdaQueryWrapper<ExchangeOrder>()
                .eq(ExchangeOrder::getUserId, request.getUserId())
                .eq(ExchangeOrder::getIsDeleted, 0)
                .ge(ExchangeOrder::getCreatedAt, since)
                .notIn(ExchangeOrder::getStatus, ORDER_REJECTED));
        if (count != null && count >= config.dailyCountLimit()) {
            throw new BizException("Daily exchange limit reached");
        }
    }

    private ExchangeQuote quoteExchange(CreateExchangeRequest request, ExchangeConfig config) {
        BigDecimal price = config.nexUsdtPrice();
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("Exchange price is not configured");
        }
        if (ASSET_USDT.equals(request.getFromAsset()) && ASSET_NEX.equals(request.getToAsset())) {
            BigDecimal toAmount = request.getFromAmount().divide(price, 8, RoundingMode.HALF_UP);
            BigDecimal rate = BigDecimal.ONE.divide(price, 8, RoundingMode.HALF_UP);
            return new ExchangeQuote(toAmount, rate, request.getFromAmount());
        }
        if (ASSET_NEX.equals(request.getFromAsset()) && ASSET_USDT.equals(request.getToAsset())) {
            BigDecimal toAmount = request.getFromAmount().multiply(price).setScale(8, RoundingMode.HALF_UP);
            BigDecimal rate = price.setScale(8, RoundingMode.HALF_UP);
            return new ExchangeQuote(toAmount, rate, toAmount);
        }
        throw new BizException("Exchange pair is not supported");
    }

    private BigDecimal withdrawalFee(BigDecimal amount, BigDecimal feeRate) {
        return amount.multiply(feeRate).setScale(6, RoundingMode.HALF_UP);
    }

    private String resolveWithdrawalNo(CreateWithdrawalRequest request) {
        return StringUtils.hasText(request.getWithdrawalNo()) ? request.getWithdrawalNo().trim() : generateOrderNo("WD");
    }

    private String resolveExchangeNo(CreateExchangeRequest request) {
        return StringUtils.hasText(request.getExchangeNo()) ? request.getExchangeNo().trim() : generateOrderNo("EX");
    }

    private String generateOrderNo(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private BigDecimal withdrawalTotal(WithdrawalOrder order) {
        return order.getAmount().add(normalizeMoney(order.getFee()));
    }

    private record WithdrawalConfig(
            BigDecimal minUsdt,
            BigDecimal feeRate,
            BigDecimal maxBalancePct,
            int dailyCountLimit,
            boolean trc20Enabled,
            boolean erc20Enabled) {
    }

    private record ExchangeConfig(
            BigDecimal nexUsdtPrice,
            BigDecimal minUsdtValue,
            BigDecimal maxUsdtValue,
            int dailyCountLimit) {
    }

    private record ExchangeQuote(BigDecimal toAmount, BigDecimal rate, BigDecimal usdtValue) {
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
