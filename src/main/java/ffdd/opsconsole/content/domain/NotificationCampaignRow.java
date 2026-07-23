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
        String bodyVi,
        String swipeTo,
        BigDecimal budget,
        NotificationAudienceTarget audienceTarget,
        String ctaLabel,
        String ctaHref,
        long revision) {

    public NotificationCampaignRow(
            String id, String name, String kind, String tier, String audience, String reach,
            String status, String schedule, String sent, String read, String bodyEn, String bodyZh,
            String bodyVi, String swipeTo, BigDecimal budget, NotificationAudienceTarget audienceTarget) {
        this(id, name, kind, tier, audience, reach, status, schedule, sent, read,
                bodyEn, bodyZh, bodyVi, swipeTo, budget, audienceTarget, "", "", 0L);
    }

    public NotificationCampaignRow(
            String id, String name, String kind, String tier, String audience, String reach,
            String status, String schedule, String sent, String read, String bodyEn, String bodyZh,
            String swipeTo, BigDecimal budget, NotificationAudienceTarget audienceTarget) {
        this(id, name, kind, tier, audience, reach, status, schedule, sent, read,
                bodyEn, bodyZh, bodyZh, swipeTo, budget, audienceTarget, "", "", 0L);
    }

    public NotificationCampaignRow(
            String id, String name, String kind, String tier, String audience, String reach,
            String status, String schedule, String sent, String read, String bodyEn, String bodyZh,
            String bodyVi, String swipeTo, BigDecimal budget, NotificationAudienceTarget audienceTarget,
            String ctaLabel, String ctaHref) {
        this(id, name, kind, tier, audience, reach, status, schedule, sent, read,
                bodyEn, bodyZh, bodyVi, swipeTo, budget, audienceTarget, ctaLabel, ctaHref, 0L);
    }
}
