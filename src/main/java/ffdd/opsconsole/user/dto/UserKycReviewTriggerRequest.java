package ffdd.opsconsole.user.dto;

public record UserKycReviewTriggerRequest(
        String reasonCode,
        String reason,
        String evidenceRef,
        String operator) {
}
