package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;

public record NotificationCampaignRow(
        String id,
        String name,
        String kind,
        String tier,
        String audience,
        String reach,
        String status,
        String schedule,
        String sent,
        String read,
        String bodyEn,
        String bodyZh,
        String swipeTo,
        BigDecimal budget) {
}
