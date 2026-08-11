package ffdd.opsconsole.finance.cregis;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

public final class CregisCallbackVerifier {
    private static final Pattern EVM_ADDRESS = Pattern.compile("(?i)^0x[0-9a-f]{40}$");
    private static final Pattern BSC_TXID = Pattern.compile("(?i)^(?:0x)?[0-9a-f]{64}$");

    private final CregisSigner signer;
    private final long projectId;
    private final String apiKey;
    private final Clock clock;
    private final Duration tolerance;

    public CregisCallbackVerifier(
            CregisSigner signer,
            long projectId,
            String apiKey,
            Clock clock,
            Duration tolerance) {
        this.signer = signer;
        this.projectId = projectId;
        this.apiKey = apiKey;
        this.clock = clock;
        this.tolerance = tolerance;
    }

    public Verification verifyPayout(Map<String, Object> callback, ExpectedPayout expected) {
        if (callback == null || projectId <= 0 || apiKey == null || apiKey.isEmpty()
                || tolerance == null || tolerance.isNegative() || tolerance.isZero() || expected == null) {
            return invalid("VERIFIER_NOT_CONFIGURED");
        }
        if (expected.cid() <= 0 || text(expected.thirdPartyId()) == null
                || text(expected.address()) == null || !EVM_ADDRESS.matcher(expected.address()).matches()
                || CregisAmount.normalizePositive(expected.amount()) == null) {
            return invalid("EXPECTED_PAYOUT_INVALID");
        }
        Long pid = integer(callback.get("pid"));
        Long cid = integer(callback.get("cid"));
        Long timestamp = integer(callback.get("timestamp"));
        Integer status = exactInt(callback.get("status"));
        String nonce = text(callback.get("nonce"));
        String signature = text(callback.get("sign"));
        String thirdPartyId = text(callback.get("third_party_id"));
        String address = text(callback.get("address"));
        BigDecimal amount = positiveAmount(callback.get("amount"));
        if (pid == null || cid == null || cid <= 0 || timestamp == null || status == null
                || nonce == null || !nonce.matches("[A-Za-z0-9]{6}")
                || signature == null || thirdPartyId == null || thirdPartyId.length() > 128
                || address == null || !EVM_ADDRESS.matcher(address).matches() || amount == null) {
            return invalid("CALLBACK_SCHEMA_INVALID");
        }
        if (pid != projectId) return invalid("PROJECT_ID_MISMATCH");
        Instant received = Instant.ofEpochMilli(timestamp);
        Duration skew = Duration.between(received, clock.instant()).abs();
        if (skew.compareTo(tolerance) > 0) return invalid("TIMESTAMP_OUT_OF_WINDOW");
        try {
            if (!signer.verify(apiKey, callback, signature)) return invalid("SIGNATURE_INVALID");
        } catch (IllegalArgumentException malformed) {
            return invalid("CALLBACK_SCHEMA_INVALID");
        }
        if (status < 0 || status > 7) return invalid("STATUS_INVALID");
        if (!CregisConstants.BSC_CHAIN_ID.equals(text(callback.get("chain_id")))
                || !CregisConstants.USDT_BEP20_TOKEN_ID.equalsIgnoreCase(text(callback.get("token_id")))
                || !supportedCurrency(text(callback.get("currency")))) {
            return invalid("ASSET_SCOPE_MISMATCH");
        }
        if (cid.longValue() != expected.cid()
                || !thirdPartyId.equals(expected.thirdPartyId())
                || !address.equalsIgnoreCase(expected.address())
                || amount.compareTo(expected.amount()) != 0) {
            return invalid("PAYOUT_SNAPSHOT_MISMATCH");
        }
        Object rawTxid = callback.get("txid");
        String txid = text(rawTxid);
        if ((rawTxid != null && txid == null)
                || (txid != null && !BSC_TXID.matcher(txid).matches())
                || (status == CregisGateway.PayoutStatus.SUCCEEDED.providerCode() && txid == null)) {
            return invalid("CALLBACK_SCHEMA_INVALID");
        }
        return new Verification(true, "OK");
    }

    private boolean supportedCurrency(String currency) {
        return CregisConstants.USDT_BEP20_CURRENCY.equalsIgnoreCase(currency)
                || "USDT-BEP20".equalsIgnoreCase(currency);
    }

    private Long integer(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private Integer exactInt(Object value) {
        Long parsed = integer(value);
        if (parsed == null || parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) return null;
        return parsed.intValue();
    }

    private String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        return text;
    }

    private BigDecimal positiveAmount(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        return CregisAmount.parsePositive(text);
    }

    private Verification invalid(String reason) {
        return new Verification(false, reason);
    }

    public record Verification(boolean valid, String reason) { }

    public record ExpectedPayout(long cid, String thirdPartyId, String address, BigDecimal amount) { }
}
