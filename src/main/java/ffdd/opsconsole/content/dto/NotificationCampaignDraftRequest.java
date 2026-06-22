package ffdd.opsconsole.content.dto;

import java.math.BigDecimal;

public record NotificationCampaignDraftRequest(
        String title,
        String body,
        String tier,
        String audience,
        String schedule,
        BigDecimal budget,
        String operator,
        String reason) {
}
