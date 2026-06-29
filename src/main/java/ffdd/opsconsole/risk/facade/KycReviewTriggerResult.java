package ffdd.opsconsole.risk.facade;

public record KycReviewTriggerResult(
        boolean requiresReview,
        boolean created,
        String ticketId,
        String reason) {

    public static KycReviewTriggerResult notRequired() {
        return new KycReviewTriggerResult(false, false, null, null);
    }
}
