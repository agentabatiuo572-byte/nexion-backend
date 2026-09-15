package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HdPayCallbackVerifierTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"100.00", "\"100.00\"", "897260.00", "\"897260.00\""})
    void verifiesPayInCallbackWithNumericOrTextAmount(String amountJson) throws Exception {
        String amountText = amountJson.replace("\"", "");
        String key = "0123456789abcdef0123456789abcdef";
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("createTime", "2024-08-21 14:32:35");
        fields.put("merchantId", "1234567890123456789");
        fields.put("merchantOrderId", "VQR-1");
        fields.put("orderId", "1826145351742570496");
        fields.put("orderStatus", "3");
        fields.put("payTime", "2024-08-21 14:43:17");
        fields.put("signType", "MD5");
        fields.put("standbyObject", "{}");
        fields.put("transAmt", amountText);
        String sign = HdPaySigner.sign(fields, key);
        JsonNode callback = objectMapper.readTree("""
                {"createTime":"2024-08-21 14:32:35","merchantId":"1234567890123456789",
                 "merchantOrderId":"VQR-1","orderId":"1826145351742570496","orderStatus":3,
                 "payTime":"2024-08-21 14:43:17","signType":"MD5","standbyObject":"{}",
                 "transAmt":%s,"sign":"%s"}
                """.formatted(amountJson, sign));

        HdPayCallbackVerifier.VerifiedCallback verified =
                new HdPayCallbackVerifier(properties(key)).verify(callback);

        assertThat(verified.merchantOrderId()).isEqualTo("VQR-1");
        assertThat(verified.transAmt()).isEqualByComparingTo(amountText);
        assertThat(verified.orderStatus()).isEqualTo(3);
    }

    @Test
    void rejectsCallbacksWhileTheProviderKillSwitchIsDisabled() {
        HdPayProperties disabled = properties("0123456789abcdef0123456789abcdef");
        disabled.setMode(HdPayProperties.Mode.DISABLED);

        assertThatThrownBy(() -> new HdPayCallbackVerifier(disabled)
                .verify(objectMapper.createObjectNode()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HDPAY_CALLBACK_DISABLED");
    }

    @Test
    void rejectsFractionalOrderStatusEvenWhenItFitsAnInteger() throws Exception {
        String key = "0123456789abcdef0123456789abcdef";
        JsonNode callback = objectMapper.readTree("""
                {"merchantId":"1234567890123456789","merchantOrderId":"VQR-1",
                 "orderId":"P-1","orderStatus":3.5,"transAmt":100.00,
                 "signType":"MD5","sign":"00000000000000000000000000000000"}
                """);

        assertThatThrownBy(() -> new HdPayCallbackVerifier(properties(key)).verify(callback))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HDPAY_CALLBACK_FIELD_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"100", "100.0", "100.00", "0.01", "999999999999999999.99"})
    void textAmountIsVerifiedExactlyAsSigned(String amount) {
        ObjectNode body = signedTextCallback(amount);

        assertThat(new HdPayCallbackVerifier(properties(testKey())).verify(body).transAmt())
                .isEqualByComparingTo(amount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"100", "100.0", "101.00"})
    void changingSignedTextEvenToAnEquivalentAmountInvalidatesSignature(String changedAmount) {
        ObjectNode body = signedTextCallback("100.00");
        body.put("transAmt", changedAmount);

        assertThatThrownBy(() -> new HdPayCallbackVerifier(properties(testKey())).verify(body))
                .isInstanceOf(BizException.class)
                .hasMessage("HDPAY_CALLBACK_SIGNATURE_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", " 100.00", "100.00 ", "+100.00", "-1", "0", "0.00",
            "100.001", "100.000", "1e2", "1E+9999", "NaN", "Infinity", "897,260.00",
            ".01", "100.", "1000000000000000000.00", "0000000000000000000001.00"
    })
    void rejectsInvalidTextAmountsWithControlledFieldError(String amount) {
        assertInvalidAmount(signedTextCallback(amount));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "{}", "[]", "0", "-1", "100.001", "1e50", "1e309"})
    void rejectsInvalidNumericAndNonScalarAmountsWithControlledFieldError(String amountJson)
            throws Exception {
        ObjectNode body = signedTextCallback("100.00");
        body.set("transAmt", objectMapper.readTree(amountJson));
        assertInvalidAmount(body);
    }

    @Test
    void rejectsMissingAmountWithControlledFieldError() {
        ObjectNode body = signedTextCallback("100.00");
        body.remove("transAmt");
        assertInvalidAmount(body);
    }

    @Test
    void textAmountsDoNotBypassMerchantIdentityOrSignatureChecks() {
        ObjectNode body = signedTextCallback("897260.00");
        HdPayProperties otherMerchant = properties(testKey());
        otherMerchant.setMerchantId("9876543210987654321");
        assertThatThrownBy(() -> new HdPayCallbackVerifier(otherMerchant).verify(body))
                .isInstanceOf(BizException.class)
                .hasMessage("HDPAY_CALLBACK_SIGNATURE_INVALID");

        body.put("sign", "00000000000000000000000000000000");
        assertThatThrownBy(() -> new HdPayCallbackVerifier(properties(testKey())).verify(body))
                .isInstanceOf(BizException.class)
                .hasMessage("HDPAY_CALLBACK_SIGNATURE_INVALID");
    }

    private void assertInvalidAmount(JsonNode body) {
        assertThatThrownBy(() -> new HdPayCallbackVerifier(properties(testKey())).verify(body))
                .isInstanceOfSatisfying(BizException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(400);
                    assertThat(error.getMessage()).isEqualTo("HDPAY_CALLBACK_FIELD_INVALID");
                });
    }

    private ObjectNode signedTextCallback(String amount) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("merchantId", "1234567890123456789");
        body.put("merchantOrderId", "VQR-1");
        body.put("orderId", "P-1");
        body.put("orderStatus", 3);
        body.put("transAmt", amount);
        body.put("signType", "MD5");
        Map<String, String> fields = new LinkedHashMap<>();
        body.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue().asText()));
        body.put("sign", HdPaySigner.sign(fields, testKey()));
        return body;
    }

    private String testKey() {
        return "0123456789abcdef0123456789abcdef";
    }

    private HdPayProperties properties(String key) {
        HdPayProperties properties = new HdPayProperties();
        properties.setMode(HdPayProperties.Mode.PROVIDER);
        properties.setBaseUrl("https://api.hdpayadmin.com/api/order");
        properties.setCallbackBaseUrl("https://payments.example.com");
        properties.setCallbackHosts(java.util.List.of("payments.example.com"));
        properties.setMerchantId("1234567890123456789");
        properties.setMd5Key(key);
        properties.setPayType("BANKQR");
        properties.setCountryCode("VN");
        return properties;
    }
}
