package ffdd.opsconsole.finance.cregis;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public final class LocalCregisSandboxGateway implements CregisGateway {
    private static final Pattern EVM_ADDRESS = Pattern.compile("(?i)^0x[0-9a-f]{40}$");
    private final Map<String, AddressFixture> addressesByRequestId = new ConcurrentHashMap<>();
    private final Map<String, String> addressOwners = new ConcurrentHashMap<>();
    private final Map<String, PayoutFixture> payoutsByThirdPartyId = new ConcurrentHashMap<>();
    private final Map<Long, PayoutOrder> payoutsByCid = new ConcurrentHashMap<>();

    @Override
    public List<Coin> projectCoins() {
        return List.of(new Coin(
                CregisConstants.USDT_BEP20_CURRENCY,
                "USDT-BEP20 (LOCAL SANDBOX)",
                CregisConstants.BSC_CHAIN_ID,
                CregisConstants.USDT_BEP20_TOKEN_ID,
                true,
                true));
    }

    @Override
    public Address createAddress(String chainId, String alias, String callbackUrl, String requestId) {
        requireBsc(chainId);
        requireText(requestId, 128, "CREGIS_REQUEST_INVALID");
        requireOptionalText(alias, 40, "CREGIS_REQUEST_INVALID");
        requireHttpsUrl(callbackUrl);
        AddressFixture candidate = new AddressFixture(
                safe(alias), callbackUrl.trim(), deterministicAddress(requestId.trim()));
        AddressFixture existing = addressesByRequestId.putIfAbsent(requestId.trim(), candidate);
        if (existing != null) throw addressReplayUnknown();
        addressOwners.putIfAbsent(candidate.address().toLowerCase(Locale.ROOT), requestId.trim());
        return new Address(chainId, candidate.address(), requestId.trim());
    }

    @Override
    public boolean addressBelongs(String chainId, String address) {
        requireBsc(chainId);
        if (!StringUtils.hasText(address)) return false;
        return addressOwners.containsKey(address.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean addressLegal(String chainId, String address) {
        requireBsc(chainId);
        return StringUtils.hasText(address) && EVM_ADDRESS.matcher(address.trim()).matches();
    }

    @Override
    public PayoutSubmission createPayout(PayoutRequest request) {
        PayoutRequest normalized = normalize(request);
        PayoutFixture candidate = new PayoutFixture(normalized, deterministicCid(normalized.thirdPartyId()));
        PayoutFixture existing = payoutsByThirdPartyId.putIfAbsent(normalized.thirdPartyId(), candidate);
        if (existing != null) throw duplicateBusinessIdUnknown();
        payoutsByCid.put(candidate.cid(), new PayoutOrder(
                candidate.cid(),
                normalized.currency(),
                CregisConstants.BSC_CHAIN_ID,
                CregisConstants.USDT_BEP20_TOKEN_ID,
                normalized.address(),
                normalized.amount(),
                normalized.thirdPartyId(),
                PayoutStatus.AWAITING_AUDIT,
                null));
        return new PayoutSubmission(candidate.cid(), normalized.thirdPartyId());
    }

    @Override
    public PayoutOrder queryPayout(PayoutQuery expected) {
        BigDecimal expectedAmount = expected == null ? null : CregisAmount.normalizePositive(expected.amount());
        if (expected == null || expected.cid() <= 0
                || !StringUtils.hasText(expected.thirdPartyId())
                || !StringUtils.hasText(expected.address())
                || expectedAmount == null) {
            throw requestInvalid();
        }
        PayoutOrder order = payoutsByCid.get(expected.cid());
        if (order == null) {
            throw new CregisGatewayException(CregisGatewayException.Kind.REJECTED, "CREGIS_PAYOUT_NOT_FOUND");
        }
        if (!order.thirdPartyId().equals(expected.thirdPartyId())
                || !order.address().equalsIgnoreCase(expected.address())
                || order.amount().compareTo(expectedAmount) != 0) {
            throw new CregisGatewayException(
                    CregisGatewayException.Kind.INVALID_RESPONSE, "CREGIS_PAYOUT_SNAPSHOT_MISMATCH");
        }
        return order;
    }

    @Override
    public boolean hasExternalFundSideEffects() {
        return false;
    }

    private PayoutRequest normalize(PayoutRequest request) {
        if (request == null || !CregisConstants.USDT_BEP20_CURRENCY.equals(request.currency())) {
            throw requestInvalid();
        }
        if (!addressLegal(CregisConstants.BSC_CHAIN_ID, request.address())) throw requestInvalid();
        BigDecimal amount = CregisAmount.normalizePositive(request.amount());
        if (amount == null) throw requestInvalid();
        requireText(request.thirdPartyId(), 128, "CREGIS_REQUEST_INVALID");
        requireHttpsUrl(request.callbackUrl());
        requireOptionalText(request.remark(), 255, "CREGIS_REQUEST_INVALID");
        return new PayoutRequest(
                request.currency(),
                request.address().trim().toLowerCase(Locale.ROOT),
                amount,
                request.thirdPartyId().trim(),
                request.callbackUrl().trim(),
                safe(request.remark()));
    }

    private String deterministicAddress(String requestId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("NEXION-CREGIS-LOCAL-SANDBOX:" + requestId).getBytes(StandardCharsets.UTF_8));
            return "0x" + java.util.HexFormat.of().formatHex(digest, 0, 20);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("CREGIS_SANDBOX_HASH_UNAVAILABLE", impossible);
        }
    }

    private long deterministicCid(String thirdPartyId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("NEXION-CREGIS-LOCAL-PAYOUT:" + thirdPartyId).getBytes(StandardCharsets.UTF_8));
            long value = java.nio.ByteBuffer.wrap(digest, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
            return 8_000_000_000_000_000L + (value % 999_999_999_999_999L);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("CREGIS_SANDBOX_HASH_UNAVAILABLE", impossible);
        }
    }

    private void requireBsc(String chainId) {
        if (!CregisConstants.BSC_CHAIN_ID.equals(chainId)) throw requestInvalid();
    }

    private void requireHttpsUrl(String value) {
        requireText(value, 2048, "CREGIS_REQUEST_INVALID");
        try {
            java.net.URI uri = java.net.URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                throw requestInvalid();
            }
        } catch (IllegalArgumentException invalid) {
            throw requestInvalid();
        }
    }

    private void requireText(String value, int maxLength, String code) {
        if (!StringUtils.hasText(value) || value.trim().length() > maxLength) {
            throw new CregisGatewayException(CregisGatewayException.Kind.REJECTED, code);
        }
    }

    private void requireOptionalText(String value, int maxLength, String code) {
        if (value != null && value.trim().length() > maxLength) {
            throw new CregisGatewayException(CregisGatewayException.Kind.REJECTED, code);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private CregisGatewayException requestInvalid() {
        return new CregisGatewayException(CregisGatewayException.Kind.REJECTED, "CREGIS_REQUEST_INVALID");
    }

    private CregisGatewayException addressReplayUnknown() {
        return new CregisGatewayException(
                CregisGatewayException.Kind.SUBMISSION_UNKNOWN, "CREGIS_ADDRESS_REPLAY_UNKNOWN");
    }

    private CregisGatewayException duplicateBusinessIdUnknown() {
        return new CregisGatewayException(
                CregisGatewayException.Kind.SUBMISSION_UNKNOWN, "CREGIS_DUPLICATE_BUSINESS_ID_UNKNOWN");
    }

    private record AddressFixture(String alias, String callbackUrl, String address) { }

    private record PayoutFixture(PayoutRequest request, long cid) { }
}
