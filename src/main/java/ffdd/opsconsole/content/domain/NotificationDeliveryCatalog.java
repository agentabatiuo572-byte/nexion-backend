package ffdd.opsconsole.content.domain;

import java.util.List;

public record NotificationDeliveryCatalog(
        List<NotificationAudienceOption> kinds,
        List<NotificationAudienceOption> ctaRoutes) {
}
