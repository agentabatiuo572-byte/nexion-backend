package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.domain.TopupAdmissionReceipt;
import ffdd.opsconsole.finance.domain.TopupFeeBufferSnapshot;
import ffdd.opsconsole.finance.domain.TopupChargebackEventReceipt;
import ffdd.opsconsole.finance.domain.TopupChargebackSource;
import ffdd.opsconsole.finance.domain.TopupFailureReceipt;
import ffdd.opsconsole.finance.domain.TopupProviderStatementReceipt;
import ffdd.opsconsole.finance.domain.TopupSettlementReceipt;
import ffdd.opsconsole.finance.domain.TopupRiskLockSnapshot;
import ffdd.opsconsole.finance.domain.TopupWalletSnapshot;
import ffdd.opsconsole.finance.dto.TopupCardAdmissionRequest;
import ffdd.opsconsole.finance.dto.TopupCardAdmissionResult;
import ffdd.opsconsole.finance.dto.TopupCardChargebackRequest;
import ffdd.opsconsole.finance.dto.TopupCardChargebackResult;
import ffdd.opsconsole.finance.dto.TopupCardFailureRequest;
import ffdd.opsconsole.finance.dto.TopupCardFailureResult;
import ffdd.opsconsole.finance.dto.TopupCardSettlementRequest;
import ffdd.opsconsole.finance.dto.TopupCardSettlementResult;
import ffdd.opsconsole.finance.dto.TopupProviderStatementRequest;
import ffdd.opsconsole.finance.dto.TopupProviderStatementResult;
import ffdd.opsconsole.finance.mapper.D1FinanceClosureMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
public class TopupCardLifecycleService {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{2,127}");
    private static final Pattern BIN = Pattern.compile("[0-9]{6,8}");
    private static final Pattern DEVICE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{7,127}");
    private static final Set<String> PROVIDERS = Set.of("CHECKOUT.COM", "STRIPE", "CARD");
    private static final Map<String, Set<String>> STATEMENT_PROVIDERS = Map.of(
            "Card", PROVIDERS,
            "USDT-TRC20", Set.of("TRON-NODE", "TRONGRID"),
            "USDT-ERC20", Set.of("ETHEREUM-NODE", "INFURA", "ALCHEMY"),
            "BTC", Set.of("BITCOIN-NODE", "BLOCKSTREAM"),
            "ETH", Set.of("ETHEREUM-NODE", "INFURA", "ALCHEMY"));
    private static final Set<String> STATEMENT_STATUSES = Set.of("PAID", "CONFIRMED", "SETTLED");
    private static final BigDecimal MAX_TOPUP = new BigDecimal("1000000");
    private static final BigDecimal MAX_FEE_RATE = new BigDecimal("10");
    private static final int ADMISSION_MINUTES = 10;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final D1FinanceClosureMapper mapper;
    private final AuditLogService auditLogService;
    private final TreasuryLedgerRepository treasuryLedgerRepository;
    private final EventOutboxService eventOutboxService;
    private final PlatformConfigFacade configFacade;

    @Transactional(rollbackFor = Exception.class)
    public TopupCardAdmissionResult admit(TopupCardAdmissionRequest input) {
        NormalizedAdmission request = normalizeAdmission(input);
        String hash = TopupEventHashing.sha256(request.canonical());
        TopupAdmissionReceipt existing = mapper.selectAdmissionForUpdate(request.eventId(), request.orderNo());
        if (existing != null) {
            requireSameHash(hash, existing.requestHash(), "ADMISSION_EVENT_CONFLICT");
            return admissionResult(existing, true);
        }

        String denial = firstRiskLockReason(request);
        String decision = denial == null ? "ALLOWED" : "DENIED";
        String reason = denial == null ? "RISK_CHECK_PASSED" : denial;
        LocalDateTime expiresAt = businessNow().plusMinutes(ADMISSION_MINUTES);
        if (mapper.insertAdmissionReceipt(
                request.eventId(), hash, request.orderNo(), request.userId(), request.provider(),
                request.amount(), request.feeAmount(), request.feeRate(), request.threeDsStatus(), request.cardBin(),
                request.clientIp(), request.deviceFingerprint(), decision, reason, expiresAt) != 1) {
            TopupAdmissionReceipt raced = mapper.selectAdmissionForUpdate(request.eventId(), request.orderNo());
            if (raced == null) {
                throw new IllegalStateException("ADMISSION_RECEIPT_WRITE_FAILED");
            }
            requireSameHash(hash, raced.requestHash(), "ADMISSION_EVENT_CONFLICT");
            return admissionResult(raced, true);
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("D1_CARD_TOPUP_ADMISSION_" + decision)
                .resourceType("CARD_TOPUP_ADMISSION")
                .resourceId(request.eventId())
                .bizNo(request.orderNo())
                .userId(request.userId())
                .actorType("PAYMENT_GATEWAY")
                .actorUsername("payment-gateway")
                .result("ALLOWED".equals(decision) ? "SUCCESS" : "DENIED")
                .riskLevel("ALLOWED".equals(decision) ? "INFO" : "HIGH")
                .detail(Map.ofEntries(
                        Map.entry("orderNo", request.orderNo()),
                        Map.entry("provider", request.provider()),
                        Map.entry("amountUsdt", request.amount().toPlainString()),
                        Map.entry("feeAmountUsdt", request.feeAmount().toPlainString()),
                        Map.entry("feeRatePct", request.feeRate().toPlainString()),
                        Map.entry("threeDsStatus", request.threeDsStatus()),
                        Map.entry("cardBin", request.cardBin()),
                        Map.entry("clientIp", request.clientIp()),
                        Map.entry("deviceFingerprint", request.deviceFingerprint()),
                        Map.entry("reason", reason),
                        Map.entry("expiresAt", expiresAt.toString())))
                .build());
        if ("ALLOWED".equals(decision)) {
            eventOutboxService.publish("WALLET", request.orderNo(), "wallet.topup_initiated", Map.of(
                    "transaction_id", request.orderNo(),
                    "user_id", request.userId(),
                    "amount", request.amount(),
                    "currency", "USDT",
                    "channel", "Card",
                    "topup_id", request.orderNo(),
                    "psp", request.provider(),
                    "three_ds_status", request.threeDsStatus()));
        }
        return new TopupCardAdmissionResult(
                request.eventId(), decision, reason, request.amount(), request.feeAmount(),
                request.feeRate(), expiresAt, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public TopupCardSettlementResult settle(TopupCardSettlementRequest input) {
        NormalizedSettlement request = normalizeSettlement(input);
        String hash = TopupEventHashing.sha256(request.canonical());
        TopupSettlementReceipt existing = mapper.selectSettlementForUpdate(request.eventId());
        if (existing != null) {
            requireSameHash(hash, existing.requestHash(), "SETTLEMENT_EVENT_CONFLICT");
            if (!"SETTLED".equals(existing.status())) {
                throw new IllegalStateException("SETTLEMENT_INCOMPLETE");
            }
            return settlementResult(existing, true);
        }

        TopupAdmissionReceipt admission = mapper.selectAdmissionForUpdate(request.admissionEventId(), request.orderNo());
        if (admission == null || !"ALLOWED".equals(admission.decision())) {
            throw new IllegalStateException("CARD_TOPUP_ADMISSION_REQUIRED");
        }
        if (!request.orderNo().equals(admission.orderNo()) || !request.userId().equals(admission.userId())) {
            throw new IllegalStateException("CARD_TOPUP_ADMISSION_MISMATCH");
        }
        if (!request.provider().equals(admission.provider())
                || request.amount().compareTo(admission.amountUsdt()) != 0
                || request.feeAmount().compareTo(admission.feeAmountUsdt()) != 0
                || request.feeRate().compareTo(admission.feeRatePct()) != 0) {
            throw new IllegalStateException("CARD_TOPUP_AUTHORIZATION_AMOUNT_MISMATCH");
        }
        if (admission.expiresAt() == null || !admission.expiresAt().isAfter(businessNow())) {
            throw new IllegalStateException("CARD_TOPUP_ADMISSION_EXPIRED");
        }
        if (StringUtils.hasText(admission.settlementEventId())
                && !request.eventId().equals(admission.settlementEventId())) {
            throw new IllegalStateException("CARD_TOPUP_ADMISSION_ALREADY_CONSUMED");
        }
        if (StringUtils.hasText(admission.failureEventId())) {
            throw new IllegalStateException("CARD_TOPUP_ADMISSION_ALREADY_FAILED");
        }
        String settlementRiskDenial = firstRiskLockReason(
                admission.cardBin(), admission.clientIp(), admission.deviceFingerprint());
        if (settlementRiskDenial != null) {
            throw new IllegalStateException(settlementRiskDenial);
        }
        if (mapper.insertSettlementReceipt(
                request.eventId(), hash, request.admissionEventId(), request.paymentNo(), request.orderNo(),
                request.userId(), request.provider(), request.providerPaymentId(), request.amount(),
                request.feeAmount(), request.feeRate()) != 1) {
            TopupSettlementReceipt raced = mapper.selectSettlementForUpdate(request.eventId());
            if (raced == null) {
                throw new IllegalStateException("SETTLEMENT_IDEMPOTENCY_CONFLICT");
            }
            requireSameHash(hash, raced.requestHash(), "SETTLEMENT_EVENT_CONFLICT");
            if (!"SETTLED".equals(raced.status())) {
                throw new IllegalStateException("SETTLEMENT_INCOMPLETE");
            }
            return settlementResult(raced, true);
        }
        if (mapper.consumeAdmission(request.admissionEventId(), request.eventId()) != 1) {
            throw new IllegalStateException("CARD_TOPUP_ADMISSION_CONFLICT");
        }

        TopupWalletSnapshot wallet = mapper.selectWalletForUpdate(request.userId());
        TopupFeeBufferSnapshot feeBuffer = mapper.selectFeeBufferForUpdate();
        if (wallet == null) {
            throw new IllegalStateException("CARD_TOPUP_WALLET_NOT_FOUND");
        }
        if (feeBuffer == null) {
            throw new IllegalStateException("TOPUP_FEE_BUFFER_NOT_INITIALIZED");
        }
        BigDecimal walletAfter = safe(wallet.usdtAvailable()).add(request.amount()).setScale(6, RoundingMode.HALF_UP);
        BigDecimal cumulativeAfter = safe(wallet.cumulativeDepositUsdt()).add(request.amount())
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal feeBufferAfter = safe(feeBuffer.balanceUsdt()).add(request.feeAmount())
                .setScale(6, RoundingMode.HALF_UP);
        if (mapper.updateWallet(request.userId(), walletAfter, cumulativeAfter, wallet.version()) != 1) {
            throw new IllegalStateException("CARD_TOPUP_WALLET_CONFLICT");
        }
        if (mapper.updateFeeBuffer(feeBufferAfter, feeBuffer.version()) != 1) {
            throw new IllegalStateException("TOPUP_FEE_BUFFER_CONFLICT");
        }
        if (mapper.insertSettledPayment(
                request.eventId(), request.admissionEventId(), request.paymentNo(), request.orderNo(),
                request.userId(), request.provider(), request.providerPaymentId(), request.amount(),
                request.feeAmount(), request.feeRate(), request.occurredAt()) != 1) {
            throw new IllegalStateException("CARD_TOPUP_PAYMENT_WRITE_FAILED");
        }
        mapper.insertCardTopupWalletLedger(
                request.userId(), request.paymentNo(), request.amount(), walletAfter,
                "银行卡充值入账 · provider=" + request.provider() + " · event=" + request.eventId());
        if (mapper.bindPaymentWalletLedger(request.paymentNo()) != 1) {
            throw new IllegalStateException("CARD_TOPUP_LEDGER_BINDING_FAILED");
        }
        if (request.feeAmount().compareTo(BigDecimal.ZERO) > 0) {
            mapper.insertFeeBufferCredit(
                compactKey("FEE-IN-", request.paymentNo(), 96), request.paymentNo(), request.feeAmount(),
                    feeBufferAfter, "银行卡充值手续费缓冲入账", "payment-gateway:" + request.provider(),
                    request.eventId());
        }
        treasuryLedgerRepository.recordTopupReserve(request.paymentNo(), request.amount(), request.eventId());
        eventOutboxService.publish("WALLET", request.paymentNo(), "wallet.topup_confirmed", Map.of(
                "transaction_id", request.paymentNo(),
                "user_id", request.userId(),
                "amount", request.amount(),
                "currency", "USDT",
                "channel", "Card",
                "topup_id", request.paymentNo(),
                "psp", request.provider()));
        if (mapper.completeSettlement(request.eventId(), walletAfter, cumulativeAfter, feeBufferAfter) != 1) {
            throw new IllegalStateException("SETTLEMENT_COMPLETION_CONFLICT");
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("D1_CARD_TOPUP_SETTLED")
                .resourceType("CARD_TOPUP_SETTLEMENT")
                .resourceId(request.eventId())
                .bizNo(request.paymentNo())
                .userId(request.userId())
                .actorType("PAYMENT_GATEWAY")
                .actorUsername("payment-gateway:" + request.provider())
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of(
                        "orderNo", request.orderNo(),
                        "providerPaymentId", request.providerPaymentId(),
                        "amountUsdt", request.amount().toPlainString(),
                        "feeAmountUsdt", request.feeAmount().toPlainString(),
                        "feeRatePct", request.feeRate().toPlainString(),
                        "walletBalanceAfter", walletAfter.toPlainString(),
                        "cumulativeDepositAfter", cumulativeAfter.toPlainString(),
                        "feeBufferBalanceAfter", feeBufferAfter.toPlainString()))
                .build());
        return new TopupCardSettlementResult(
                request.eventId(), request.paymentNo(), "SETTLED", walletAfter, cumulativeAfter,
                feeBufferAfter, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public TopupCardFailureResult recordFailure(TopupCardFailureRequest input) {
        NormalizedFailure request = normalizeFailure(input);
        String hash = TopupEventHashing.sha256(request.canonical());
        TopupFailureReceipt existing = mapper.selectFailureForUpdate(
                request.eventId(), request.paymentNo(), request.orderNo(),
                request.provider(), request.providerPaymentId());
        if (existing != null) {
            requireSameHash(hash, existing.requestHash(), "FAILURE_EVENT_CONFLICT");
            return new TopupCardFailureResult(
                    existing.failureEventId(), existing.paymentNo(), existing.status(), true);
        }

        TopupAdmissionReceipt admission = mapper.selectAdmissionForUpdate(
                request.admissionEventId(), request.orderNo());
        if (admission == null || !"ALLOWED".equals(admission.decision())) {
            throw new IllegalStateException("CARD_TOPUP_ADMISSION_REQUIRED");
        }
        if (!request.orderNo().equals(admission.orderNo())
                || !request.userId().equals(admission.userId())
                || !request.provider().equals(admission.provider())) {
            throw new IllegalStateException("CARD_TOPUP_ADMISSION_MISMATCH");
        }
        if (StringUtils.hasText(admission.settlementEventId())) {
            throw new IllegalStateException("CARD_TOPUP_ADMISSION_ALREADY_SETTLED");
        }
        if (StringUtils.hasText(admission.failureEventId())) {
            throw new IllegalStateException("CARD_TOPUP_ADMISSION_ALREADY_FAILED");
        }
        if (mapper.insertFailureReceipt(
                request.eventId(), hash, request.admissionEventId(), request.paymentNo(), request.orderNo(),
                request.userId(), request.provider(), request.providerPaymentId(), request.status(),
                request.reason(), request.occurredAt()) != 1) {
            TopupFailureReceipt raced = mapper.selectFailureForUpdate(
                    request.eventId(), request.paymentNo(), request.orderNo(),
                    request.provider(), request.providerPaymentId());
            if (raced == null) {
                throw new IllegalStateException("FAILURE_EVENT_WRITE_FAILED");
            }
            requireSameHash(hash, raced.requestHash(), "FAILURE_EVENT_CONFLICT");
            return new TopupCardFailureResult(
                    raced.failureEventId(), raced.paymentNo(), raced.status(), true);
        }
        if (mapper.failAdmission(request.admissionEventId(), request.eventId()) != 1) {
            throw new IllegalStateException("CARD_TOPUP_ADMISSION_CONFLICT");
        }
        if (mapper.insertFailedPayment(
                request.eventId(), request.admissionEventId(), request.paymentNo(), request.orderNo(),
                request.userId(), request.provider(), request.providerPaymentId(), request.status(),
                request.reason(), request.occurredAt()) != 1) {
            throw new IllegalStateException("CARD_TOPUP_FAILURE_WRITE_FAILED");
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("D1_CARD_TOPUP_" + request.status())
                .resourceType("CARD_TOPUP_FAILURE")
                .resourceId(request.eventId())
                .bizNo(request.paymentNo())
                .userId(request.userId())
                .actorType("PAYMENT_GATEWAY")
                .actorUsername("payment-gateway:" + request.provider())
                .result("DENIED")
                .riskLevel("HIGH")
                .detail(Map.of(
                        "admissionEventId", request.admissionEventId(),
                        "orderNo", request.orderNo(),
                        "providerPaymentId", request.providerPaymentId(),
                        "failureStatus", request.status(),
                        "failureReason", request.reason(),
                        "occurredAt", request.occurredAt().toString()))
                .build());
        return new TopupCardFailureResult(
                request.eventId(), request.paymentNo(), request.status(), false);
    }

    @Transactional(rollbackFor = Exception.class)
    public TopupCardChargebackResult recordChargeback(TopupCardChargebackRequest input) {
        NormalizedChargeback request = normalizeChargeback(input);
        String hash = TopupEventHashing.sha256(request.canonical());
        TopupChargebackEventReceipt existing = mapper.selectChargebackEventForUpdate(request.eventId());
        if (existing != null) {
            requireSameHash(hash, existing.requestHash(), "CHARGEBACK_EVENT_CONFLICT");
            return new TopupCardChargebackResult(
                    existing.chargebackEventId(), existing.paymentNo(), existing.status(), true);
        }
        TopupChargebackSource source = mapper.selectChargebackSourceForUpdate(
                request.paymentNo(), request.provider(), request.providerPaymentId());
        if (source == null
                || !request.orderNo().equals(source.orderNo())
                || !request.userId().equals(source.userId())
                || request.amount().compareTo(source.amountUsdt()) != 0) {
            throw new IllegalStateException("CHARGEBACK_PAYMENT_MISMATCH");
        }
        if (!Set.of("CONFIRMED", "PAID", "SUCCESS", "CHARGEBACK", "DISPUTED", "CHARGEBACK_REVIEW")
                .contains(source.status())) {
            throw new IllegalStateException("CHARGEBACK_PAYMENT_STATE_INVALID");
        }
        if (chargebackRank(request.status()) <= chargebackRank(source.status())
                || (source.latestChargebackOccurredAt() != null
                    && !request.occurredAt().isAfter(source.latestChargebackOccurredAt()))) {
            throw new IllegalStateException("CHARGEBACK_EVENT_OUT_OF_ORDER");
        }
        if (mapper.insertChargebackEvent(
                request.eventId(), hash, request.paymentNo(), request.orderNo(), request.userId(),
                request.provider(), request.providerPaymentId(), request.amount(), request.status(),
                request.reason(), request.evidenceRef(), request.occurredAt()) != 1) {
            TopupChargebackEventReceipt raced = mapper.selectChargebackEventForUpdate(request.eventId());
            if (raced == null) {
                throw new IllegalStateException("CHARGEBACK_EVENT_WRITE_FAILED");
            }
            requireSameHash(hash, raced.requestHash(), "CHARGEBACK_EVENT_CONFLICT");
            return new TopupCardChargebackResult(
                    raced.chargebackEventId(), raced.paymentNo(), raced.status(), true);
        }
        if (mapper.applyChargebackStatus(
                request.eventId(), request.paymentNo(), request.provider(), request.providerPaymentId(),
                source.status(), request.status(), request.reason()) != 1) {
            throw new IllegalStateException("CHARGEBACK_PAYMENT_STATE_CHANGED");
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("D1_CARD_TOPUP_" + request.status())
                .resourceType("CARD_TOPUP_CHARGEBACK")
                .resourceId(request.eventId())
                .bizNo(request.paymentNo())
                .userId(request.userId())
                .actorType("PAYMENT_GATEWAY")
                .actorUsername("payment-gateway:" + request.provider())
                .result("SUCCESS")
                .riskLevel("CRITICAL")
                .detail(Map.of(
                        "orderNo", request.orderNo(),
                        "providerPaymentId", request.providerPaymentId(),
                        "amountUsdt", request.amount().toPlainString(),
                        "chargebackStatus", request.status(),
                        "chargebackReason", request.reason(),
                        "evidenceRef", request.evidenceRef(),
                        "occurredAt", request.occurredAt().toString()))
                .build());
        return new TopupCardChargebackResult(
                request.eventId(), request.paymentNo(), request.status(), false);
    }

    @Transactional(rollbackFor = Exception.class)
    public TopupProviderStatementResult ingestProviderStatement(TopupProviderStatementRequest input) {
        NormalizedStatement request = normalizeStatement(input);
        String hash = TopupEventHashing.sha256(request.canonical());
        int inserted = mapper.insertProviderStatement(
                request.eventId(), hash, request.statementNo(), request.provider(), request.channelCode(),
                request.providerReference(), request.userId(), request.amount(), request.status(),
                request.evidenceRef(), request.observedAt());
        if (inserted != 1) {
            TopupProviderStatementReceipt existing = mapper.selectProviderStatementForUpdate(
                    request.eventId(), request.statementNo(), request.provider(), request.providerReference());
            if (existing == null) {
                throw new IllegalStateException("PROVIDER_STATEMENT_WRITE_FAILED");
            }
            requireSameHash(hash, existing.payloadHash(), "PROVIDER_STATEMENT_EVENT_CONFLICT");
            return new TopupProviderStatementResult(
                    existing.ingestionEventId(), existing.statementNo(), existing.statementStatus(), true);
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("D1_PROVIDER_STATEMENT_INGESTED")
                .resourceType("PROVIDER_STATEMENT")
                .resourceId(request.statementNo())
                .bizNo(request.providerReference())
                .userId(request.userId())
                .actorType("PROVIDER_STATEMENT_ADAPTER")
                .actorUsername("provider-statement:" + request.provider())
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(Map.of(
                        "ingestionEventId", request.eventId(),
                        "channelCode", request.channelCode(),
                        "amountUsdt", request.amount().toPlainString(),
                        "statementStatus", request.status(),
                        "evidenceRef", request.evidenceRef(),
                        "observedAt", request.observedAt().toString()))
                .build());
        return new TopupProviderStatementResult(
                request.eventId(), request.statementNo(), request.status(), false);
    }

    private String firstRiskLockReason(NormalizedAdmission request) {
        return firstRiskLockReason(request.cardBin(), request.clientIp(), request.deviceFingerprint());
    }

    private String firstRiskLockReason(String cardBin, String clientIp, String deviceFingerprint) {
        TopupRiskLockSnapshot lock = mapper.selectRiskLockForUpdate("BIN", cardBin);
        if (!active(lock)) {
            lock = mapper.selectRiskLockForUpdate("IP", clientIp);
        }
        if (!active(lock)) {
            lock = mapper.selectRiskLockForUpdate("DEVICE", deviceFingerprint);
        }
        return active(lock) ? "RISK_LOCK_ACTIVE: " + lock.targetType() + ": " + lock.reason() : null;
    }

    private boolean active(TopupRiskLockSnapshot lock) {
        return lock != null && lock.active();
    }

    private int chargebackRank(String status) {
        return switch (status) {
            case "CONFIRMED", "PAID", "SUCCESS" -> 0;
            case "DISPUTED" -> 1;
            case "CHARGEBACK_REVIEW" -> 2;
            case "CHARGEBACK" -> 3;
            default -> -1;
        };
    }

    private TopupCardAdmissionResult admissionResult(TopupAdmissionReceipt receipt, boolean replay) {
        return new TopupCardAdmissionResult(
                receipt.admissionEventId(), receipt.decision(), receipt.reason(), receipt.amountUsdt(),
                receipt.feeAmountUsdt(), receipt.feeRatePct(), receipt.expiresAt(), replay);
    }

    private TopupCardSettlementResult settlementResult(TopupSettlementReceipt receipt, boolean replay) {
        return new TopupCardSettlementResult(
                receipt.settlementEventId(), receipt.paymentNo(), receipt.status(),
                receipt.walletBalanceAfter(), receipt.cumulativeDepositAfter(),
                receipt.feeBufferBalanceAfter(), replay);
    }

    private NormalizedAdmission normalizeAdmission(TopupCardAdmissionRequest input) {
        if (input == null || input.userId() == null || input.userId() < 1) {
            throw new IllegalArgumentException("CARD_ADMISSION_USER_INVALID");
        }
        String eventId = safeId(input.admissionEventId(), 128, "CARD_ADMISSION_EVENT_INVALID");
        String orderNo = safeId(input.orderNo(), 96, "CARD_ADMISSION_ORDER_INVALID");
        String bin = text(input.cardBin());
        String ip = text(input.clientIp());
        String device = text(input.deviceFingerprint());
        if (!BIN.matcher(bin).matches()) {
            throw new IllegalArgumentException("CARD_BIN_INVALID");
        }
        if (!validIp(ip)) {
            throw new IllegalArgumentException("CARD_CLIENT_IP_INVALID");
        }
        if (!DEVICE.matcher(device).matches()) {
            throw new IllegalArgumentException("CARD_DEVICE_FINGERPRINT_INVALID");
        }
        String provider = text(input.provider());
        if (!PROVIDERS.contains(provider.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("CARD_PROVIDER_INVALID");
        }
        if (!configBoolean("finance.topup.channel.card.enabled", true)) {
            throw new IllegalStateException("CARD_TOPUP_CHANNEL_DISABLED");
        }
        String configuredProvider = configText("finance.topup.psp.primary", "Checkout.com");
        if (!provider.equals(configuredProvider)) {
            throw new IllegalStateException("CARD_PROVIDER_ROUTE_CHANGED");
        }
        BigDecimal amount = positiveMoney(input.amountUsdt(), MAX_TOPUP, "CARD_ADMISSION_AMOUNT_INVALID");
        BigDecimal minAmount = configDecimal("finance.topup.channel.card.min_amount", new BigDecimal("10"));
        if (amount.compareTo(minAmount) < 0) {
            throw new IllegalArgumentException("CARD_ADMISSION_BELOW_MIN_AMOUNT");
        }
        BigDecimal rate = configDecimal("finance.topup.channel.card.fee", new BigDecimal("3.5"));
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(MAX_FEE_RATE) > 0) {
            throw new IllegalStateException("CARD_FEE_CONFIG_INVALID");
        }
        rate = rate.setScale(6, RoundingMode.UNNECESSARY);
        BigDecimal fee = amount.multiply(rate).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        String threeDsStatus = text(input.threeDsStatus()).toUpperCase(Locale.ROOT);
        if (!Set.of("AUTHENTICATED", "EXEMPTED").contains(threeDsStatus)) {
            throw new IllegalArgumentException("CARD_3DS_STATUS_INVALID");
        }
        BigDecimal threeDsThreshold = configDecimal(
                "finance.topup.card.threeDsThreshold", new BigDecimal("50"));
        if (amount.compareTo(threeDsThreshold) >= 0 && !"AUTHENTICATED".equals(threeDsStatus)) {
            throw new IllegalStateException("CARD_3DS_AUTHENTICATION_REQUIRED");
        }
        return new NormalizedAdmission(
                eventId, orderNo, input.userId(), provider, amount, fee, rate, threeDsStatus,
                bin, ip.toLowerCase(Locale.ROOT), device);
    }

    private NormalizedSettlement normalizeSettlement(TopupCardSettlementRequest input) {
        if (input == null || input.userId() == null || input.userId() < 1) {
            throw new IllegalArgumentException("CARD_SETTLEMENT_USER_INVALID");
        }
        String eventId = safeId(input.settlementEventId(), 128, "CARD_SETTLEMENT_EVENT_INVALID");
        String admissionId = safeId(input.admissionEventId(), 128, "CARD_ADMISSION_EVENT_INVALID");
        String paymentNo = safeId(input.paymentNo(), 96, "CARD_PAYMENT_NO_INVALID");
        String orderNo = safeId(input.orderNo(), 96, "CARD_SETTLEMENT_ORDER_INVALID");
        String provider = text(input.provider());
        if (!PROVIDERS.contains(provider.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("CARD_PROVIDER_INVALID");
        }
        String providerPaymentId = safeId(input.providerPaymentId(), 128, "CARD_PROVIDER_PAYMENT_ID_INVALID");
        BigDecimal amount = positiveMoney(input.amountUsdt(), MAX_TOPUP, "CARD_SETTLEMENT_AMOUNT_INVALID");
        BigDecimal fee = nonNegativeMoney(input.feeAmountUsdt(), amount, "CARD_SETTLEMENT_FEE_INVALID");
        BigDecimal rate = nonNegativeMoney(input.feeRatePct(), MAX_FEE_RATE, "CARD_SETTLEMENT_FEE_RATE_INVALID");
        BigDecimal expectedFee = amount.multiply(rate).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        if (expectedFee.subtract(fee).abs().compareTo(new BigDecimal("0.000001")) > 0) {
            throw new IllegalArgumentException("CARD_SETTLEMENT_FEE_MISMATCH");
        }
        LocalDateTime occurredAt = input.occurredAt();
        LocalDateTime now = businessNow();
        if (occurredAt == null || occurredAt.isAfter(now.plusMinutes(5)) || occurredAt.isBefore(now.minusDays(7))) {
            throw new IllegalArgumentException("CARD_SETTLEMENT_OCCURRED_AT_INVALID");
        }
        return new NormalizedSettlement(
                eventId, admissionId, paymentNo, orderNo, input.userId(), provider,
                providerPaymentId, amount, fee, rate, occurredAt);
    }

    private NormalizedStatement normalizeStatement(TopupProviderStatementRequest input) {
        if (input == null) {
            throw new IllegalArgumentException("PROVIDER_STATEMENT_INVALID");
        }
        String eventId = safeId(input.ingestionEventId(), 128, "PROVIDER_INGESTION_EVENT_INVALID");
        String statementNo = safeId(input.statementNo(), 96, "PROVIDER_STATEMENT_NO_INVALID");
        String provider = text(input.provider());
        String channel = canonicalStatementChannel(input.channelCode());
        Set<String> allowedProviders = STATEMENT_PROVIDERS.get(channel);
        if (allowedProviders == null || !allowedProviders.contains(provider.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("PROVIDER_STATEMENT_PROVIDER_CHANNEL_INVALID");
        }
        String providerReference = safeId(input.providerReference(), 128, "PROVIDER_REFERENCE_INVALID");
        BigDecimal amount = positiveMoney(input.amountUsdt(), MAX_TOPUP, "PROVIDER_STATEMENT_AMOUNT_INVALID");
        String status = text(input.statementStatus()).toUpperCase(Locale.ROOT);
        if (!STATEMENT_STATUSES.contains(status)) {
            throw new IllegalArgumentException("PROVIDER_STATEMENT_STATUS_INVALID");
        }
        String evidence = text(input.evidenceRef());
        if (evidence.length() < 8 || evidence.length() > 128) {
            throw new IllegalArgumentException("PROVIDER_STATEMENT_EVIDENCE_INVALID");
        }
        LocalDateTime observedAt = input.observedAt();
        LocalDateTime now = businessNow();
        if (observedAt == null || observedAt.isAfter(now.plusMinutes(5)) || observedAt.isBefore(now.minusDays(31))) {
            throw new IllegalArgumentException("PROVIDER_STATEMENT_OBSERVED_AT_INVALID");
        }
        if (input.userId() != null && input.userId() < 1) {
            throw new IllegalArgumentException("PROVIDER_STATEMENT_USER_INVALID");
        }
        return new NormalizedStatement(
                eventId, statementNo, provider, channel, providerReference, input.userId(), amount,
                status, evidence, observedAt);
    }

    private NormalizedFailure normalizeFailure(TopupCardFailureRequest input) {
        if (input == null || input.userId() == null || input.userId() < 1) {
            throw new IllegalArgumentException("CARD_FAILURE_USER_INVALID");
        }
        String eventId = safeId(input.failureEventId(), 128, "CARD_FAILURE_EVENT_INVALID");
        String admissionId = safeId(input.admissionEventId(), 128, "CARD_ADMISSION_EVENT_INVALID");
        String paymentNo = safeId(input.paymentNo(), 96, "CARD_PAYMENT_NO_INVALID");
        String orderNo = safeId(input.orderNo(), 96, "CARD_FAILURE_ORDER_INVALID");
        String provider = text(input.provider());
        if (!PROVIDERS.contains(provider.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("CARD_PROVIDER_INVALID");
        }
        String providerPaymentId = safeId(
                input.providerPaymentId(), 128, "CARD_PROVIDER_PAYMENT_ID_INVALID");
        String status = text(input.failureStatus()).toUpperCase(Locale.ROOT);
        if (!Set.of("FAILED", "DECLINED", "EXPIRED").contains(status)) {
            throw new IllegalArgumentException("CARD_FAILURE_STATUS_INVALID");
        }
        String reason = text(input.failureReason());
        if (reason.length() < 8 || reason.length() > 255) {
            throw new IllegalArgumentException("CARD_FAILURE_REASON_INVALID");
        }
        LocalDateTime occurredAt = input.occurredAt();
        LocalDateTime now = businessNow();
        if (occurredAt == null || occurredAt.isAfter(now.plusMinutes(5))
                || occurredAt.isBefore(now.minusDays(7))) {
            throw new IllegalArgumentException("CARD_FAILURE_OCCURRED_AT_INVALID");
        }
        return new NormalizedFailure(
                eventId, admissionId, paymentNo, orderNo, input.userId(), provider,
                providerPaymentId, status, reason, occurredAt);
    }

    private NormalizedChargeback normalizeChargeback(TopupCardChargebackRequest input) {
        if (input == null || input.userId() == null || input.userId() < 1) {
            throw new IllegalArgumentException("CHARGEBACK_USER_INVALID");
        }
        String eventId = safeId(input.chargebackEventId(), 128, "CHARGEBACK_EVENT_INVALID");
        String paymentNo = safeId(input.paymentNo(), 96, "CHARGEBACK_PAYMENT_NO_INVALID");
        String orderNo = safeId(input.orderNo(), 96, "CHARGEBACK_ORDER_NO_INVALID");
        String provider = text(input.provider());
        if (!PROVIDERS.contains(provider.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("CARD_PROVIDER_INVALID");
        }
        String providerPaymentId = safeId(
                input.providerPaymentId(), 128, "CARD_PROVIDER_PAYMENT_ID_INVALID");
        BigDecimal amount = positiveMoney(
                input.amountUsdt(), MAX_TOPUP, "CHARGEBACK_AMOUNT_INVALID");
        String status = text(input.chargebackStatus()).toUpperCase(Locale.ROOT);
        if (!Set.of("CHARGEBACK", "DISPUTED", "CHARGEBACK_REVIEW").contains(status)) {
            throw new IllegalArgumentException("CHARGEBACK_STATUS_INVALID");
        }
        String reason = text(input.chargebackReason());
        if (reason.length() < 8 || reason.length() > 255) {
            throw new IllegalArgumentException("CHARGEBACK_REASON_INVALID");
        }
        String evidence = text(input.evidenceRef());
        if (evidence.length() < 8 || evidence.length() > 128) {
            throw new IllegalArgumentException("CHARGEBACK_EVIDENCE_INVALID");
        }
        LocalDateTime occurredAt = input.occurredAt();
        LocalDateTime now = businessNow();
        if (occurredAt == null || occurredAt.isAfter(now.plusMinutes(5))
                || occurredAt.isBefore(now.minusDays(120))) {
            throw new IllegalArgumentException("CHARGEBACK_OCCURRED_AT_INVALID");
        }
        return new NormalizedChargeback(
                eventId, paymentNo, orderNo, input.userId(), provider, providerPaymentId,
                amount, status, reason, evidence, occurredAt);
    }

    private BigDecimal positiveMoney(BigDecimal value, BigDecimal max, String error) {
        if (value == null || value.scale() > 6 || value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(error);
        }
        return value.setScale(6, RoundingMode.UNNECESSARY);
    }

    private String canonicalStatementChannel(String value) {
        String normalized = safeId(value, 64, "PROVIDER_STATEMENT_CHANNEL_INVALID")
                .toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CARD" -> "Card";
            case "USDT-TRC20", "TRC20" -> "USDT-TRC20";
            case "USDT-ERC20", "ERC20" -> "USDT-ERC20";
            case "BTC" -> "BTC";
            case "ETH" -> "ETH";
            default -> throw new IllegalArgumentException("PROVIDER_STATEMENT_CHANNEL_INVALID");
        };
    }

    private LocalDateTime businessNow() {
        return LocalDateTime.now(BUSINESS_ZONE);
    }

    private BigDecimal nonNegativeMoney(BigDecimal value, BigDecimal max, String error) {
        if (value == null || value.scale() > 6 || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(error);
        }
        return value.setScale(6, RoundingMode.UNNECESSARY);
    }

    private String safeId(String value, int maxLength, String error) {
        String normalized = text(value);
        if (normalized.length() > maxLength || !SAFE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(error);
        }
        return normalized;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String configText(String key, String fallback) {
        return configFacade.activeValue(key).map(String::trim).filter(StringUtils::hasText).orElse(fallback);
    }

    private BigDecimal configDecimal(String key, BigDecimal fallback) {
        try {
            return new BigDecimal(configText(key, fallback.toPlainString()));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("CARD_CONFIG_INVALID: " + key);
        }
    }

    private boolean configBoolean(String key, boolean fallback) {
        String value = configText(key, String.valueOf(fallback));
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalStateException("CARD_CONFIG_INVALID: " + key);
        }
        return Boolean.parseBoolean(value);
    }

    private boolean validIp(String value) {
        if (value == null || value.length() < 3 || value.length() > 64 || value.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        if (value.indexOf(':') >= 0) {
            return value.matches("[0-9A-Fa-f:.%]+");
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                if (part.isEmpty() || Integer.parseInt(part) > 255 || (part.length() > 1 && part.startsWith("0"))) {
                    return false;
                }
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return true;
    }

    private void requireSameHash(String expected, String actual, String error) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(error);
        }
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private String safeBiz(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    private String compactKey(String prefix, String value, int maxLength) {
        String candidate = prefix + safeBiz(value);
        if (candidate.length() <= maxLength) {
            return candidate;
        }
        return prefix + TopupEventHashing.sha256(value).substring(0, maxLength - prefix.length());
    }

    private record NormalizedAdmission(
            String eventId, String orderNo, Long userId, String provider, BigDecimal amount,
            BigDecimal feeAmount, BigDecimal feeRate, String threeDsStatus, String cardBin,
            String clientIp, String deviceFingerprint) {
        String canonical() {
            return String.join("|", eventId, orderNo, String.valueOf(userId), provider,
                    amount.toPlainString(), feeAmount.toPlainString(), feeRate.toPlainString(), threeDsStatus,
                    cardBin, clientIp, deviceFingerprint);
        }
    }

    private record NormalizedSettlement(
            String eventId, String admissionEventId, String paymentNo, String orderNo, Long userId,
            String provider, String providerPaymentId, BigDecimal amount, BigDecimal feeAmount,
            BigDecimal feeRate, LocalDateTime occurredAt) {
        String canonical() {
            return String.join("|", eventId, admissionEventId, paymentNo, orderNo, String.valueOf(userId),
                    provider, providerPaymentId, amount.toPlainString(), feeAmount.toPlainString(),
                    feeRate.toPlainString(), occurredAt.toString());
        }
    }

    private record NormalizedFailure(
            String eventId, String admissionEventId, String paymentNo, String orderNo, Long userId,
            String provider, String providerPaymentId, String status, String reason,
            LocalDateTime occurredAt) {
        String canonical() {
            return String.join("|", eventId, admissionEventId, paymentNo, orderNo, String.valueOf(userId),
                    provider, providerPaymentId, status, reason, occurredAt.toString());
        }
    }

    private record NormalizedChargeback(
            String eventId, String paymentNo, String orderNo, Long userId, String provider,
            String providerPaymentId, BigDecimal amount, String status, String reason,
            String evidenceRef, LocalDateTime occurredAt) {
        String canonical() {
            return String.join("|", eventId, paymentNo, orderNo, String.valueOf(userId), provider,
                    providerPaymentId, amount.toPlainString(), status, reason, evidenceRef,
                    occurredAt.toString());
        }
    }

    private record NormalizedStatement(
            String eventId, String statementNo, String provider, String channelCode,
            String providerReference, Long userId, BigDecimal amount, String status,
            String evidenceRef, LocalDateTime observedAt) {
        String canonical() {
            return String.join("|", eventId, statementNo, provider, channelCode, providerReference,
                    String.valueOf(userId), amount.toPlainString(), status, evidenceRef, observedAt.toString());
        }
    }
}
