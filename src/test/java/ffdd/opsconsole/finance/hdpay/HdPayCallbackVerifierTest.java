package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HdPayCallbackVerifierTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void verifiesDocumentedPayInCallbackWithoutLosingDecimalScale() throws Exception {
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
        fields.put("transAmt", "100.00");
        String sign = HdPaySigner.sign(fields, key);
        JsonNode callback = objectMapper.readTree("""
                {"createTime":"2024-08-21 14:32:35","merchantId":"1234567890123456789",
                 "merchantOrderId":"VQR-1","orderId":"1826145351742570496","orderStatus":3,
                 "payTime":"2024-08-21 14:43:17","signType":"MD5","standbyObject":"{}",
                 "transAmt":100.00,"sign":"%s"}
                """.formatted(sign));

        HdPayCallbackVerifier.VerifiedCallback verified =
                new HdPayCallbackVerifier(properties(key)).verify(callback);

        assertThat(verified.merchantOrderId()).isEqualTo("VQR-1");
        assertThat(verified.transAmt()).isEqualByComparingTo("100.00");
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
