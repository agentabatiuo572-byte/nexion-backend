package ffdd.opsconsole.growth.dto;

public record GrowthQuestEventRequest(
        String id,
        String name,
        String kind,
        String state,
        String reward,
        Boolean featured,
        Boolean trackable,
        String condition,
        String geo,
        Integer targetValue,
        String href,
        java.time.LocalDateTime startsAt,
        java.time.LocalDateTime endsAt,
        String reason,
        String operator) {
}
