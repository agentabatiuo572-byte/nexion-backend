package ffdd.opsconsole.content.domain;

public record NotificationEventFact(
        Long notificationId,
        Long userId,
        String kind,
        String priority,
        String ctaHref,
        boolean alreadyRead,
        String phase,
        Integer accountAgeMonths,
        String cohort) {
}
