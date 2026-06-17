package ffdd.opsconsole.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditLogSanitizerTest {
    private final AuditLogSanitizer sanitizer = new AuditLogSanitizer(new ObjectMapper());

    @Test
    void redactsSensitiveKeysRecursively() {
        String json = sanitizer.toSafeJson(Map.of(
                "paymentNo", "PAY-1",
                "signature", "abc123",
                "nested", Map.of(
                        "appSecret", "nxsk_secret",
                        "safe", "visible")));

        assertThat(json).contains("\"paymentNo\":\"PAY-1\"");
        assertThat(json).contains("\"signature\":\"[REDACTED]\"");
        assertThat(json).contains("\"appSecret\":\"[REDACTED]\"");
        assertThat(json).contains("\"safe\":\"visible\"");
        assertThat(json).doesNotContain("abc123", "nxsk_secret");
    }

    @Test
    void clipsLargePayloads() {
        String json = sanitizer.toSafeJson(Map.of("note", "x".repeat(7000)));

        assertThat(json).hasSizeLessThanOrEqualTo(4096);
        assertThat(json).endsWith("...");
    }

    @Test
    void preservesTemporalValuesAsScalarDetails() {
        String json = sanitizer.toSafeJson(Map.of("reviewedAt", LocalDateTime.of(2026, 5, 26, 18, 0)));

        assertThat(json).contains("reviewedAt");
    }
}
