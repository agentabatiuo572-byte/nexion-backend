package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CregisCallbackVerifierTest {
    private static final String KEY = "sandbox-callback-signing-key";
    private static final long PID = 42L;
    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
    private final CregisSigner signer = new CregisSigner();
    private final CregisCallbackVerifier verifier = new CregisCallbackVerifier(
            signer, PID, KEY, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

    @Test
    void acceptsFreshTerminalPayoutCallbackAndRejectsTamperExpiryOrUnknownStatus() {
        Map<String, Object> callback = callbackAt(NOW, 6);
        callback.put("sign", signer.sign(KEY, callback));
        assertThat(verify(callback)).isEqualTo(new CregisCallbackVerifier.Verification(true, "OK"));

        Map<String, Object> nameCurrency = callbackAt(NOW, 6);
        nameCurrency.put("currency", "USDT-BEP20");
        nameCurrency.put("sign", signer.sign(KEY, nameCurrency));
        assertThat(verify(nameCurrency)).isEqualTo(new CregisCallbackVerifier.Verification(true, "OK"));

        Map<String, Object> tampered = new LinkedHashMap<>(callback);
        tampered.put("amount", "999");
        assertThat(verify(tampered).reason()).isEqualTo("SIGNATURE_INVALID");

        Map<String, Object> expired = callbackAt(NOW.minus(Duration.ofMinutes(6)), 6);
        expired.put("sign", signer.sign(KEY, expired));
        assertThat(verify(expired).reason()).isEqualTo("TIMESTAMP_OUT_OF_WINDOW");

        for (int progressStatus : new int[] {0, 1, 5}) {
            Map<String, Object> progress = callbackAt(NOW, progressStatus);
            progress.put("sign", signer.sign(KEY, progress));
            assertThat(verify(progress)).as("provider progress status %s", progressStatus)
                    .isEqualTo(new CregisCallbackVerifier.Verification(true, "OK"));
        }

        Map<String, Object> unknown = callbackAt(NOW, 8);
        unknown.put("sign", signer.sign(KEY, unknown));
        assertThat(verify(unknown).reason()).isEqualTo("STATUS_INVALID");

        Map<String, Object> successWithoutTxid = callbackAt(NOW, 6);
        successWithoutTxid.remove("txid");
        successWithoutTxid.put("sign", signer.sign(KEY, successWithoutTxid));
        assertThat(verify(successWithoutTxid).reason()).isEqualTo("CALLBACK_SCHEMA_INVALID");

        Map<String, Object> nested = callbackAt(NOW, 7);
        nested.put("amount", Map.of("unexpected", true));
        nested.put("sign", "0".repeat(32));
        assertThat(verify(nested).reason()).isEqualTo("CALLBACK_SCHEMA_INVALID");

        Map<String, Object> wrongSnapshot = callbackAt(NOW, 7);
        wrongSnapshot.put("third_party_id", "other-withdrawal");
        wrongSnapshot.put("sign", signer.sign(KEY, wrongSnapshot));
        assertThat(verify(wrongSnapshot).reason()).isEqualTo("PAYOUT_SNAPSHOT_MISMATCH");

        Map<String, Object> failedWithMalformedTxid = callbackAt(NOW, 7);
        failedWithMalformedTxid.put("txid", "not-a-bsc-transaction");
        failedWithMalformedTxid.put("sign", signer.sign(KEY, failedWithMalformedTxid));
        assertThat(verify(failedWithMalformedTxid).reason()).isEqualTo("CALLBACK_SCHEMA_INVALID");

        Map<String, Object> extremeAmount = callbackAt(NOW, 7);
        extremeAmount.put("amount", "1e2147483647");
        extremeAmount.put("sign", signer.sign(KEY, extremeAmount));
        assertThat(verify(extremeAmount).reason()).isEqualTo("CALLBACK_SCHEMA_INVALID");
    }

    private CregisCallbackVerifier.Verification verify(Map<String, Object> callback) {
        return verifier.verifyPayout(callback, new CregisCallbackVerifier.ExpectedPayout(
                9000000000000001L,
                "withdrawal-42",
                "0x1111111111111111111111111111111111111111",
                new java.math.BigDecimal("1.00")));
    }

    private Map<String, Object> callbackAt(Instant instant, int status) {
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("pid", PID);
        callback.put("cid", 9000000000000001L);
        callback.put("chain_id", CregisConstants.BSC_CHAIN_ID);
        callback.put("token_id", CregisConstants.USDT_BEP20_TOKEN_ID);
        callback.put("currency", CregisConstants.USDT_BEP20_CURRENCY);
        callback.put("address", "0x1111111111111111111111111111111111111111");
        callback.put("amount", "1.00");
        callback.put("third_party_id", "withdrawal-42");
        callback.put("status", status);
        if (status == 6) callback.put("txid", "0x" + "a".repeat(64));
        callback.put("nonce", "abc123");
        callback.put("timestamp", instant.toEpochMilli());
        return callback;
    }
}
