package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.Map;

public record AppNotificationView(
        Long id,
        String kind,
        String priority,
        String title,
        String body,
        String ctaLabel,
        String ctaHref,
        LocalDateTime createdAt,
        LocalDateTime readAt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Map<String,Object> earning) {
    public AppNotificationView {
        ctaHref = withoutRetiredStakingHref(ctaHref);
    }

    /** Existing callers keep the nine-argument shape; only earning rows carry the map. */
    public AppNotificationView(Long id,String kind,String priority,String title,String body,String ctaLabel,
            String ctaHref,LocalDateTime createdAt,LocalDateTime readAt) {
        this(id,kind,priority,title,body,ctaLabel,ctaHref,createdAt,readAt,null);
    }

    public static String withoutRetiredStakingHref(String href) {
        String target = href == null ? "" : href.trim();
        return "/pages/staking/staking".equals(target) || "/staking".equals(target) ? "" : href;
    }
}
