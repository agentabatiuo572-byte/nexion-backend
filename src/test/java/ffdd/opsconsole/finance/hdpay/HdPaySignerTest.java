package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HdPaySignerTest {
    @Test
    void signsEveryPresentNonSignFieldInAsciiKeyOrderAndKeepsEmptyValues() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("transAmt", "100.00");
        fields.put("sign", "ignored");
        fields.put("orderRemark", "");
        fields.put("merchantId", "2094");

        String canonical = "merchantId=2094&orderRemark=&transAmt=100.00&test-key";
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat(HdPaySigner.sign(fields, "test-key")).isEqualTo(expected);
        assertThat(HdPaySigner.verify(fields, "test-key", expected.toUpperCase())).isTrue();
    }

    @Test
    void doesNotMutateInputOrLeakTheCanonicalStringThroughItsApi() {
        Map<String, String> fields = new LinkedHashMap<>(Map.of("merchantOrderId", "VQR-1"));
        Map<String, String> before = new LinkedHashMap<>(fields);

        HdPaySigner.sign(fields, "secret");

        assertThat(fields).isEqualTo(before);
    }
}
