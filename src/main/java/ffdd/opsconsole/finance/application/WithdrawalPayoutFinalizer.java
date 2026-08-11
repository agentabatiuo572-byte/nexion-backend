package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WithdrawalPayoutFinalizer {
    private final WithdrawalPayoutMapper mapper;
    private final AuditLogService audit;
    private final TreasuryLedgerPostingFacade ledger;
    private final Clock clock;

    @Transactional(rollbackFor = Exception.class)
    public boolean submitted(WithdrawalPayoutMapper.PayoutRow row, long providerCid, String source) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (mapper.markSubmitted(row.withdrawalNo(), providerCid, source, now) != 1) return false;
        String payloadHash = sha(row.withdrawalNo() + "|" + providerCid + "|" + source + "|SUBMITTED");
        if (mapper.insertPayoutLedger("SUB-" + sha(row.withdrawalNo()).substring(0, 48),
                row.withdrawalNo(), providerCid, "SUBMITTED", "SENT", source,
                row.netReceive(), null, payloadHash, now) != 1) {
            throw new BizException(409, "WITHDRAWAL_PAYOUT_SUBMISSION_LEDGER_CONFLICT");
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("D5_WITHDRAWAL_PAYOUT_SUBMITTED").resourceType("WITHDRAWAL")
                .resourceId(row.withdrawalNo()).bizNo(row.withdrawalNo()).userId(row.userId())
                .actorType("SYSTEM").actorUsername("withdrawal-payout-executor")
                .riskLevel("CRITICAL").result("SUCCESS")
                .detail(Map.of("providerCid", providerCid, "providerIdempotencyKey", row.providerIdempotencyKey(),
                        "source", source, "amount", row.netReceive())).build());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean retry(WithdrawalPayoutMapper.PayoutRow row, String error) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (mapper.releaseRetry(row.withdrawalNo(), safeError(error), now) != 1) return false;
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("D5_WITHDRAWAL_PAYOUT_DEFERRED").resourceType("WITHDRAWAL")
                .resourceId(row.withdrawalNo()).bizNo(row.withdrawalNo()).userId(row.userId())
                .actorType("SYSTEM").actorUsername("withdrawal-payout-executor")
                .riskLevel("HIGH").result("FAILED")
                .detail(Map.of("error", safeError(error), "attempts", attempts(row),
                        "payoutDueAt", String.valueOf(row.payoutDueAt()))).build());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean orphaned(WithdrawalPayoutMapper.PayoutRow row, Long providerCid, String source, String error) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (mapper.markOrphaned(row.withdrawalNo(), providerCid, source, safeError(error), now) != 1) return false;
        long cid = providerCid == null ? 0L : providerCid;
        String hash = sha(row.withdrawalNo() + "|" + cid + "|" + source + "|ORPHANED|" + safeError(error));
        if (mapper.insertPayoutLedger("ORPH-" + hash.substring(0, 48), row.withdrawalNo(), cid,
                "SUBMISSION_UNKNOWN", "TX_ORPHANED", source, row.netReceive(), null, hash, now) != 1) {
            throw new BizException(409, "WITHDRAWAL_PAYOUT_ORPHAN_LEDGER_CONFLICT");
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("D5_WITHDRAWAL_PAYOUT_SUBMISSION_UNKNOWN").resourceType("WITHDRAWAL")
                .resourceId(row.withdrawalNo()).bizNo(row.withdrawalNo()).userId(row.userId())
                .actorType("SYSTEM").actorUsername("withdrawal-payout-executor")
                .riskLevel("CRITICAL").result("FAILED")
                .detail(Map.of("error", safeError(error), "providerCid", cid, "source", source)).build());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean completeSandbox(WithdrawalPayoutMapper.PayoutRow row, long providerCid) {
        String txid = "0x" + sha("sandbox:" + row.withdrawalNo());
        String payloadHash = sha(row.withdrawalNo() + "|" + providerCid + "|mock|SUCCEEDED|" + txid);
        return terminal(row, providerCid, "mock", "SBX-" + payloadHash.substring(0, 48),
                payloadHash, "CONFIRMED", txid, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean terminal(WithdrawalPayoutMapper.PayoutRow row, long providerCid, String source,
                            String eventNo, String payloadHash, String status, String txid, String failureReason) {
        String existingHash = mapper.payoutLedgerPayloadHash(eventNo);
        if (existingHash != null) {
            if (!existingHash.equals(payloadHash)) throw new BizException(409, "WITHDRAWAL_PAYOUT_EVENT_HASH_CONFLICT");
            return true;
        }
        if (!"CONFIRMED".equals(status) && !"FAILED".equals(status)) {
            throw new BizException(422, "WITHDRAWAL_PAYOUT_TERMINAL_STATUS_INVALID");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (mapper.insertPayoutLedger(eventNo, row.withdrawalNo(), providerCid, "CALLBACK", status,
                source, row.netReceive(), txid, payloadHash, now) != 1) {
            throw new BizException(409, "WITHDRAWAL_PAYOUT_CALLBACK_REPLAY_CONFLICT");
        }
        if (mapper.terminalOrder(row.withdrawalNo(), providerCid, status, txid,
                "FAILED".equals(status) ? safeError(failureReason) : null, now) != 1) {
            throw new BizException(409, "WITHDRAWAL_PAYOUT_TERMINAL_STATE_CONFLICT");
        }
        if ("CONFIRMED".equals(status)) {
            if (mapper.settlePending(row.userId(), row.amount(), now) != 1) {
                throw new BizException(409, "WITHDRAWAL_PENDING_SETTLEMENT_CONFLICT");
            }
        } else {
            BigDecimal nexBurned = row.nexBurned() == null ? BigDecimal.ZERO : row.nexBurned();
            if (mapper.refundPending(row.userId(), row.amount(), nexBurned, now) != 1) {
                throw new BizException(409, "WITHDRAWAL_PENDING_REFUND_CONFLICT");
            }
            ledger.postLedgerEntry(row.withdrawalNo() + ":PAYOUT:USDT:REFUND", row.userId(),
                    "WITHDRAW_PAYOUT_REFUND", "USDT", "IN", row.amount(), "POSTED",
                    "Provider payout failed; reserved withdrawal returned");
            if (nexBurned.signum() > 0) {
                ledger.postLedgerEntry(row.withdrawalNo() + ":PAYOUT:NEX:REFUND", row.userId(),
                        "WITHDRAW_PAYOUT_NEX_REFUND", "NEX", "IN", nexBurned, "POSTED",
                        "Provider payout failed; NEX fee offset returned");
            }
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("CONFIRMED".equals(status) ? "D5_WITHDRAWAL_PAYOUT_CONFIRMED" : "D5_WITHDRAWAL_PAYOUT_FAILED")
                .resourceType("WITHDRAWAL").resourceId(row.withdrawalNo()).bizNo(row.withdrawalNo())
                .userId(row.userId()).actorType("PROVIDER").actorUsername(source)
                .riskLevel("CRITICAL").result("CONFIRMED".equals(status) ? "SUCCESS" : "FAILED")
                .detail(Map.of("providerCid", providerCid, "source", source, "eventNo", eventNo,
                        "status", status, "txid", txid == null ? "" : txid)).build());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean providerProgress(WithdrawalPayoutMapper.PayoutRow row, long providerCid,
                                    String eventNo, String payloadHash, int providerStatus, String txid) {
        String existingHash = mapper.payoutLedgerPayloadHash(eventNo);
        if (existingHash != null) {
            if (!existingHash.equals(payloadHash)) throw new BizException(409, "WITHDRAWAL_PAYOUT_EVENT_HASH_CONFLICT");
            return true;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (mapper.insertPayoutLedger(eventNo, row.withdrawalNo(), providerCid, "PROVIDER_PROGRESS",
                "PROVIDER_" + providerStatus, "provider", row.netReceive(), txid, payloadHash, now) != 1) {
            throw new BizException(409, "WITHDRAWAL_PAYOUT_PROGRESS_REPLAY_CONFLICT");
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("D5_WITHDRAWAL_PAYOUT_PROVIDER_PROGRESS").resourceType("WITHDRAWAL")
                .resourceId(row.withdrawalNo()).bizNo(row.withdrawalNo()).userId(row.userId())
                .actorType("PROVIDER").actorUsername("provider").riskLevel("HIGH").result("SUCCESS")
                .detail(Map.of("providerCid", providerCid, "providerStatus", providerStatus,
                        "eventNo", eventNo, "txid", txid == null ? "" : txid)).build());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean holdAmbiguousCallback(WithdrawalPayoutMapper.PayoutRow row, long providerCid,
                                         String eventNo, String payloadHash, int providerStatus, String txid) {
        String existingHash = mapper.payoutLedgerPayloadHash(eventNo);
        if (existingHash != null) {
            if (!existingHash.equals(payloadHash)) throw new BizException(409, "WITHDRAWAL_PAYOUT_EVENT_HASH_CONFLICT");
            return true;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (mapper.insertPayoutLedger(eventNo, row.withdrawalNo(), providerCid, "AMBIGUOUS_CALLBACK",
                "TX_ORPHANED", "provider", row.netReceive(), txid, payloadHash, now) != 1) {
            throw new BizException(409, "WITHDRAWAL_PAYOUT_AMBIGUOUS_REPLAY_CONFLICT");
        }
        if (mapper.holdAmbiguousCallback(row.withdrawalNo(), providerCid,
                "CREGIS_FAILURE_WITH_TXID_STATUS_" + providerStatus, now) != 1) {
            throw new BizException(409, "WITHDRAWAL_PAYOUT_AMBIGUOUS_STATE_CONFLICT");
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("D5_WITHDRAWAL_PAYOUT_AMBIGUOUS_CALLBACK_HOLD").resourceType("WITHDRAWAL")
                .resourceId(row.withdrawalNo()).bizNo(row.withdrawalNo()).userId(row.userId())
                .actorType("PROVIDER").actorUsername("provider").riskLevel("CRITICAL").result("FAILED")
                .detail(Map.of("providerCid", providerCid, "providerStatus", providerStatus,
                        "eventNo", eventNo, "txid", txid == null ? "" : txid)).build());
        return true;
    }

    private int attempts(WithdrawalPayoutMapper.PayoutRow row) {
        return row.attempts() == null ? 1 : row.attempts() + 1;
    }

    private String safeError(String value) {
        String safe = value == null || value.isBlank() ? "CREGIS_PROVIDER_UNAVAILABLE" : value.trim();
        return safe.length() <= 120 ? safe : safe.substring(0, 120);
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", impossible);
        }
    }
}
