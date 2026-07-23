package ffdd.opsconsole.content.dto;

import java.math.BigDecimal;
import ffdd.opsconsole.content.domain.NotificationAudienceTarget;

public record NotificationCampaignDraftRequest(
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
        String ctaHref,
        Long expectedRevision) {

    public NotificationCampaignDraftRequest(
            String name, String titleZh, String titleVi, String titleEn,
            String bodyZh, String bodyVi, String bodyEn, String tier,
            NotificationAudienceTarget audienceTarget, BigDecimal budget,
            String operator, String reason) {
        this(name, titleZh, titleVi, titleEn, bodyZh, bodyVi, bodyEn, tier,
                audienceTarget, budget, operator, reason, "system", "", "", null);
    }

    public NotificationCampaignDraftRequest(
            String title,
            String body,
            String tier,
            String audience,
            String schedule,
            BigDecimal budget,
            String operator,
            String reason) {
        this(title, title, title, title, body, body, body, tier,
                new NotificationAudienceTarget("P1", "P6", "all", 0),
                budget, operator, reason, "system", "", "", null);
    }

    public NotificationCampaignDraftRequest(
            String name, String titleZh, String titleEn, String bodyZh, String bodyEn,
            String tier, NotificationAudienceTarget audienceTarget, BigDecimal budget,
            String operator, String reason) {
        this(name, titleZh, titleZh, titleEn, bodyZh, bodyZh, bodyEn,
                tier, audienceTarget, budget, operator, reason, "system", "", "", null);
    }

    public NotificationCampaignDraftRequest(
            String name, String titleZh, String titleVi, String titleEn,
            String bodyZh, String bodyVi, String bodyEn, String tier,
            NotificationAudienceTarget audienceTarget, BigDecimal budget,
            String operator, String reason, String kind, String ctaLabel, String ctaHref) {
        this(name, titleZh, titleVi, titleEn, bodyZh, bodyVi, bodyEn, tier,
                audienceTarget, budget, operator, reason, kind, ctaLabel, ctaHref, null);
    }
}
