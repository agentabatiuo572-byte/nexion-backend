package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.finance.domain.TopupProviderStatementReceipt;
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
import ffdd.opsconsole.shared.exception.BizException;
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

@Service
@RequiredArgsConstructor
public class TopupCardLifecycleService {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{2,127}");
    private static final Map<String, Set<String>> STATEMENT_PROVIDERS = Map.of(
            "USDT-TRC20", Set.of("TRON-NODE", "TRONGRID"),
            "USDT-ERC20", Set.of("ETHEREUM-NODE", "INFURA", "ALCHEMY"),
            "BTC", Set.of("BITCOIN-NODE", "BLOCKSTREAM"),
            "ETH", Set.of("ETHEREUM-NODE", "INFURA", "ALCHEMY"));
    private static final Set<String> STATEMENT_STATUSES = Set.of("PAID", "CONFIRMED", "SETTLED");
    private static final BigDecimal MAX_TOPUP = new BigDecimal("1000000");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @lombok.NonNull
    private final SupportPaymentAttributionFacade paymentAttribution;
    private final D1FinanceClosureMapper mapper;
    private final AuditLogService auditLogService;
    private final TreasuryLedgerRepository treasuryLedgerRepository;
    private final EventOutboxService eventOutboxService;
    private final PlatformConfigFacade configFacade;

    public TopupCardAdmissionResult admit(TopupCardAdmissionRequest input) {
        throw new BizException(410, "CARD_TOPUP_CHANNEL_RETIRED");
    }

    public TopupCardSettlementResult settle(TopupCardSettlementRequest input) {
        throw new BizException(410, "CARD_TOPUP_CHANNEL_RETIRED");
    }

    public TopupCardFailureResult recordFailure(TopupCardFailureRequest input) {
        throw new BizException(410, "CARD_TOPUP_CHANNEL_RETIRED");
    }

    public TopupCardChargebackResult recordChargeback(TopupCardChargebackRequest input) {
        throw new BizException(410, "CARD_TOPUP_CHANNEL_RETIRED");
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

    private NormalizedStatement normalizeStatement(TopupProviderStatementRequest input) {
        if (input == null) {
            throw new IllegalArgumentException("PROVIDER_STATEMENT_INVALID");
        }
        String channel = canonicalStatementChannel(input.channelCode());
        String eventId = safeId(input.ingestionEventId(), 128, "PROVIDER_INGESTION_EVENT_INVALID");
        String statementNo = safeId(input.statementNo(), 96, "PROVIDER_STATEMENT_NO_INVALID");
        String provider = text(input.provider());
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
            case "CARD" -> throw new BizException(410, "CARD_TOPUP_CHANNEL_RETIRED");
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

    private void requireSameHash(String expected, String actual, String error) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(error);
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
