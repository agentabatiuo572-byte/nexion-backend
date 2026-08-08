package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LocalCregisSandboxGatewayTest {
    private final LocalCregisSandboxGateway gateway = new LocalCregisSandboxGateway();

    @Test
    void exercisesBep20AddressValidationPayoutAndQueryWithoutRealFunds() {
        assertThat(gateway.projectCoins()).singleElement().satisfies(coin -> {
            assertThat(coin.currency()).isEqualTo(CregisConstants.USDT_BEP20_CURRENCY);
            assertThat(coin.payoutEnabled()).isTrue();
            assertThat(coin.addressEnabled()).isTrue();
        });

        CregisGateway.Address address = gateway.createAddress(
                CregisConstants.BSC_CHAIN_ID, "user-42", "https://sandbox.invalid/deposit", "addr-42");
        assertThat(gateway.addressLegal(CregisConstants.BSC_CHAIN_ID, address.address())).isTrue();
        assertThat(gateway.addressBelongs(CregisConstants.BSC_CHAIN_ID, address.address())).isTrue();

        var request = new CregisGateway.PayoutRequest(
                CregisConstants.USDT_BEP20_CURRENCY,
                "0x1111111111111111111111111111111111111111",
                new BigDecimal("12.50"),
                "withdrawal-42",
                "https://sandbox.invalid/payout",
                "local sandbox contract");
        CregisGateway.PayoutSubmission submission = gateway.createPayout(request);

        assertThat(gateway.queryPayout(new CregisGateway.PayoutQuery(
                submission.cid(), request.thirdPartyId(), request.address(), request.amount()))).satisfies(order -> {
            assertThat(order.thirdPartyId()).isEqualTo("withdrawal-42");
            assertThat(order.status()).isEqualTo(CregisGateway.PayoutStatus.AWAITING_AUDIT);
            assertThat(order.txid()).isNull();
        });
        assertThat(gateway.hasExternalFundSideEffects()).isFalse();
    }

    @Test
    void duplicateBusinessIdRemainsUnknownEvenWhenThePayloadMatches() {
        var first = request("withdrawal-idempotent", "1.00");
        gateway.createPayout(first);

        assertThatThrownBy(() -> gateway.createPayout(first))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_DUPLICATE_BUSINESS_ID_UNKNOWN");
        assertThatThrownBy(() -> gateway.createPayout(request("withdrawal-idempotent", "2.00")))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_DUPLICATE_BUSINESS_ID_UNKNOWN");
    }

    @Test
    void repeatedAddressRequestRemainsUnknownBecauseProviderHasNoAddressIdempotencyKey() {
        gateway.createAddress(CregisConstants.BSC_CHAIN_ID, "user-42",
                "https://sandbox.invalid/deposit", "addr-duplicate");

        assertThatThrownBy(() -> gateway.createAddress(CregisConstants.BSC_CHAIN_ID, "user-42",
                "https://sandbox.invalid/deposit", "addr-duplicate"))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_ADDRESS_REPLAY_UNKNOWN");
    }

    @Test
    void rejectsExponentAmountsThatCannotBeSafelyExpanded() {
        assertThatThrownBy(() -> gateway.createPayout(new CregisGateway.PayoutRequest(
                CregisConstants.USDT_BEP20_CURRENCY,
                "0x2222222222222222222222222222222222222222",
                new BigDecimal("1e2147483647"), "withdrawal-extreme",
                "https://sandbox.invalid/payout", "test")))
                .isInstanceOf(CregisGatewayException.class)
                .hasMessage("CREGIS_REQUEST_INVALID");
    }

    private CregisGateway.PayoutRequest request(String id, String amount) {
        return new CregisGateway.PayoutRequest(
                CregisConstants.USDT_BEP20_CURRENCY,
                "0x2222222222222222222222222222222222222222",
                new BigDecimal(amount), id, "https://sandbox.invalid/payout", "test");
    }
}
