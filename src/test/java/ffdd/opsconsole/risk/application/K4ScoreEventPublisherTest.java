package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.risk.domain.RiskScoreContributionView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class K4ScoreEventPublisherTest {

    @Test
    void changedDimensionsUsesExactPrdNestedElementContract() {
        EventOutboxService outbox = mock(EventOutboxService.class);
        RiskScoreUserView after = new RiskScoreUserView(
                "U00000007", 73, 73, false, "高风险", "bad", "k4-v13", 3L,
                "2026-07-22T12:00:00", "刚刚",
                List.of(new RiskScoreContributionView(
                        "withdrawVelocity", "提现速度", true, "24h=4", 73, 100, 26)));

        K4ScoreEventPublisher.publishScoreUpdated(outbox, null, after);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(outbox).publish(eq("RISK_SCORE_USER"), eq("U00000007"), eq("risk.score_updated"), payload.capture());
        assertThat(payload.getValue().get("changedDimensions")).isEqualTo(List.of(Map.of(
                "dim", "withdrawVelocity",
                "hit", true,
                "contribution", 26)));
        @SuppressWarnings("unchecked")
        Map<String, Object> element = ((List<Map<String, Object>>) payload.getValue()
                .get("changedDimensions")).get(0);
        assertThat(element).containsOnlyKeys("dim", "hit", "contribution");
    }
}
