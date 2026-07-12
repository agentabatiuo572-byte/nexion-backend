package ffdd.opsconsole.content.domain;

import java.util.List;

public record NotificationAudienceCatalog(
        List<NotificationAudienceOption> phases,
        List<NotificationAudienceOption> languages,
        String conditionLogic) {
}
