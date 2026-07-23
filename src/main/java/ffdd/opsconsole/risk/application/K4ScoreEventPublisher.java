package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskScoreContributionView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the two A4-governed, server-authoritative K4 score facts. */
final class K4ScoreEventPublisher {
    private K4ScoreEventPublisher() {
    }

    static void publishScoreUpdated(
            EventOutboxService outbox,
            RiskScoreUserView before,
            RiskScoreUserView after) {
        List<Map<String, Object>> changedDimensions = changedDimensions(before, after);
        boolean scoreChanged = before == null
                || !Objects.equals(before.modelScore(), after.modelScore())
                || !Objects.equals(before.effectiveScore(), after.effectiveScore())
                || !Objects.equals(before.modelVersion(), after.modelVersion())
                || !changedDimensions.isEmpty();
        if (!scoreChanged) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", after.userNo());
        payload.put("score", after.effectiveScore());
        payload.put("band", canonicalBand(after));
        payload.put("changedDimensions", changedDimensions);
        payload.put("modelVersion", after.modelVersion());
        outbox.publish("RISK_SCORE_USER", after.userNo(), "risk.score_updated", payload);
    }

    static void publishScoreOverridden(
            EventOutboxService outbox,
            RiskScoreUserView after,
            String reason,
            String operator) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", after.userNo());
        payload.put("overrideScore", after.effectiveScore());
        payload.put("reason", reason);
        payload.put("operator", operator);
        outbox.publish("RISK_SCORE_USER", after.userNo(), "risk.score_overridden", payload);
    }

    private static List<Map<String, Object>> changedDimensions(
            RiskScoreUserView before,
            RiskScoreUserView after) {
        Map<String, RiskScoreContributionView> previous = new LinkedHashMap<>();
        if (before != null && before.contributions() != null) {
            for (RiskScoreContributionView contribution : before.contributions()) {
                previous.put(contribution.dimKey(), contribution);
            }
        }
        List<Map<String, Object>> changes = new ArrayList<>();
        if (after.contributions() == null) {
            return changes;
        }
        for (RiskScoreContributionView contribution : after.contributions()) {
            if (Objects.equals(previous.get(contribution.dimKey()), contribution)) {
                continue;
            }
            Map<String, Object> change = new LinkedHashMap<>();
            // PRD/A4 nested JSON contract: every element is exactly {dim, hit, contribution}.
            change.put("dim", contribution.dimKey());
            change.put("hit", Boolean.TRUE.equals(contribution.hit()));
            change.put("contribution", contribution.points());
            changes.add(change);
        }
        return changes;
    }

    private static String canonicalBand(RiskScoreUserView score) {
        return switch (score.bandTone() == null ? "" : score.bandTone()) {
            case "bad" -> "high";
            case "warn" -> "mid";
            default -> "low";
        };
    }
}
