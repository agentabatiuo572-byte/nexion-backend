package ffdd.opsconsole.user.domain;

public record UserKycStats(
        long total,
        long verified,
        long unverified,
        long inReview,
        long rejected,
        String verifiedPct,
        int feeUsd) {
}
