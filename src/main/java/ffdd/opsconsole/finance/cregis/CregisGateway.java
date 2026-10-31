package ffdd.opsconsole.finance.cregis;

import java.math.BigDecimal;
import java.util.List;

public interface CregisGateway {
    List<Coin> projectCoins();

    Address createAddress(String chainId, String alias, String callbackUrl, String requestId);

    boolean addressBelongs(String chainId, String address);

    boolean addressLegal(String chainId, String address);

    /** Provider-side zero balance check before a sub-address may enter the pool. */
    default boolean zeroAddressBalance(String currency, String address) {
        throw new CregisGatewayException(CregisGatewayException.Kind.CONFIGURATION,
                "CREGIS_ADDRESS_BALANCE_UNSUPPORTED");
    }

    /** A read-only second provider observation for a single incoming transaction. */
    List<DepositTrade> depositsByTxid(String txid);

    default List<DepositTrade> depositsByTxid(CregisNetwork network, String txid) {
        if (network == CregisNetwork.BEP20) return depositsByTxid(txid);
        throw new CregisGatewayException(CregisGatewayException.Kind.CONFIGURATION, "CREGIS_NETWORK_UNSUPPORTED");
    }

    /** One provider page for a closed block-time window (epoch seconds, inclusive). */
    default DepositPage depositPage(long startSecond, long endSecond, int page, int size) {
        throw new CregisGatewayException(CregisGatewayException.Kind.CONFIGURATION,
                "CREGIS_TRADE_PAGE_UNSUPPORTED");
    }

    default DepositPage depositPage(CregisNetwork network, long startSecond, long endSecond, int page, int size) {
        if (network == CregisNetwork.BEP20) return depositPage(startSecond, endSecond, page, size);
        throw new CregisGatewayException(CregisGatewayException.Kind.CONFIGURATION, "CREGIS_NETWORK_UNSUPPORTED");
    }

    PayoutSubmission createPayout(PayoutRequest request);

    PayoutOrder queryPayout(PayoutQuery expected);

    default long createCollection(CollectionRequest request) {
        throw new CregisGatewayException(CregisGatewayException.Kind.CONFIGURATION,"CREGIS_AUTO_FORWARD_DISABLED");
    }
    default java.util.Optional<CollectionOrder> queryCollection(long cid, CollectionRequest expected) {
        throw new CregisGatewayException(CregisGatewayException.Kind.CONFIGURATION,"CREGIS_COLLECTION_QUERY_UNSUPPORTED");
    }
    default PayoutSubmission createAutoForwardPayout(PayoutRequest request) {
        throw new CregisGatewayException(CregisGatewayException.Kind.CONFIGURATION,"CREGIS_AUTO_FORWARD_DISABLED");
    }
    record CollectionRequest(String fromAddress,String toAddress,BigDecimal amount) { }
    record CollectionOrder(long cid,int status,String txid) { }

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

    record DepositTrade(long cid, String chainId, String tokenId, String address,
                        BigDecimal amount, String txid, int status) { }

    record DepositPage(long total, List<DepositRow> rows) { }
    record DepositRow(long cid, String chainId, String tokenId, String address,
                      BigDecimal amount, String txid, int status, long blockHeight,
                      long blockTime) { }

    record PayoutRequest(
            String currency,
            String address,
            BigDecimal amount,
            String thirdPartyId,
            String callbackUrl,
            String remark) { }

    record PayoutSubmission(long cid, String thirdPartyId) { }

    record PayoutQuery(long cid, String thirdPartyId, String address, BigDecimal amount, String currency) {
        public PayoutQuery(long cid, String thirdPartyId, String address, BigDecimal amount) {
            this(cid, thirdPartyId, address, amount, CregisConstants.USDT_BEP20_CURRENCY);
        }
    }

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
