package ffdd.opsconsole.content.dto;

import java.math.BigDecimal;

public record NotificationCampaignCreateRequest(
        String name,
        String title,
        String content,
        String tier,
        String audience,
        BigDecimal budget,
        String operator,
        String reason) {
}
