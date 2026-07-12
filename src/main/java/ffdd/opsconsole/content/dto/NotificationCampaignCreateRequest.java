package ffdd.opsconsole.content.dto;

import java.math.BigDecimal;
import ffdd.opsconsole.content.domain.NotificationAudienceTarget;

public record NotificationCampaignCreateRequest(
        String name,
        String titleZh,
        String titleVi,
        String titleEn,
        String bodyZh,
        String bodyVi,
        String bodyEn,
        String tier,
        NotificationAudienceTarget audienceTarget,
        BigDecimal budget,
        String operator,
        String reason,
        String kind,
        String ctaLabel,
        String ctaHref) {

    public NotificationCampaignCreateRequest(
            String name, String titleZh, String titleVi, String titleEn,
            String bodyZh, String bodyVi, String bodyEn, String tier,
            NotificationAudienceTarget audienceTarget, BigDecimal budget,
            String operator, String reason) {
        this(name, titleZh, titleVi, titleEn, bodyZh, bodyVi, bodyEn, tier,
                audienceTarget, budget, operator, reason, "system", "", "");
    }

    public NotificationCampaignCreateRequest(
            String name,
            String title,
            String content,
            String tier,
            String audience,
            BigDecimal budget,
            String operator,
            String reason) {
        this(name, title, title, title, content, content, content, tier,
                legacyAudience(audience), budget, operator, reason, "system", "", "");
    }

    public NotificationCampaignCreateRequest(
            String name, String titleZh, String titleEn, String bodyZh, String bodyEn,
            String tier, NotificationAudienceTarget audienceTarget, BigDecimal budget,
            String operator, String reason) {
        this(name, titleZh, titleZh, titleEn, bodyZh, bodyZh, bodyEn,
                tier, audienceTarget, budget, operator, reason, "system", "", "");
    }

    private static NotificationAudienceTarget legacyAudience(String audience) {
        return new NotificationAudienceTarget("P1", "P6", "all", 0);
    }
}
