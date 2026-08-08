package ffdd.opsconsole.finance.cregis;

import java.math.BigDecimal;
import java.util.List;

public interface CregisGateway {
    List<Coin> projectCoins();

    Address createAddress(String chainId, String alias, String callbackUrl, String requestId);

    boolean addressBelongs(String chainId, String address);

    boolean addressLegal(String chainId, String address);

    PayoutSubmission createPayout(PayoutRequest request);

    PayoutOrder queryPayout(PayoutQuery expected);

    default boolean hasExternalFundSideEffects() {
        return true;
    }

    record Coin(
            String currency,
            String coinName,
            String chainId,
            String tokenId,
            boolean payoutEnabled,
            boolean addressEnabled) { }

    record Address(String chainId, String address, String requestId) { }

    record PayoutRequest(
            String currency,
            String address,
            BigDecimal amount,
            String thirdPartyId,
            String callbackUrl,
            String remark) { }

    record PayoutSubmission(long cid, String thirdPartyId) { }

    record PayoutQuery(long cid, String thirdPartyId, String address, BigDecimal amount) { }

    record PayoutOrder(
            long cid,
            String currency,
            String chainId,
            String tokenId,
            String address,
            BigDecimal amount,
            String thirdPartyId,
            PayoutStatus status,
            String txid) { }

    enum PayoutStatus {
        AWAITING_AUDIT(0),
        SIGN_PASSED(1),
        SIGN_REJECTED(2),
        AUDIT_CANCELLED(3),
        AUDIT_REJECTED(4),
        AWAITING_SIGNATURE(5),
        SUCCEEDED(6),
        FAILED(7);

        private final int providerCode;

        PayoutStatus(int providerCode) {
            this.providerCode = providerCode;
        }

        public int providerCode() {
            return providerCode;
        }

        public static PayoutStatus fromProviderCode(int code) {
            for (PayoutStatus status : values()) {
                if (status.providerCode == code) return status;
            }
            throw new CregisGatewayException(
                    CregisGatewayException.Kind.INVALID_RESPONSE, "CREGIS_RESPONSE_INVALID");
        }
    }
}
