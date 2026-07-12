package ffdd.opsconsole.content.domain;

public record NotificationAudienceTarget(
        String phaseMin,
        String phaseMax,
        String language,
        Integer registrationDaysMin) {
}
