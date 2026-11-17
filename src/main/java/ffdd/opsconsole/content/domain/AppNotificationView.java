package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record AppNotificationView(
        Long id,
        String kind,
        String priority,
        String title,
        String body,
        String ctaLabel,
        String ctaHref,
        LocalDateTime createdAt,
        LocalDateTime readAt) {
    public AppNotificationView {
        ctaHref = withoutRetiredStakingHref(ctaHref);
    }

    public static String withoutRetiredStakingHref(String href) {
        String target = href == null ? "" : href.trim();
        return "/pages/staking/staking".equals(target) || "/staking".equals(target) ? "" : href;
    }
}
