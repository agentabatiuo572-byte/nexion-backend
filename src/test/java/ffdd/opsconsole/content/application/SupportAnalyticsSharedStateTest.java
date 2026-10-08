package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupportAnalyticsSharedStateTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode row(String value) { return json.valueToTree(Map.of("id","1","value",value)); }
    private JsonNode event(String id, int time, JsonNode before, JsonNode after) {
        return json.valueToTree(Map.of("operationId",id,"dbStarted",Map.of("utc","2026-10-07 00:00:0"+time),"before",before,"after",after));
    }
    @Test void unchangedAndCompleteOutOfOrderReceiptsAreAccepted() {
        var original=row("original"); var changed=row("changed"); var last=row("last");
        assertThatCode(()->SupportAnalyticsSharedState.requireChain(original,original,List.of())).doesNotThrowAnyException();
        assertThatCode(()->SupportAnalyticsSharedState.requireChain(original,last,List.of(event("two",2,changed,last),event("one",1,original,changed)))).doesNotThrowAnyException();
    }
    @Test void missingOrUnrelatedOrDuplicateReceiptsCannotAuthorizeRestoration() {
        var original=row("original"); var changed=row("changed"); var unrelated=row("unrelated"); var operation=event("one",1,original,changed);
        for (var events:List.of(List.<JsonNode>of(),List.of(event("other",1,unrelated,changed)),List.of(operation,operation)))
            assertThatThrownBy(()->SupportAnalyticsSharedState.requireChain(original,changed,events)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SupportAnalyticsSharedState.requireChain(original,unrelated,List.of(operation))).isInstanceOf(IllegalStateException.class);
    }
    @Test void objectSizesCompareBySerializedValueWhileContentChangesStillFail() throws Exception {
        var before=json.readTree("[{\"key\":\"fixture\",\"size\":68,\"sha256\":\"same\"}]");
        assertThat(SupportAnalyticsCaptureTest.canonicalValue(List.of(Map.of("key","fixture","size",68L,"sha256","same")))).isEqualTo(before);
        assertThat(SupportAnalyticsCaptureTest.canonicalValue(List.of(Map.of("key","fixture","size",69L,"sha256","same")))).isNotEqualTo(before);
        assertThat(SupportAnalyticsCaptureTest.canonicalValue(List.of(Map.of("key","fixture","size",68L,"sha256","different")))).isNotEqualTo(before);
    }
    @Test void coverageMayOnlyAdvanceItsWatermarkOnTheSameRecord() {
        var before=json.valueToTree(Map.of("id","1","coverage_start_at","2026-10-01 00:00:00","observed_through_at","2026-10-06 00:00:00"));
        var later=before.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)later).put("observed_through_at","2026-10-07 00:00:00");
        assertThatCode(()->SupportAnalyticsSharedState.requireCoverageAdvance(before,later)).doesNotThrowAnyException();
        for(var change:Map.of("id","2","coverage_start_at","2026-10-02 00:00:00","observed_through_at","2026-10-05 00:00:00").entrySet()) {
            var invalid=(com.fasterxml.jackson.databind.node.ObjectNode)before.deepCopy(); invalid.put(change.getKey(),change.getValue());
            assertThatThrownBy(()->SupportAnalyticsSharedState.requireCoverageAdvance(before,invalid)).isInstanceOf(IllegalStateException.class);
        }
    }
}
