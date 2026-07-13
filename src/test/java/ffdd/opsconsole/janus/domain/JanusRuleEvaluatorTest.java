package ffdd.opsconsole.janus.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JanusRuleEvaluatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void weightedRulesAreEvaluatedExactlyOnce() throws Exception {
        var tree = objectMapper.readTree("""
                {"mode":"WEIGHTED_SCORE","threshold":2,"rules":[
                  {"field":"maturityScore","op":">=","value":50,"weight":2},
                  {"field":"environmentRiskScore","op":"<","value":40,"weight":1}
                ]}
                """);
        var device = new JanusDeviceView("SID-1", "D-1", 1L, 2L, 1L, 1, null, "official", null,
                "OBSERVING", null, null, "system", false, null, 60, 50, 10, 60,
                null, "Android", "Pixel", "Android", "Chrome", objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), null, null, objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), null, null, null,
                objectMapper.createArrayNode(), 1L);

        JanusRuleEvaluator.Result result = new JanusRuleEvaluator().evaluate(tree, device);

        assertThat(result.passed()).isTrue();
        assertThat(result.totalLeaves()).isEqualTo(2);
        assertThat(result.trace()).hasSize(2);
    }

    @Test
    void notGroupPassesOnlyWhenNoneOfItsChildrenPass() throws Exception {
        var tree = objectMapper.readTree("""
                {"mode":"NOT","rules":[
                  {"field":"maturityScore","op":">=","value":50},
                  {"field":"environmentRiskScore","op":">=","value":60}
                ]}
                """);
        var device = new JanusDeviceView("SID-1", "D-1", 1L, 2L, 1L, 1, null, "official", null,
                "OBSERVING", null, null, "system", false, null, 60, 50, 10, 60,
                null, "Android", "Pixel", "Android", "Chrome", objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), null, null, objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), null, null, null,
                objectMapper.createArrayNode(), 1L);

        assertThat(new JanusRuleEvaluator().evaluate(tree, device).passed()).isFalse();
    }
}
