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
        String reason,
        String operator) {
}
