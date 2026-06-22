package ffdd.opsconsole.content.domain;

import java.util.List;

public record NotificationCampaignOverview(
        NotificationCampaignStats stats,
        List<NotificationCampaignRow> campaigns,
        List<NotificationCapRuleView> capRules,
        List<String> tiers,
        List<String> audiences,
        List<String> statuses,
        List<NotificationSwipeRouteView> swipeRoutes,
        List<String> sources) {
}
