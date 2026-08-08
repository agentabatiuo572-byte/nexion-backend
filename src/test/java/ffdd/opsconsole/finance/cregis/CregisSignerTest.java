package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CregisSignerTest {
    private final CregisSigner signer = new CregisSigner();

    @Test
    void matchesThePublishedCregisCanonicalSignatureAlgorithm() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pid", 1382528827416576L);
        parameters.put("currency", "195@195");
        parameters.put("address", "TXsmKpEuW7qWnXzJLGP9eDLvWPR2GRn1FS");
        parameters.put("amount", "1.1");
        parameters.put("remark", "payout");
        parameters.put("third_party_id", "c9231e604da54469a735af3f449c880f");
        parameters.put("callback_url", "http://192.168.2.29:9099/callback");
        parameters.put("nonce", "hwlkk6");
        parameters.put("timestamp", 1688004243314L);

        // Cregis' final request example contains this value. The preceding prose currently
        // prints a different digest, so the test locks the algorithm and final wire example.
        assertThat(signer.sign("f502a9ac9ca54327986f29c03b271491", parameters))
                .isEqualTo("d6eef2de79e39f434a38efb910213ba6");
    }

    @Test
    void ignoresSignNullAndEmptyButRejectsAmbiguousCompositeValues() {
        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("pid", 1L);
        clean.put("nonce", "abc123");
        Map<String, Object> noisy = new LinkedHashMap<>(clean);
        noisy.put("sign", "tampered");
        noisy.put("empty", "");
        noisy.put("null", null);

        String expected = signer.sign("secret", clean);
        assertThat(signer.sign("secret", noisy)).isEqualTo(expected);
        assertThat(signer.verify("secret", noisy, expected)).isTrue();
        assertThat(signer.verify("secret", noisy, "00000000000000000000000000000000")).isFalse();
        assertThatThrownBy(() -> signer.sign("secret", Map.of("nested", Map.of("x", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CREGIS_SIGNATURE_VALUE_UNSUPPORTED");
    }
}
