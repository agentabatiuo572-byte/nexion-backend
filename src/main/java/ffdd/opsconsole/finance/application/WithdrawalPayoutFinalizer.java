package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import ffdd.opsconsole.finance.cregis.BscDepositProof;
import ffdd.opsconsole.finance.cregis.CregisConstants;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
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
    private final SupportWithdrawalFundSourceService withdrawalFunds;
    private final EventOutboxService outbox;

    /** Keep the same customer-first lock order as withdrawal submission. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public void lockCashCustomer(Long userId) {
        if (userId == null || !userId.equals(mapper.lockCashCustomer(userId)))
            throw new BizException(409,"提现账户暂不可处理，请复核后重试");
    }

    /** Called only after the existing D2 SENT CAS, inside that same transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean settleManualConfirmation(String withdrawalNo, Long userId, BigDecimal amount,
                                             BigDecimal nexBurned, LocalDateTime confirmedAt) {
        if (requiresProviderProof(withdrawalNo)) throw new BizException(409,"PROVIDER_CHAIN_CONFIRMATION_REQUIRED");
        lockCashCustomer(userId);
        if (!withdrawalFunds.hasCashReservation(withdrawalNo,userId,amount,nexBurned)) return false;
        var before = withdrawalFunds.begin(userId);
        settlePending(userId,amount,confirmedAt);
        withdrawalFunds.confirmed(before,withdrawalNo,confirmedAt);
        return true;
    }

    public boolean requiresProviderProof(String withdrawalNo) {
        var row = mapper.payout(withdrawalNo);
        return row != null && "provider".equals(row.payoutSource());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean providerDispatchStarted(String withdrawalNo, Long userId) {
        lockCashCustomer(userId);
        return "provider".equals(mapper.lockPayoutSource(withdrawalNo));
    }

    /** Only an independently queried canonical BSC Transfer may settle a provider payout. */
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmProviderChain(WithdrawalPayoutMapper.PayoutRow row, String txid,
                                        BscDepositProof.Proof proof, int requiredConfirmations) {
        if (proof == null || requiredConfirmations < 15 || proof.confirmations() < requiredConfirmations
                || !"USDT-BEP20".equals(row.chain()) || !"provider".equals(row.payoutSource())
                || row.providerCid() == null || !"SENT".equals(row.status())
                || txid == null || !txid.matches("(?i)(?:0x)?[0-9a-f]{64}"))
            throw new BizException(409,"CREGIS_CHAIN_PROOF_REQUIRED");
        txid = "0x" + txid.replaceFirst("(?i)^0x", "").toLowerCase(java.util.Locale.ROOT);
        String hash = sha(row.withdrawalNo() + "|" + row.providerCid() + "|" + txid
                + "|" + proof.blockHash() + "|" + proof.logIndex());
        String eventNo = "CREGIS-BSC-" + hash.substring(0,48);
        String existing = mapper.payoutLedgerPayloadHash(eventNo);
        if (existing != null) {
            if (!existing.equals(hash)) throw new BizException(409,"WITHDRAWAL_PAYOUT_EVENT_HASH_CONFLICT");
            return true;
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("D5_WITHDRAWAL_PAYOUT_CHAIN_PROOF").resourceType("WITHDRAWAL")
                .resourceId(row.withdrawalNo()).bizNo(row.withdrawalNo()).userId(row.userId())
                .actorType("SYSTEM").actorUsername("bsc-payout-observer").riskLevel("CRITICAL").result("SUCCESS")
                .detail(Map.of("txid",txid,"blockNumber",proof.blockNumber(),"blockHash",proof.blockHash(),
                        "logIndex",proof.logIndex(),"confirmations",proof.confirmations(),
                        "requiredConfirmations",requiredConfirmations,"recipient",row.targetAddress(),
                        "amount",row.netReceive(),"token",CregisConstants.USDT_BEP20_TOKEN_ID,
                        "providerCid",row.providerCid())).build());
        return terminalChecked(row,row.providerCid(),"provider",eventNo,hash,"CONFIRMED",txid,null);
    }

    private void settlePending(Long userId, BigDecimal gross, LocalDateTime at) {
        if (mapper.settlePending(userId,gross,at)!=1) throw new BizException(409,"WITHDRAWAL_PENDING_SETTLEMENT_CONFLICT");
    }

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

    /** Caller has queried the vendor using CID plus the original immutable payout snapshot. */
    @Transactional(rollbackFor = Exception.class)
    public boolean recoverProviderSubmission(WithdrawalPayoutMapper.PayoutRow row, long providerCid) {
        if (mapper.recoverOrphanToSent(row.withdrawalNo(),providerCid,row.providerIdempotencyKey(),
                LocalDateTime.now(clock)) != 1) return false;
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("D5_WITHDRAWAL_PAYOUT_SUBMISSION_RECOVERED").resourceType("WITHDRAWAL")
                .resourceId(row.withdrawalNo()).bizNo(row.withdrawalNo()).userId(row.userId())
                .actorType("SYSTEM").actorUsername("cregis-query-recovery").riskLevel("CRITICAL").result("SUCCESS")
                .detail(Map.of("providerCid",providerCid,"providerIdempotencyKey",row.providerIdempotencyKey())).build());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public String recoverProviderSubmission(WithdrawalPayoutMapper.PayoutRow expected, long providerCid,
                                             String actor, String reason) {
        lockCashCustomer(expected.userId());
        mapper.lockPayoutSource(expected.withdrawalNo());
        var current = mapper.payout(expected.withdrawalNo());
        if (current == null || !java.util.Objects.equals(expected.userId(), current.userId())
                || !"USDT-BEP20".equals(current.chain()) || !"provider".equals(current.payoutSource())
                || !java.util.Objects.equals(expected.targetAddress(), current.targetAddress())
                || !java.util.Objects.equals(expected.netReceive(), current.netReceive())
                || !java.util.Objects.equals(expected.amount(), current.amount())
                || !java.util.Objects.equals(expected.providerIdempotencyKey(), current.providerIdempotencyKey())
                || current.providerCid() != null && current.providerCid() != providerCid)
            throw new BizException(409, "CREGIS_PAYOUT_RECOVERY_STATE_CONFLICT");
        String state = current.status();
        if ("TX_ORPHANED".equals(state)) {
            if (!recoverProviderSubmission(current, providerCid))
                throw new BizException(409, "CREGIS_PAYOUT_RECOVERY_STATE_CONFLICT");
            state = "SENT";
        } else if (current.providerCid() == null || !java.util.Set.of("SENT", "CONFIRMED", "FAILED").contains(state)) {
            throw new BizException(409, "CREGIS_PAYOUT_RECOVERY_STATE_CONFLICT");
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("D2_CREGIS_PAYOUT_CID_RECOVERED").resourceType("WITHDRAWAL")
                .resourceId(current.withdrawalNo()).bizNo(current.withdrawalNo()).userId(current.userId())
                .actorType("ADMIN").actorUsername(actor).riskLevel("CRITICAL").result("SUCCESS")
                .detail(Map.of("providerCid", providerCid, "providerIdempotencyKey", current.providerIdempotencyKey(),
                        "reason", reason, "state", state)).build());
        return state;
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
    public boolean completeDevelopmentSimulation(WithdrawalPayoutMapper.PayoutRow row, long providerCid) {
        String txid = "0x" + sha("development:" + row.withdrawalNo());
        String source = "dev-simulator";
        String payloadHash = sha(row.withdrawalNo() + "|" + providerCid + "|" + source
                + "|SUCCEEDED|" + txid);
        return terminal(row, providerCid, source, "DEV-" + payloadHash.substring(0, 48),
                payloadHash, "CONFIRMED", txid, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean terminal(WithdrawalPayoutMapper.PayoutRow row, long providerCid, String source,
                            String eventNo, String payloadHash, String status, String txid, String failureReason) {
        if ("provider".equals(source) && "CONFIRMED".equals(status))
            throw new BizException(409,"CREGIS_CHAIN_PROOF_REQUIRED");
        return terminalChecked(row,providerCid,source,eventNo,payloadHash,status,txid,failureReason);
    }

    private boolean terminalChecked(WithdrawalPayoutMapper.PayoutRow row, long providerCid, String source,
                            String eventNo, String payloadHash, String status, String txid, String failureReason) {
        // Lock before the first consistent read, including under MySQL REPEATABLE_READ.
        lockCashCustomer(row.userId());
        String existingHash = mapper.payoutLedgerPayloadHash(eventNo);
        if (existingHash != null) {
            if (!existingHash.equals(payloadHash)) throw new BizException(409, "WITHDRAWAL_PAYOUT_EVENT_HASH_CONFLICT");
            return true;
        }
        if (!"CONFIRMED".equals(status) && !"FAILED".equals(status)) {
            throw new BizException(422, "WITHDRAWAL_PAYOUT_TERMINAL_STATUS_INVALID");
        }
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        if ("provider".equals(source) && "FAILED".equals(status)
                && mapper.hasProviderSuccessEvidence(row.withdrawalNo()))
            throw new BizException(409,"CREGIS_FAILURE_CONFLICTS_WITH_SUCCESS_EVIDENCE");
        if (!withdrawalFunds.hasCashReservation(row.withdrawalNo(),row.userId(),row.amount(),row.nexBurned()))
            throw new BizException(409,"该笔提现的预留资金尚未核实，请复核后重试");
        var fundBefore = productionSource(source) ? withdrawalFunds.begin(row.userId()) : null;
        if (mapper.insertPayoutLedger(eventNo, row.withdrawalNo(), providerCid, "CALLBACK", status,
                source, row.netReceive(), txid, payloadHash, now) != 1) {
            throw new BizException(409, "WITHDRAWAL_PAYOUT_CALLBACK_REPLAY_CONFLICT");
        }
        if (mapper.terminalOrder(row.withdrawalNo(), providerCid, status, txid,
                "FAILED".equals(status) ? safeError(failureReason) : null, now) != 1) {
            throw new BizException(409, "WITHDRAWAL_PAYOUT_TERMINAL_STATE_CONFLICT");
        }
        if ("CONFIRMED".equals(status)) {
            if ("BANK-VND".equals(row.chain()) && "hdpay".equals(source))
                ledger.settleBankWithdrawalReserve(row.withdrawalNo(), row.amount(), providerCid, now);
            settlePending(row.userId(),row.amount(),now);
            if(fundBefore!=null)withdrawalFunds.confirmed(fundBefore,row.withdrawalNo(),now);
        } else {
            if ("BANK-VND".equals(row.chain()) && "hdpay".equals(source))
                ledger.reverseLegacyBankWithdrawalReserve(row.withdrawalNo(), row.amount(), now);
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
            if(fundBefore!=null)withdrawalFunds.returned(fundBefore,row.withdrawalNo());
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("CONFIRMED".equals(status) ? "D5_WITHDRAWAL_PAYOUT_CONFIRMED" : "D5_WITHDRAWAL_PAYOUT_FAILED")
                .resourceType("WITHDRAWAL").resourceId(row.withdrawalNo()).bizNo(row.withdrawalNo())
                .userId(row.userId()).actorType("PROVIDER").actorUsername(source)
                .riskLevel("CRITICAL").result("CONFIRMED".equals(status) ? "SUCCESS" : "FAILED")
                .detail(Map.of("providerCid", providerCid, "source", source, "eventNo", eventNo,
                        "status", status, "txid", txid == null ? "" : txid)).build());
        if ("provider".equals(source)) {
            Map<String,Object> payload = new java.util.LinkedHashMap<>();
            payload.put("withdrawal_id",row.withdrawalNo()); payload.put("user_id",row.userId());
            payload.put("amount",row.amount()); payload.put("currency","USDT"); payload.put("state",status);
            payload.put("operator","cregis-payout");
            payload.put("reason","CONFIRMED".equals(status) ? "CREGIS_CHAIN_CONFIRMED" : safeError(failureReason));
            if ("CONFIRMED".equals(status)) {
                payload.put("chain_tx_hash",txid); payload.put("confirmed_at",now.toString());
            } else {
                payload.put("address_hash",sha(row.targetAddress().trim()));
                payload.put("risk_score",mapper.payoutRiskScore(row.withdrawalNo()));
            }
            if ("CONFIRMED".equals(status))
                outbox.publish("WITHDRAWAL",row.withdrawalNo(),"withdraw.confirmed",payload);
            else outbox.publish("WITHDRAWAL",row.withdrawalNo(),"withdraw.refunded",payload);
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean providerProgress(WithdrawalPayoutMapper.PayoutRow row, long providerCid,
                                    String eventNo, String payloadHash, int providerStatus, String txid) {
        // Serialize success evidence with a concurrent pre-broadcast failure refund.
        lockCashCustomer(row.userId());
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

    private boolean productionSource(String source) { return "provider".equals(source) || "hdpay".equals(source); }

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
