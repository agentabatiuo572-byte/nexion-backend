package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.cregis.CregisConstants;
import ffdd.opsconsole.finance.cregis.CregisGateway;
import ffdd.opsconsole.finance.cregis.CregisGatewayException;
import ffdd.opsconsole.finance.cregis.CregisGatewayRouter;
import ffdd.opsconsole.finance.cregis.CregisProperties;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WithdrawalPayoutExecutor {
    private final WithdrawalPayoutMapper mapper;
    private final CregisGatewayRouter router;
    private final WithdrawalPayoutFinalizer finalizer;

    @Scheduled(fixedDelayString = "${nexion.finance.withdrawal-payout-poll-ms:30000}")
    public void process() {
        CregisProperties.Mode mode = router.mode();
        // The local gateway is an acceptance probe only. It must never consume real wallet orders.
        if (mode == CregisProperties.Mode.LOCAL_SANDBOX) return;
        if (mode == CregisProperties.Mode.PROVIDER) {
            for (var submitted : mapper.incompleteProviderSubmissions(50)) reconcileProvider(submitted);
        }
        LocalDateTime now = LocalDateTime.now();
        for (var candidate : mapper.claimable(now, 50)) {
            if (mapper.claim(candidate.withdrawalNo(), now, now.plusMinutes(2)) != 1) continue;
            WithdrawalPayoutMapper.PayoutRow claimed = mapper.payout(candidate.withdrawalNo());
            if (claimed == null) continue;
            dispatch(claimed);
        }
    }

    private void reconcileProvider(WithdrawalPayoutMapper.PayoutRow row) {
        try {
            CregisGateway.PayoutOrder payout = router.provider().queryPayout(new CregisGateway.PayoutQuery(
                    row.providerCid(), row.providerIdempotencyKey(), row.targetAddress(), row.netReceive()));
            if ("TX_ORPHANED".equals(row.status())) {
                if (mapper.recoverOrphanToSent(row.withdrawalNo(), payout.cid(), payout.thirdPartyId(),
                        LocalDateTime.now()) != 1) return;
                row = mapper.payout(row.withdrawalNo());
                if (row == null || !"SENT".equals(row.status())) return;
            }
            int code = payout.status().providerCode();
            String payloadHash = sha(row.withdrawalNo() + "|QUERY|" + code + "|" + payout.txid());
            String eventNo = "CREGIS-QRY-" + payloadHash.substring(0, 48);
            if (code == 0 || code == 1 || code == 5 || code == 6) {
                finalizer.providerProgress(row, row.providerCid(), eventNo, payloadHash, code, payout.txid());
            } else if (code == 7 || payout.txid() != null
                    || mapper.hasProviderSuccessEvidence(row.withdrawalNo())) {
                finalizer.holdAmbiguousCallback(row, row.providerCid(), eventNo, payloadHash, code, payout.txid());
            } else {
                finalizer.terminal(row, row.providerCid(), "provider", eventNo, payloadHash,
                        "FAILED", null, "CREGIS_" + payout.status().name());
            }
        } catch (RuntimeException unavailable) {
            // SENT remains reserved and is durably selected again. Never infer failure from an RPC/provider outage.
        }
    }

    private void dispatch(WithdrawalPayoutMapper.PayoutRow row) {
        if (!"USDT-BEP20".equals(row.chain())) {
            finalizer.retry(row, "CREGIS_CHAIN_UNSUPPORTED");
            return;
        }
        CregisProperties.Mode mode = router.mode();
        if (mode == CregisProperties.Mode.DISABLED) {
            finalizer.retry(row, "CREGIS_PROVIDER_DISABLED");
            return;
        }
        String source = mode == CregisProperties.Mode.LOCAL_SANDBOX ? "mock" : "provider";
        CregisGateway gateway;
        try {
            gateway = mode == CregisProperties.Mode.LOCAL_SANDBOX
                    ? router.isolatedLocalSandbox() : router.provider();
            CregisGateway.PayoutSubmission submission = gateway.createPayout(new CregisGateway.PayoutRequest(
                    CregisConstants.USDT_BEP20_CURRENCY,
                    row.targetAddress(),
                    row.netReceive(),
                    row.providerIdempotencyKey(),
                    router.payoutCallbackUrl(),
                    "Nexion withdrawal " + row.withdrawalNo()));
            if (!row.providerIdempotencyKey().equals(submission.thirdPartyId())) {
                finalizer.orphaned(row, submission.cid(), source, "CREGIS_SUBMISSION_ID_MISMATCH");
                return;
            }
            if (!finalizer.submitted(row, submission.cid(), source)) return;
            if (mode == CregisProperties.Mode.LOCAL_SANDBOX) {
                finalizer.completeSandbox(row, submission.cid());
            }
        } catch (CregisGatewayException failure) {
            switch (failure.kind()) {
                case SUBMISSION_UNKNOWN, INVALID_RESPONSE, CONFLICT ->
                        finalizer.orphaned(row, null, source, failure.getMessage());
                case REJECTED, CONFIGURATION, UNAVAILABLE -> finalizer.retry(row, failure.getMessage());
            }
        } catch (RuntimeException unknown) {
            // A timeout or local failure after submission cannot be safely retried as a new business payout.
            // The stable provider key allows reconciliation; TX_ORPHANED makes the ambiguity explicit.
            finalizer.orphaned(row, null, source, "CREGIS_SUBMISSION_UNKNOWN");
        }
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
