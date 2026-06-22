package ffdd.opsconsole.content.dto;

public record LearningFeaturedUpdateRequest(
        String courseId,
        String operator,
        String reason) {
}
