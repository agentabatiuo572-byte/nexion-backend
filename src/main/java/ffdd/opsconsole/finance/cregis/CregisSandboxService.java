package ffdd.opsconsole.finance.cregis;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public final class CregisSandboxService {
    private static final long LOCAL_PID = 9_001L;
    /** Non-secret test material. It is accepted only by the in-process local sandbox verifier. */
    private static final String LOCAL_CALLBACK_KEY = "nexion-cregis-local-sandbox-only";

    private final CregisGatewayRouter router;
    private final CregisSigner signer;
    private final Clock clock;

    public SandboxOverview overview() {
        boolean local = router.mode() == CregisProperties.Mode.LOCAL_SANDBOX;
        return new SandboxOverview(
                router.mode().name(),
                local,
                false,
                false,
                "USDT-BEP20");
    }

    public ProbeResult runProbe() {
        // A fresh simulator instance per probe prevents probe data becoming shared state
        // and prevents the local harness from asserting provider replay guarantees.
        CregisGateway gateway = router.isolatedLocalSandbox();
        CregisGateway.Coin usdt = gateway.projectCoins().stream()
                .filter(coin -> CregisConstants.USDT_BEP20_CURRENCY.equalsIgnoreCase(coin.currency()))
                .filter(CregisGateway.Coin::addressEnabled)
                .filter(CregisGateway.Coin::payoutEnabled)
                .findFirst()
                .orElseThrow(() -> new CregisGatewayException(
                        CregisGatewayException.Kind.INVALID_RESPONSE, "CREGIS_USDT_BEP20_UNAVAILABLE"));

        String probeId = "cregis-local-contract-probe-v1";
        CregisGateway.Address depositAddress = gateway.createAddress(
                usdt.chainId(), probeId, "https://sandbox.invalid/cregis/deposit", probeId);
        boolean legal = gateway.addressLegal(usdt.chainId(), depositAddress.address());
        boolean owned = gateway.addressBelongs(usdt.chainId(), depositAddress.address());
        CregisGateway.PayoutRequest payoutRequest = new CregisGateway.PayoutRequest(
                usdt.currency(),
                "0x3333333333333333333333333333333333333333",
                BigDecimal.ONE,
                probeId,
                "https://sandbox.invalid/cregis/payout",
                "local contract probe; no funds");
        CregisGateway.PayoutSubmission submission = gateway.createPayout(payoutRequest);
        CregisGateway.PayoutOrder payout = gateway.queryPayout(new CregisGateway.PayoutQuery(
                submission.cid(), payoutRequest.thirdPartyId(), payoutRequest.address(), payoutRequest.amount()));
        boolean callbackVerified = verifySyntheticCallback(payout);
        if (!legal || !owned || !callbackVerified || gateway.hasExternalFundSideEffects()) {
            throw new CregisGatewayException(
                    CregisGatewayException.Kind.INVALID_RESPONSE, "CREGIS_LOCAL_SANDBOX_PROBE_FAILED");
        }
        return new ProbeResult(
                "PASS",
                "USDT-BEP20",
                true,
                true,
                true,
                false,
                payout.status().name());
    }

    private boolean verifySyntheticCallback(CregisGateway.PayoutOrder payout) {
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("pid", LOCAL_PID);
        callback.put("cid", payout.cid());
        callback.put("chain_id", payout.chainId());
        callback.put("token_id", payout.tokenId());
        callback.put("currency", payout.currency());
        callback.put("address", payout.address());
        callback.put("amount", payout.amount().toPlainString());
        callback.put("third_party_id", payout.thirdPartyId());
        callback.put("status", CregisGateway.PayoutStatus.FAILED.providerCode());
        callback.put("nonce", "sbx001");
        callback.put("timestamp", clock.millis());
        callback.put("sign", signer.sign(LOCAL_CALLBACK_KEY, callback));
        CregisCallbackVerifier verifier = new CregisCallbackVerifier(
                signer, LOCAL_PID, LOCAL_CALLBACK_KEY, clock, Duration.ofMinutes(5));
        return verifier.verifyPayout(callback, new CregisCallbackVerifier.ExpectedPayout(
                payout.cid(), payout.thirdPartyId(), payout.address(), payout.amount())).valid();
    }

    public record SandboxOverview(
            String mode,
            boolean localSandboxAvailable,
            boolean productionReady,
            boolean fundWorkflowConnected,
            String asset) { }

    public record ProbeResult(
            String result,
            String asset,
            boolean addressLegal,
            boolean addressOwned,
            boolean callbackSignatureVerified,
            boolean externalFundSideEffects,
            String payoutStatus) { }
}
