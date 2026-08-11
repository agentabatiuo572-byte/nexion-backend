package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.FundsSandboxMapper;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper.CallbackRow;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper.CallbackWrite;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper.LedgerWrite;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper.OrderRow;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper.OrderWrite;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper.WalletRow;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class FundsSandboxService {
    private static final Set<String> TOPUP_CHANNELS = Set.of("CREGIS_USDT_BEP20", "VIETQR", "CARD");
    private static final Pattern EVM_ADDRESS = Pattern.compile("(?i)^0x[0-9a-f]{40}$");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.000000");
    private static final BigDecimal SANDBOX_WITHDRAWAL_MIN_AMOUNT = new BigDecimal("1.000000");

    private final FundsSandboxMapper mapper;
    private final FundsSandboxProperties properties;
    private final Clock clock;

    public Overview overview(Long userId) {
        requireLocalSandbox();
        requireUser(userId);
        mapper.insertWalletIfAbsent(userId);
        WalletRow wallet = requireWallet(mapper.walletSnapshot(userId));
        return new Overview(walletView(wallet), mapper.listOrders(userId, 100), mapper.listLedger(userId, 200), withdrawalPolicy(),
                "mock", "SANDBOX", "LOCAL_SANDBOX");
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderView createTopup(Long userId, String channel, BigDecimal amount, String idempotencyKey) {
        requireLocalSandbox();
        requireUser(userId);
        String normalizedChannel = normalizeTopupChannel(channel);
        BigDecimal normalizedAmount = money(amount);
        String key = requireKey(idempotencyKey);
        String requestHash = hash(userId + "|TOPUP|" + normalizedChannel + "|" + normalizedAmount);
        OrderRow replay = mapper.findOrderByIdempotency(userId, key);
        if (replay != null) return replay(replay, requestHash);

        mapper.insertWalletIfAbsent(userId);
        WalletRow wallet = requireWallet(mapper.lockWallet(userId));
        replay = mapper.findOrderByIdempotency(userId, key);
        if (replay != null) return replay(replay, requestHash);

        LocalDateTime now = LocalDateTime.now(clock);
        String orderNo = stableNo("SBX-TU-", userId + "|" + key + "|" + requestHash);
        OrderWrite write = new OrderWrite(orderNo, userId, "TOPUP", normalizedChannel, normalizedAmount,
                null, "PENDING", "mock", "SANDBOX", key, requestHash, 0L, now);
        if (mapper.insertOrder(write) != 1) throw new BizException(409, "FUNDS_SANDBOX_ORDER_CONFLICT");

        String eventId = stableNo("SBX-CB-", orderNo + "|SETTLED");
        CallbackWrite callback = new CallbackWrite(eventId, userId, orderNo, "SETTLED",
                hash(orderNo + "|SETTLED|0"), now);
        if (mapper.insertCallback(callback) != 1) throw new BizException(409, "FUNDS_SANDBOX_CALLBACK_CONFLICT");
        if (mapper.transitionOrder(orderNo, userId, "PENDING", "SETTLED", 0L) != 1) {
            throw new BizException(409, "FUNDS_SANDBOX_ORDER_VERSION_CONFLICT");
        }
        if (mapper.creditWallet(userId, normalizedAmount, wallet.version()) != 1) {
            throw new BizException(409, "FUNDS_SANDBOX_WALLET_VERSION_CONFLICT");
        }
        WalletRow after = new WalletRow(userId, wallet.availableUsdt().add(normalizedAmount),
                wallet.reservedUsdt(), wallet.version() + 1);
        insertLedger(userId, orderNo, "TOPUP_CREDIT", "IN", normalizedAmount, after, now);
        if (mapper.markCallbackProcessed(eventId, "PROCESSED") != 1) {
            throw new BizException(409, "FUNDS_SANDBOX_CALLBACK_STATE_CONFLICT");
        }
        return view(write, "SETTLED", 1L, now, after);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderView createWithdrawal(
            Long userId, String channel, BigDecimal amount, String targetAddress, String idempotencyKey) {
        requireLocalSandbox();
        requireUser(userId);
        String normalizedChannel = normalizeWithdrawalChannel(channel);
        BigDecimal normalizedAmount = money(amount);
        if (normalizedAmount.compareTo(SANDBOX_WITHDRAWAL_MIN_AMOUNT) < 0) {
            throw new BizException(422, "FUNDS_SANDBOX_WITHDRAWAL_MIN_AMOUNT");
        }
        String address = normalizeAddress(targetAddress);
        String key = requireKey(idempotencyKey);
        String requestHash = hash(userId + "|WITHDRAWAL|" + normalizedChannel + "|"
                + normalizedAmount + "|" + address);
        OrderRow replay = mapper.findOrderByIdempotency(userId, key);
        if (replay != null) return replay(replay, requestHash);

        mapper.insertWalletIfAbsent(userId);
        WalletRow wallet = requireWallet(mapper.lockWallet(userId));
        replay = mapper.findOrderByIdempotency(userId, key);
        if (replay != null) return replay(replay, requestHash);
        if (wallet.availableUsdt().compareTo(normalizedAmount) < 0) {
            throw new BizException(409, "FUNDS_SANDBOX_INSUFFICIENT_BALANCE");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String orderNo = stableNo("SBX-WD-", userId + "|" + key + "|" + requestHash);
        OrderWrite write = new OrderWrite(orderNo, userId, "WITHDRAWAL", normalizedChannel,
                normalizedAmount, address, "SUBMITTED", "mock", "SANDBOX", key, requestHash, 0L, now);
        if (mapper.insertOrder(write) != 1) throw new BizException(409, "FUNDS_SANDBOX_ORDER_CONFLICT");
        if (mapper.reserveWallet(userId, normalizedAmount, wallet.version()) != 1) {
            throw new BizException(409, "FUNDS_SANDBOX_WALLET_VERSION_CONFLICT");
        }
        WalletRow after = new WalletRow(userId, wallet.availableUsdt().subtract(normalizedAmount),
                wallet.reservedUsdt().add(normalizedAmount), wallet.version() + 1);
        insertLedger(userId, orderNo, "WITHDRAWAL_RESERVE", "RESERVE", normalizedAmount, after, now);
        // No ETA, timer or client-owned completion. Only applyCallback may enter a terminal state.
        return view(write, "SUBMITTED", 0L, null, after);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderView applyCallback(
            Long userId, String orderNo, String eventId, String targetStatus, Long expectedVersion) {
        requireLocalSandbox();
        requireUser(userId);
        String normalizedOrderNo = requireText(orderNo, 96, "FUNDS_SANDBOX_ORDER_NO_REQUIRED");
        String normalizedEventId = requireText(eventId, 128, "FUNDS_SANDBOX_EVENT_ID_REQUIRED");
        String normalizedStatus = normalizeCallbackStatus(targetStatus);
        if (expectedVersion == null || expectedVersion < 0) {
            throw new BizException(422, "FUNDS_SANDBOX_EXPECTED_VERSION_REQUIRED");
        }
        OrderRow order = mapper.findOrderForUser(userId, normalizedOrderNo);
        if (order == null) throw new BizException(404, "FUNDS_SANDBOX_ORDER_NOT_FOUND");
        String requestHash = hash(userId + "|" + normalizedOrderNo + "|" + normalizedStatus + "|" + expectedVersion);
        CallbackRow existing = mapper.findCallback(normalizedEventId);
        if (existing != null) {
            if (existing.userId().equals(userId) && existing.orderNo().equals(normalizedOrderNo)
                    && existing.requestHash().equals(requestHash)) {
                OrderRow current = mapper.findOrderForUser(userId, normalizedOrderNo);
                return view(current == null ? order : current, requireWallet(mapper.walletSnapshot(userId)));
            }
            throw new BizException(409, "FUNDS_SANDBOX_CALLBACK_REPLAY_CONFLICT");
        }
        if (!"WITHDRAWAL".equals(order.kind()) || !"SUBMITTED".equals(order.status())) {
            throw new BizException(409, "FUNDS_SANDBOX_CALLBACK_STATE_CONFLICT");
        }
        if (!order.version().equals(expectedVersion)) {
            throw new BizException(409, "FUNDS_SANDBOX_ORDER_VERSION_CONFLICT");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (mapper.insertCallback(new CallbackWrite(normalizedEventId, userId, normalizedOrderNo,
                normalizedStatus, requestHash, now)) != 1) {
            throw new BizException(409, "FUNDS_SANDBOX_CALLBACK_CONFLICT");
        }
        if (mapper.transitionOrder(normalizedOrderNo, userId, "SUBMITTED", normalizedStatus, expectedVersion) != 1) {
            throw new BizException(409, "FUNDS_SANDBOX_ORDER_VERSION_CONFLICT");
        }
        WalletRow wallet = requireWallet(mapper.lockWallet(userId));
        int walletChanged = "CONFIRMED".equals(normalizedStatus)
                ? mapper.consumeReservedWallet(userId, order.amount(), wallet.version())
                : mapper.releaseReservedWallet(userId, order.amount(), wallet.version());
        if (walletChanged != 1) throw new BizException(409, "FUNDS_SANDBOX_WALLET_VERSION_CONFLICT");
        WalletRow after = "CONFIRMED".equals(normalizedStatus)
                ? new WalletRow(userId, wallet.availableUsdt(), wallet.reservedUsdt().subtract(order.amount()), wallet.version() + 1)
                : new WalletRow(userId, wallet.availableUsdt().add(order.amount()), wallet.reservedUsdt().subtract(order.amount()), wallet.version() + 1);
        insertLedger(userId, normalizedOrderNo,
                "CONFIRMED".equals(normalizedStatus) ? "WITHDRAWAL_DEBIT" : "WITHDRAWAL_RELEASE",
                "CONFIRMED".equals(normalizedStatus) ? "OUT" : "RELEASE", order.amount(), after, now);
        if (mapper.markCallbackProcessed(normalizedEventId, "PROCESSED") != 1) {
            throw new BizException(409, "FUNDS_SANDBOX_CALLBACK_STATE_CONFLICT");
        }
        return new OrderView(order.orderNo(), order.kind(), order.channel(), order.amount(), order.targetAddress(),
                normalizedStatus, "mock", "SANDBOX", expectedVersion + 1, order.createdAt(), now, walletView(after));
    }

    private OrderView replay(OrderRow order, String requestHash) {
        if (!order.requestHash().equals(requestHash)) {
            throw new BizException(409, "FUNDS_SANDBOX_IDEMPOTENCY_CONFLICT");
        }
        return view(order, requireWallet(mapper.walletSnapshot(order.userId())));
    }

    private void insertLedger(Long userId, String orderNo, String role, String direction,
                              BigDecimal amount, WalletRow after, LocalDateTime now) {
        String ledgerNo = stableNo("SBX-LG-", orderNo + "|" + role);
        if (mapper.insertLedger(new LedgerWrite(ledgerNo, userId, orderNo, role, direction, amount,
                after.availableUsdt(), after.reservedUsdt(), now)) != 1) {
            throw new BizException(409, "FUNDS_SANDBOX_LEDGER_CONFLICT");
        }
    }

    private OrderView view(OrderWrite order, String status, Long version, LocalDateTime settledAt, WalletRow wallet) {
        return new OrderView(order.orderNo(), order.kind(), order.channel(), order.amount(), order.targetAddress(),
                status, order.source(), order.sourceEnvironment(), version, order.createdAt(), settledAt, walletView(wallet));
    }

    private OrderView view(OrderRow order, WalletRow wallet) {
        return new OrderView(order.orderNo(), order.kind(), order.channel(), order.amount(), order.targetAddress(),
                order.status(), order.source(), order.sourceEnvironment(), order.version(), order.createdAt(),
                order.settledAt(), walletView(wallet));
    }

    private WalletView walletView(WalletRow wallet) {
        return new WalletView(wallet.availableUsdt(), wallet.reservedUsdt(), wallet.version(), "mock", "SANDBOX");
    }

    /**
     * The isolated withdrawal rail is declared beside its wallet authority.
     * It is intentionally not derived from the production D5/J1 policy: that
     * policy can be closed while the explicit LOCAL_SANDBOX test channel is
     * enabled. All fields that make a sandbox submission possible are part of
     * this signed-in server response, and no provider channel is represented.
     */
    private WithdrawalPolicyView withdrawalPolicy() {
        return new WithdrawalPolicyView(
                SANDBOX_WITHDRAWAL_MIN_AMOUNT,
                10,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                1,
                Map.of("trc20", BigDecimal.ZERO, "bep20", BigDecimal.ZERO, "erc20", BigDecimal.ZERO),
                BigDecimal.ONE,
                "funds-sandbox-v1",
                1,
                false,
                true,
                List.of("USDT-BEP20"),
                "USDT-BEP20",
                "CREGIS_USDT_BEP20",
                "mock",
                "SANDBOX",
                "LOCAL_SANDBOX");
    }

    private WalletRow requireWallet(WalletRow wallet) {
        if (wallet == null || wallet.version() == null || wallet.availableUsdt() == null || wallet.reservedUsdt() == null) {
            throw new BizException(503, "FUNDS_SANDBOX_WALLET_UNAVAILABLE");
        }
        return wallet;
    }

    private void requireLocalSandbox() {
        if (properties.getMode() == FundsSandboxProperties.Mode.PROVIDER) {
            throw new BizException(503, "FUNDS_PROVIDER_NOT_CONFIGURED");
        }
        if (properties.getMode() != FundsSandboxProperties.Mode.LOCAL_SANDBOX) {
            throw new BizException(409, "FUNDS_SANDBOX_DISABLED");
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_SUBJECT_REQUIRED");
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.scale() > 6 || value.compareTo(MAX_AMOUNT) > 0) {
            throw new BizException(422, "FUNDS_SANDBOX_AMOUNT_INVALID");
        }
        return value.setScale(6, RoundingMode.UNNECESSARY);
    }

    private String normalizeTopupChannel(String value) {
        String channel = normalizeToken(value);
        if (!TOPUP_CHANNELS.contains(channel)) throw new BizException(422, "FUNDS_SANDBOX_CHANNEL_INVALID");
        return channel;
    }

    private String normalizeWithdrawalChannel(String value) {
        String channel = normalizeToken(value);
        if (!"CREGIS_USDT_BEP20".equals(channel)) {
            throw new BizException(422, "FUNDS_SANDBOX_WITHDRAWAL_CHANNEL_INVALID");
        }
        return channel;
    }

    private String normalizeAddress(String value) {
        String address = requireText(value, 128, "FUNDS_SANDBOX_TARGET_ADDRESS_REQUIRED").toLowerCase(Locale.ROOT);
        if (!EVM_ADDRESS.matcher(address).matches()) throw new BizException(422, "FUNDS_SANDBOX_TARGET_ADDRESS_INVALID");
        return address;
    }

    private String normalizeCallbackStatus(String value) {
        String status = normalizeToken(value);
        if (!Set.of("CONFIRMED", "FAILED").contains(status)) {
            throw new BizException(422, "FUNDS_SANDBOX_CALLBACK_STATUS_INVALID");
        }
        return status;
    }

    private String normalizeToken(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String requireKey(String value) {
        return requireText(value, 128, "IDEMPOTENCY_KEY_REQUIRED", 8);
    }

    private String requireText(String value, int max, String code) {
        return requireText(value, max, code, 1);
    }

    private String requireText(String value, int max, String code, int min) {
        if (!StringUtils.hasText(value) || value.trim().length() < min || value.trim().length() > max) {
            throw new BizException(422, code);
        }
        return value.trim();
    }

    private String stableNo(String prefix, String material) {
        return prefix + hash(material).substring(0, 24).toUpperCase(Locale.ROOT);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("FUNDS_SANDBOX_HASH_UNAVAILABLE", impossible);
        }
    }

    public record WalletView(BigDecimal availableUsdt, BigDecimal reservedUsdt, Long version,
                             String source, String sourceEnvironment) { }
    public record OrderView(String orderNo, String kind, String channel, BigDecimal amount,
                            String targetAddress, String status, String source, String sourceEnvironment,
                            Long version, LocalDateTime createdAt, LocalDateTime settledAt, WalletView wallet) { }
    public record Overview(WalletView wallet, List<OrderRow> orders,
                           List<FundsSandboxMapper.LedgerRow> ledger, WithdrawalPolicyView withdrawalPolicy, String source,
                           String sourceEnvironment, String mode) { }
    public record WithdrawalPolicyView(
            BigDecimal minAmount, int dailyLimitCount, BigDecimal balanceMaxRatio,
            BigDecimal smallAmountThresholdUsd, int payoutSlaHours,
            Map<String, BigDecimal> networkConfirmFeeUsd, BigDecimal nexFeeOffsetRate,
            String policyVersion, int cooldownDays, boolean complianceHoldEnabled,
            boolean withdrawalEnabled, List<String> enabledNetworks, String network, String channel,
            String source, String sourceEnvironment, String mode) { }
}
