package ffdd.opsconsole.commerce.application;

import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper.Callback;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper.CallbackWrite;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper.InventoryRow;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper.SandboxOrder;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper.LedgerWrite;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Durable acceptance payment rail. It is intentionally reachable only through
 * the profile-gated admin controller; production therefore has no local
 * success path and ordinary orders remain pending payment until a provider is integrated.
 */
@Service
@RequiredArgsConstructor
public class CommerceAcceptanceSandboxService {
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Set<String> EVENTS = Set.of(
            "PAYMENT_SUCCEEDED", "PAYMENT_FAILED", "EXPIRED", "USER_CANCELLED",
            "FULFILLMENT_SUCCEEDED", "FULFILLMENT_FAILED", "REFUNDED");

    private final CommerceAcceptanceSandboxMapper mapper;
    /** Funds Sandbox is the sole authoritative isolated wallet and ledger boundary. */
    private final FundsSandboxMapper fundsMapper;
    private final FundsSandboxProfileGuard fundsSandboxProfileGuard;
    private final Clock clock;
    private final CommerceAcceptanceRun acceptanceRun;

    @Transactional(rollbackFor = Exception.class)
    public CallbackResult applyCallback(String orderNo, String eventId, String event, Long expectedVersion) {
        return applyCallback(orderNo, eventId, event, expectedVersion, "acceptance sandbox callback", "system");
    }

    @Transactional(rollbackFor = Exception.class)
    public CallbackResult applyCallback(String orderNo, String eventId, String event, Long expectedVersion,
                                        String reason, String actor) {
        requireEnabled();
        String runId = acceptanceRun.requireRunId();
        String normalizedOrderNo = requireText(orderNo, 96, "COMMERCE_SANDBOX_ORDER_NO_REQUIRED");
        String normalizedEventId = requireEventId(eventId);
        String normalizedEvent = requireEvent(event);
        if (expectedVersion == null || expectedVersion < 0) {
            throw new BizException(422, "COMMERCE_SANDBOX_EXPECTED_VERSION_REQUIRED");
        }
        String normalizedReason = requireText(reason, 300, "COMMERCE_SANDBOX_CALLBACK_REASON_REQUIRED");
        String normalizedActor = requireText(actor, 128, "COMMERCE_SANDBOX_CALLBACK_ACTOR_REQUIRED");
        String requestHash = sha256(normalizedOrderNo + "|" + normalizedEvent + "|" + expectedVersion);
        Callback replay = mapper.findCallback(runId, normalizedEventId);
        if (replay != null) return replay(replay, requestHash, normalizedReason, normalizedActor);

        // A callback is never an admission path: only the sandbox checkout can create
        // an isolated order/inventory snapshot. This rejects arbitrary production order numbers.
        SandboxOrder sandbox = mapper.lockSandboxOrder(runId, normalizedOrderNo);
        if (sandbox == null) throw new BizException(404, "COMMERCE_SANDBOX_ORDER_NOT_FOUND");
        if (sandbox.version() == null || sandbox.userId() == null || sandbox.productId() == null || sandbox.quantity() == null
                || sandbox.quantity() < 1 || money(sandbox.amountUsdt()).signum() < 0) {
            throw new BizException(503, "COMMERCE_SANDBOX_ORDER_UNAVAILABLE");
        }
        if (fundsMapper.isSandboxUser(sandbox.userId()) == null) {
            throw new BizException(403, "COMMERCE_SANDBOX_USER_REQUIRED");
        }
        // A locking/current read is required here. Under MySQL REPEATABLE_READ a
        // plain select can retain the stale no-row snapshot from before waiting
        // on the order lock, causing the second identical event to hit version
        // conflict instead of returning the frozen first response.
        Callback committedWhileWaiting = mapper.lockCurrentCallback(runId, normalizedEventId);
        if (committedWhileWaiting != null) return replay(committedWhileWaiting, requestHash, normalizedReason, normalizedActor);
        if (!sandbox.version().equals(expectedVersion)) {
            throw new BizException(409, "COMMERCE_SANDBOX_ORDER_VERSION_CONFLICT");
        }
        Transition transition = transition(sandbox.state(), normalizedEvent, sandbox.walletDebited(), sandbox.stockReturned(),
                sandbox.amountUsdt());
        LocalDateTime now = LocalDateTime.now(clock);
        BigDecimal walletAfter = null;
        if (transition.debit(sandbox.amountUsdt())) {
            fundsMapper.insertWalletIfAbsent(runId, sandbox.userId());
            WalletRow wallet = wallet(fundsMapper.lockWallet(runId, sandbox.userId()));
            if (fundsMapper.reserveWallet(runId, sandbox.userId(), sandbox.amountUsdt(), wallet.version()) != 1
                    || fundsMapper.consumeReservedWallet(runId, sandbox.userId(), sandbox.amountUsdt(), wallet.version() + 1) != 1) {
                throw new BizException(409, "COMMERCE_SANDBOX_INSUFFICIENT_BALANCE");
            }
            walletAfter = wallet.availableUsdt().subtract(sandbox.amountUsdt());
            // The payment consumes only its own transient reservation. Existing
            // withdrawal reservations remain part of the authoritative wallet.
            ledger(runId, sandbox, "COMMERCE_PAYMENT_DEBIT", "OUT", walletAfter, wallet.reservedUsdt(), now);
        }
        if (transition.credit(sandbox.amountUsdt())) {
            WalletRow wallet = wallet(fundsMapper.lockWallet(runId, sandbox.userId()));
            if (fundsMapper.creditWallet(runId, sandbox.userId(), sandbox.amountUsdt(), wallet.version()) != 1) {
                throw new BizException(409, "COMMERCE_SANDBOX_WALLET_VERSION_CONFLICT");
            }
            walletAfter = wallet.availableUsdt().add(sandbox.amountUsdt());
            ledger(runId, sandbox, "COMMERCE_REFUND_CREDIT", "IN", walletAfter, wallet.reservedUsdt(), now);
        }
        if (transition.returnStock()) {
            InventoryRow inventory = inventory(mapper.lockInventoryForOrder(runId, sandbox.orderNo()), sandbox);
            if (mapper.releaseInventory(runId, sandbox.orderNo(), inventory.version()) != 1) {
                throw new BizException(409, "COMMERCE_SANDBOX_INVENTORY_VERSION_CONFLICT");
            }
            var catalog = mapper.lockSandboxCatalogProductForReturn(runId, sandbox.productId());
            if (catalog == null || catalog.version() == null
                    || mapper.returnSandboxCatalogStock(runId, sandbox.productId(), catalog.version(), sandbox.quantity()) != 1) {
                throw new BizException(409, "COMMERCE_SANDBOX_CATALOG_VERSION_CONFLICT");
            }
        }
        boolean nextWalletDebited = transition.walletDebited() && sandbox.amountUsdt().signum() > 0;
        if (mapper.transitionSandboxOrder(runId, sandbox.orderNo(), sandbox.version(), sandbox.state(), transition.state(),
                nextWalletDebited, transition.stockReturned()) != 1) {
            throw new BizException(409, "COMMERCE_SANDBOX_ORDER_VERSION_CONFLICT");
        }
        CallbackResult result = new CallbackResult(sandbox.orderNo(), normalizedEvent, canonical(transition.state()), expectedVersion + 1,
                "mock", "SANDBOX", walletAfter);
        // Persist the first response itself; replay must not re-project mutable order state.
        if (mapper.insertCallback(new CallbackWrite(normalizedEventId, normalizedOrderNo, normalizedEvent,
                expectedVersion, requestHash, result.canonicalStatus(), result.version(), result.walletAfter(), now, runId)) != 1) {
            throw new BizException(409, "COMMERCE_SANDBOX_CALLBACK_CONFLICT");
        }
        audit(normalizedActor, normalizedReason, normalizedEventId, result, false);
        return result;
    }

    private CallbackResult replay(Callback replay, String requestHash, String reason, String actor) {
        if (!replay.requestHash().equals(requestHash)) {
            throw new BizException(409, "COMMERCE_SANDBOX_CALLBACK_REPLAY_CONFLICT");
        }
        if (!StringUtils.hasText(replay.canonicalStatus()) || replay.resultVersion() == null) {
            throw new BizException(503, "COMMERCE_SANDBOX_CALLBACK_RESULT_UNAVAILABLE");
        }
        CallbackResult result = new CallbackResult(replay.orderNo(), replay.targetStatus(), replay.canonicalStatus(),
                replay.resultVersion(), "mock", "SANDBOX", replay.walletAfter());
        audit(actor, reason, replay.eventId(), result, true);
        return result;
    }

    private void audit(String actor, String reason, String eventId, CallbackResult result, boolean replay) {
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("eventId", eventId);
        detail.put("event", result.event());
        detail.put("reason", reason);
        detail.put("replay", replay);
        detail.put("source", "mock");
        detail.put("sourceEnvironment", "SANDBOX");
        detail.put("strictProfile", true);
        detail.put("canonicalStatus", result.canonicalStatus());
        detail.put("resultVersion", result.version());
        detail.put("walletAfter", result.walletAfter());
        if (mapper.insertAudit(new CommerceAcceptanceSandboxMapper.SandboxAuditWrite(
                acceptanceRun.requireRunId(), eventId, result.orderNo(), actor, reason, result.event(), replay,
                result.canonicalStatus(), result.version(), result.walletAfter())) != 1) {
            throw new BizException(409, "COMMERCE_SANDBOX_AUDIT_CONFLICT");
        }
    }

    private Transition transition(String current, String event, boolean debited, boolean stockReturned, BigDecimal amount) {
        String state = StringUtils.hasText(current) ? current.trim().toUpperCase(Locale.ROOT) : "PENDING_PAYMENT";
        return switch (event) {
            case "PAYMENT_SUCCEEDED" -> require(state, "PENDING_PAYMENT", event,
                    new Transition("PAID", true, false, false, false, "PAID", "PAID", "WAITING_PROVISIONING"));
            case "PAYMENT_FAILED" -> require(state, "PENDING_PAYMENT", event,
                    new Transition("PAYMENT_FAILED", debited, true, false, !stockReturned, "FAILED", "PAYMENT_FAILED", "WAITING_PAYMENT"));
            case "EXPIRED" -> require(state, "PENDING_PAYMENT", event,
                    new Transition("EXPIRED", debited, true, false, !stockReturned, "EXPIRED", "EXPIRED", "WAITING_PAYMENT"));
            case "USER_CANCELLED" -> require(state, "PENDING_PAYMENT", event,
                    new Transition("CANCELLED", debited, true, false, !stockReturned, "CANCELLED", "CANCELLED", "WAITING_PAYMENT"));
            case "FULFILLMENT_SUCCEEDED" -> require(state, "PAID", event,
                    new Transition("ACTIVATED", true, stockReturned, false, false, "PAID", "COMPLETED", "ACTIVATED"));
            case "FULFILLMENT_FAILED" -> require(state, "PAID", event,
                    new Transition("PROVISIONING_FAILED", true, stockReturned, false, false, "PAID", "PROVISIONING_FAILED", "PROVISIONING_FAILED"));
            case "REFUNDED" -> {
                if (!Set.of("PAID", "ACTIVATED", "PROVISIONING_FAILED").contains(state)
                        || (!debited && money(amount).signum() > 0)) {
                    throw new BizException(409, "COMMERCE_SANDBOX_CALLBACK_STATE_CONFLICT");
                }
                yield new Transition("REFUNDED", false, true, true, !stockReturned,
                        "REFUNDED", "REFUNDED", "REFUNDED");
            }
            default -> throw new BizException(422, "COMMERCE_SANDBOX_CALLBACK_EVENT_INVALID");
        };
    }

    private Transition require(String actual, String expected, String event, Transition result) {
        if (!expected.equals(actual)) throw new BizException(409, "COMMERCE_SANDBOX_CALLBACK_STATE_CONFLICT");
        return result;
    }

    private void ledger(String runId, SandboxOrder order, String role, String direction, BigDecimal availableAfter,
                        BigDecimal reservedAfter, LocalDateTime now) {
        String no = "CSB-LG-" + sha256(order.orderNo() + "|" + role).substring(0, 32).toUpperCase(Locale.ROOT);
        if (fundsMapper.insertLedger(runId, new LedgerWrite(no, order.userId(), order.orderNo(), role, direction,
                order.amountUsdt(), availableAfter, reservedAfter, now)) != 1) {
            throw new BizException(409, "COMMERCE_SANDBOX_LEDGER_CONFLICT");
        }
    }

    private WalletRow wallet(WalletRow wallet) {
        if (wallet == null || wallet.version() == null || wallet.availableUsdt() == null || wallet.reservedUsdt() == null
                || wallet.availableUsdt().signum() < 0 || wallet.reservedUsdt().signum() < 0) {
            throw new BizException(503, "COMMERCE_SANDBOX_WALLET_UNAVAILABLE");
        }
        return wallet;
    }

    private InventoryRow inventory(InventoryRow inventory, SandboxOrder order) {
        if (inventory == null || inventory.version() == null || inventory.productId() == null
                || !inventory.productId().equals(order.productId()) || inventory.reservedQuantity() == null
                || inventory.reservedQuantity() != order.quantity() || inventory.releasedQuantity() == null
                || inventory.releasedQuantity() != 0 || inventory.unitPriceUsdt() == null
                || inventory.unitPriceUsdt().signum() <= 0) {
            throw new BizException(503, "COMMERCE_SANDBOX_INVENTORY_UNAVAILABLE");
        }
        return inventory;
    }

    private void requireEnabled() {
        if (!fundsSandboxProfileGuard.isLocalSandboxEnabled()) {
            throw new BizException(404, "COMMERCE_SANDBOX_DISABLED");
        }
    }

    private String requireEventId(String value) {
        String normalized = requireText(value, 128, "COMMERCE_SANDBOX_EVENT_ID_REQUIRED");
        if (!EVENT_ID.matcher(normalized).matches()) throw new BizException(422, "COMMERCE_SANDBOX_EVENT_ID_INVALID");
        return normalized;
    }

    private String requireEvent(String value) {
        String normalized = requireText(value, 64, "COMMERCE_SANDBOX_CALLBACK_EVENT_REQUIRED").toUpperCase(Locale.ROOT);
        if (!EVENTS.contains(normalized)) throw new BizException(422, "COMMERCE_SANDBOX_CALLBACK_EVENT_INVALID");
        return normalized;
    }

    private String requireText(String value, int max, String error) {
        if (!StringUtils.hasText(value) || value.trim().length() > max) throw new BizException(422, error);
        return value.trim();
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 6) throw new BizException(409, "COMMERCE_SANDBOX_AMOUNT_INVALID");
        return value.setScale(6, RoundingMode.UNNECESSARY);
    }

    private String canonical(String state) {
        return switch (state) {
            case "PENDING_PAYMENT" -> "placed";
            case "PAID" -> "paid";
            case "ACTIVATED" -> "activated";
            case "PAYMENT_FAILED" -> "payment_failed";
            case "EXPIRED" -> "expired";
            case "CANCELLED" -> "cancelled";
            case "PROVISIONING_FAILED" -> "provisioning_failed";
            case "REFUNDED" -> "refunded";
            default -> "placed";
        };
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Transition(String state, boolean walletDebited, boolean stockReturned, boolean credit, boolean returnStock,
                              String paymentStatus, String orderStatus, String activationStatus) {
        boolean debit(BigDecimal amount) { return "PAID".equals(state) && walletDebited && moneyPositive(amount); }
        boolean credit(BigDecimal amount) { return credit && moneyPositive(amount); }
        private static boolean moneyPositive(BigDecimal amount) { return amount != null && amount.signum() > 0; }
    }

    public record CallbackResult(String orderNo, String event, String canonicalStatus, Long version,
                                 String source, String sourceEnvironment, BigDecimal walletAfter) { }
}
