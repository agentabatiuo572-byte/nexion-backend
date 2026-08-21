package ffdd.opsconsole.content.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Server-owned boundary for the local Nova model. Disabled unless explicitly enabled. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "nexion.nova.ai")
public class NovaAiProperties {
    public enum Mode { DISABLED, OLLAMA_LOCAL }

    private Mode mode = Mode.DISABLED;
    private String ragBaseUrl = "http://127.0.0.1:8010";
    private String ragCollection = "customer_support_knowledge_prd_v2_20260814";
    private String model = "gemma4-e4b-ctx32k:latest";
    private int connectTimeoutMs = 2_000;
    private int readTimeoutMs = 120_000;
    private int maxInputChars = 2_000;
    private int maxHistoryMessages = 10;
    private int maxOutputChars = 8_000;
    private int maxOutputTokens = 1_024;
    private int maxConcurrentRequests = 1;
}
