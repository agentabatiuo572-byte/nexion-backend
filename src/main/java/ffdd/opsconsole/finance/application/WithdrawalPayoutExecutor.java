package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.cregis.CregisConstants;
import ffdd.opsconsole.finance.cregis.CregisGateway;
import ffdd.opsconsole.finance.cregis.CregisGatewayException;
import ffdd.opsconsole.finance.cregis.CregisGatewayRouter;
import ffdd.opsconsole.finance.cregis.CregisProperties;
import ffdd.opsconsole.finance.cregis.BscDepositProof;
import ffdd.opsconsole.finance.cregis.CregisPayoutControl;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@Conditional(WithdrawalPayoutExecutor.RuntimeEnabled.class)
@RequiredArgsConstructor
public class WithdrawalPayoutExecutor {
    static final class RuntimeEnabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            return !env.acceptsProfiles(Profiles.of("dev")) || env.acceptsProfiles(Profiles.of("prod"))
                    || env.getProperty("nexion.deployment.public-test", Boolean.class, false);
        }
    }
    private final WithdrawalPayoutMapper mapper;
    private final CregisGatewayRouter router;
    private final WithdrawalPayoutFinalizer finalizer;
    private final BscDepositProof chain;
    private final CregisPayoutControl payouts;
    private final WithdrawalPayoutDispatchGuard dispatchGuard;
    private final java.util.concurrent.atomic.AtomicReference<String> providerObservationCursor =
            new java.util.concurrent.atomic.AtomicReference<>("");

    @Scheduled(fixedDelayString = "${nexion.finance.withdrawal-payout-poll-ms:30000}")
    public synchronized void process() {
        CregisProperties.Mode mode = router.mode();
        // The local gateway is an acceptance probe only. It must never consume real wallet orders.
        if (mode == CregisProperties.Mode.LOCAL_SANDBOX) return;
        if (mode == CregisProperties.Mode.PROVIDER) {
            for (var expired : mapper.expiredProviderAttempts(LocalDateTime.now(),50))
                finalizer.orphaned(expired,expired.providerCid(),"provider","CREGIS_SUBMISSION_UNKNOWN");
            var submitted = mapper.incompleteProviderSubmissions(providerObservationCursor.get(), 50);
            if (submitted.isEmpty() && !providerObservationCursor.get().isEmpty()) {
                providerObservationCursor.set("");
                submitted = mapper.incompleteProviderSubmissions(providerObservationCursor.get(), 50);
            }
            for (var row : submitted) {
                // Advance even when a provider/RPC observation fails, so pending orders cannot starve later ones.
                providerObservationCursor.set(row.withdrawalNo());
                reconcileProvider(row);
            }
        }
        // Existing submitted orders keep being observed when the operator closes new payouts.
        if (mode == CregisProperties.Mode.PROVIDER && !payouts.enabled()) return;
        LocalDateTime now = LocalDateTime.now();
        for (var candidate : mapper.claimable(now, 50)) {
            if(mode==CregisProperties.Mode.PROVIDER && "USDT-BEP20".equals(candidate.chain())) {
                WithdrawalPayoutMapper.PayoutRow claimed;
                try { claimed=dispatchGuard.begin(candidate,now); }
                catch(RuntimeException unavailable) { continue; } // No HTTP request starts on a failed admission.
                if(claimed!=null)dispatch(claimed,true);
                continue;
            }
            if (mapper.claimNewPayout(candidate.withdrawalNo(), now, now.plusMinutes(2)) != 1) continue;
            WithdrawalPayoutMapper.PayoutRow claimed = mapper.payout(candidate.withdrawalNo());
            if (claimed == null) continue;
            dispatch(claimed,false);
        }
    }

    private void reconcileProvider(WithdrawalPayoutMapper.PayoutRow row) {
        try {
            CregisGateway.PayoutOrder payout = router.provider().queryPayout(new CregisGateway.PayoutQuery(
                    row.providerCid(), row.providerIdempotencyKey(), row.targetAddress(), row.netReceive()));
            if ("TX_ORPHANED".equals(row.status())) {
                if (!finalizer.recoverProviderSubmission(row,payout.cid())) return;
                row = mapper.payout(row.withdrawalNo());
                if (row == null || !"SENT".equals(row.status())) return;
            }
            int code = payout.status().providerCode();
            String payloadHash = sha(row.withdrawalNo() + "|QUERY|" + code + "|" + payout.txid());
            String eventNo = "CREGIS-QRY-" + payloadHash.substring(0, 48);
            if (code == 0 || code == 1 || code == 5 || code == 6) {
                finalizer.providerProgress(row, row.providerCid(), eventNo, payloadHash, code, payout.txid());
                if (code == 6 && payout.txid() != null) {
                    var proof = chain.verifyPayout(payout.txid(),row.targetAddress(),row.netReceive());
                    if (proof.isPresent() && proof.get().confirmations() >= payouts.requiredConfirmations())
                        finalizer.confirmProviderChain(row,payout.txid(),proof.get(),payouts.requiredConfirmations());
                }
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

    private void dispatch(WithdrawalPayoutMapper.PayoutRow row,boolean providerIntentPersisted) {
        if (!"USDT-BEP20".equals(row.chain())) {
            finalizer.retry(row, "CREGIS_CHAIN_UNSUPPORTED");
            return;
        }
        CregisProperties.Mode mode = providerIntentPersisted?CregisProperties.Mode.PROVIDER:router.mode();
        if (mode == CregisProperties.Mode.DISABLED) {
            finalizer.retry(row, "CREGIS_PROVIDER_DISABLED");
            return;
        }
        if (!providerIntentPersisted && (!router.payoutEnabled() || (mode == CregisProperties.Mode.PROVIDER && !payouts.enabled()))) {
            finalizer.retry(row, "CREGIS_PAYOUT_DISABLED");
            return;
        }
        String source = mode == CregisProperties.Mode.LOCAL_SANDBOX ? "mock" : "provider";
        CregisGateway gateway;
        try {
            gateway = mode == CregisProperties.Mode.LOCAL_SANDBOX
                    ? router.isolatedLocalSandbox() : router.provider();
            // Provider intent has already committed under the shared gate; never claim it a second time.
            CregisGateway.PayoutSubmission submission = gateway.createPayout(new CregisGateway.PayoutRequest(
                    CregisConstants.USDT_BEP20_CURRENCY,
                    row.targetAddress(),
                    row.netReceive(),
                    row.providerIdempotencyKey(),
                    router.payoutCallbackUrl(),
                    "UVEL withdrawal " + row.withdrawalNo()));
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
