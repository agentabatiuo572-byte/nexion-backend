package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.cregis.CregisCallbackVerifier;
import ffdd.opsconsole.finance.cregis.CregisGateway;
import ffdd.opsconsole.finance.cregis.CregisGatewayRouter;
import ffdd.opsconsole.finance.cregis.CregisProperties;
import ffdd.opsconsole.finance.cregis.CregisSigner;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

@Service
@RequiredArgsConstructor
public class WithdrawalPayoutCallbackService {
    private final WithdrawalPayoutMapper mapper;
    private final CregisProperties properties;
    private final CregisSigner signer;
    private final WithdrawalPayoutFinalizer finalizer;
    private final CregisGatewayRouter router;
    private final Clock clock;

    public ApiResult<Map<String, Object>> receive(Map<String, Object> callback) {
        if (properties.getMode() != CregisProperties.Mode.PROVIDER) {
            return ApiResult.fail(503, "CREGIS_PROVIDER_CALLBACK_DISABLED");
        }
        String providerKey = text(callback == null ? null : callback.get("third_party_id"));
        if (providerKey == null) return ApiResult.fail(422, "CREGIS_CALLBACK_SCHEMA_INVALID");
        WithdrawalPayoutMapper.PayoutRow row = mapper.payoutByProviderKey(providerKey);
        if (row == null || row.providerCid() == null || !"provider".equals(row.payoutSource())) {
            return ApiResult.fail(404, "CREGIS_PAYOUT_NOT_FOUND");
        }
        CregisCallbackVerifier verifier = new CregisCallbackVerifier(
                signer, properties.getProjectId(), properties.getApiKey(), clock, Duration.ofMinutes(5));
        CregisCallbackVerifier.Verification verification = verifier.verifyPayout(callback,
                new CregisCallbackVerifier.ExpectedPayout(row.providerCid(), row.providerIdempotencyKey(),
                        row.targetAddress(), row.netReceive()));
        if (!verification.valid()) return ApiResult.fail(401, "CREGIS_CALLBACK_" + verification.reason());

        int providerStatus = ((Number) callback.get("status")).intValue();
        String txid = text(callback.get("txid"));
        String payloadHash = callbackHash(callback);
        String eventNo = "CREGIS-CB-" + payloadHash.substring(0, 48);
        LocalDateTime now = LocalDateTime.now(clock);
        mapper.insertCallbackInbox(eventNo, row.withdrawalNo(), row.providerCid(), providerStatus, txid, payloadHash, now);
        String resultStatus = processOne(new WithdrawalPayoutMapper.CallbackInboxRow(
                eventNo, row.withdrawalNo(), row.providerCid(), providerStatus, txid, payloadHash), now);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("withdrawalNo", row.withdrawalNo());
        response.put("status", resultStatus);
        response.put("source", "provider");
        response.put("callbackVerified", true);
        return ApiResult.ok(response);
    }

    @Scheduled(fixedDelayString = "${nexion.finance.withdrawal-payout-callback-poll-ms:30000}")
    public void recoverInbox() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (var event : mapper.claimableCallbackInbox(now, 50)) processOne(event, now);
    }

    private String processOne(WithdrawalPayoutMapper.CallbackInboxRow event, LocalDateTime now) {
        if (mapper.claimCallbackInbox(event.eventNo(), now, now.plusMinutes(2)) != 1) return "ACCEPTED";
        try {
            WithdrawalPayoutMapper.PayoutRow row = mapper.payout(event.withdrawalNo());
            if (row == null || row.providerCid() == null || !row.providerCid().equals(event.providerCid())
                    || !"provider".equals(row.payoutSource())) {
                throw new IllegalStateException("CREGIS_PAYOUT_SNAPSHOT_MISSING");
            }
            int code = event.providerStatus();
            CregisGateway.PayoutStatus payoutStatus = CregisGateway.PayoutStatus.fromProviderCode(code);
            String result;
            if (code == 0 || code == 1 || code == 5) {
                finalizer.providerProgress(row, event.providerCid(), event.eventNo(), event.payloadHash(), code, event.txid());
                result = "PROVIDER_PROGRESS";
            } else if (code == 7 || (event.txid() != null && code != 6)
                    || (code != 6 && mapper.hasProviderSuccessEvidence(row.withdrawalNo()))) {
                finalizer.holdAmbiguousCallback(row, event.providerCid(), event.eventNo(),
                        event.payloadHash(), code, event.txid());
                result = "TX_ORPHANED";
            } else if (code == 6) {
                CregisGateway.PayoutOrder queried = router.provider().queryPayout(new CregisGateway.PayoutQuery(
                        event.providerCid(), row.providerIdempotencyKey(), row.targetAddress(), row.netReceive()));
                if (queried.status() != CregisGateway.PayoutStatus.SUCCEEDED
                        || queried.txid() == null || !queried.txid().equalsIgnoreCase(event.txid())) {
                    throw new IllegalStateException("CREGIS_SUCCESS_NOT_CONFIRMED_BY_QUERY");
                }
                // Provider success is not BSC finality. Until an RPC receipt/confirmation observer
                // proves the transaction canonical, keep the user's reserved balance untouched.
                finalizer.providerProgress(row, event.providerCid(), event.eventNo(), event.payloadHash(), code, event.txid());
                result = "CHAIN_CONFIRMATION_PENDING";
            } else {
                CregisGateway.PayoutOrder queried = router.provider().queryPayout(new CregisGateway.PayoutQuery(
                        event.providerCid(), row.providerIdempotencyKey(), row.targetAddress(), row.netReceive()));
                if (queried.status().providerCode() != code || queried.txid() != null) {
                    throw new IllegalStateException("CREGIS_FAILURE_NOT_CONFIRMED_PRE_BROADCAST");
                }
                terminalOrReplay(row, event, "FAILED", null, "CREGIS_" + payoutStatus.name());
                result = "FAILED";
            }
            mapper.finishCallbackInbox(event.eventNo(), LocalDateTime.now(clock));
            return result;
        } catch (RuntimeException failure) {
            mapper.retryCallbackInbox(event.eventNo(), safeError(failure), LocalDateTime.now(clock));
            return "ACCEPTED";
        }
    }

    private void terminalOrReplay(WithdrawalPayoutMapper.PayoutRow row,
                                  WithdrawalPayoutMapper.CallbackInboxRow event,
                                  String status, String txid, String failureReason) {
        if ("CONFIRMED".equals(row.status()) || "FAILED".equals(row.status())) {
            if (!status.equals(row.status())) throw new IllegalStateException("CREGIS_CALLBACK_TERMINAL_CONFLICT");
            finalizer.providerProgress(row, event.providerCid(), event.eventNo(), event.payloadHash(),
                    event.providerStatus(), txid);
            return;
        }
        if (!finalizer.terminal(row, event.providerCid(), "provider", event.eventNo(), event.payloadHash(),
                status, txid, failureReason)) {
            throw new IllegalStateException("CREGIS_CALLBACK_STATE_CONFLICT");
        }
    }

    private String safeError(RuntimeException failure) {
        String value = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private String callbackHash(Map<String, Object> callback) {
        String material = String.join("|",
                String.valueOf(callback.get("pid")), String.valueOf(callback.get("cid")),
                String.valueOf(callback.get("third_party_id")), String.valueOf(callback.get("status")),
                String.valueOf(callback.get("nonce")), String.valueOf(callback.get("timestamp")),
                String.valueOf(callback.get("txid")), String.valueOf(callback.get("sign")));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", impossible);
        }
    }

    private String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }
}
