package ffdd.commerce.dto;

import lombok.Data;

@Data
public class StoreAiPerformance {
    private Integer imageGenPerMin;
    private Integer llmTokensPerSec;
    private Integer videoMinPerHour;
    private String unlocks;
}
