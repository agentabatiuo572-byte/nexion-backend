package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Continue only the committed refund's timed-out readback; never inject reserve or issue a new refund. */
class SupportRefundCommittedReadbackTest extends SupportNetContributionRefundRuntimeTest {
    @Test void committedRefundHttpReadbackAndOriginalKeyReplay() throws Exception {
        requireNetClone();
        var scene=readFixed("D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2/runtime/support-minimal-20261012/refund-receipt-precision/SupportNetContributionRefundRuntimeTest-retainedRechargeFundedS1WalletRefundPreservesOriginalFirstAndNetThenReplays.json",
            "0851f5ef452942104e93d81d2e3ea54c7ae22bfbf19aa5bfd8ed17cf9dad9ece");
        assertThat(scene.path("status").asText()).isEqualTo("not-completed");
        var successful=new ArrayList<JsonNode>();scene.path("committedCommands").forEach(c->{if(c.path("httpStatus").asInt()==200&&c.path("orderNo").asText().equals(ORDER))successful.add(c);});
        assertThat(successful).hasSize(1);var command=successful.get(0);
        assertThat(command.path("creatorSha256").asText()).isEqualTo(CREATOR_SHA);assertThat(command.path("priorSceneSha256").asText()).isEqualTo(PRIOR_SHA);
        prior=readFixed(PRIOR_SCENE,PRIOR_SHA);creator=readFixed(CREATOR,CREATOR_SHA);readFixed(CREATE_INTENT,CREATE_INTENT_SHA);
        priorNickname="an12_0b19d4c396";boss=command.path("actor").path("adminId").asLong();
        assertThat(boss).isEqualTo(5433);
        var current=ok(request("GET","/api/admin/auth/me",boss,null,null));
        assertThat(current.path("username")).isEqualTo(command.path("actor").path("username"));
        assertThat(physical("SELECT nickname FROM nx_user WHERE id=?",CUSTOMER).get(0).get("nickname")).isEqualTo(priorNickname);
        var before=refundWitness();var saved=scene.path("nativeObservations").path("retainedRefundAfter");
        for(var entry:before.entrySet()){
            if(Set.of("sourceFacts","firstHistory").contains(entry.getKey()))
                assertThat(strictJson().readTree(json.writeValueAsBytes(entry.getValue()))).isEqualTo(saved.path(entry.getKey()));
            else assertHistoricalRows(rows(before,entry.getKey()),saved.path(entry.getKey()));
        }
        assertNet(CUSTOMER,NET,"COMMITTED_REFUND_FINAL_HTTP_READBACK");
        var originalFirst=checkpoint(prior,"SECOND_RECHARGE").path("httpAnalytics").path("records").get(0).path("first");
        assertThat(retainedCustomerPage().path("records").get(0).path("first")).isEqualTo(originalFirst);
        var history=source.readHistory(List.of(CUSTOMER));
        assertThat(ffdd.opsconsole.content.application.SupportAnalyticsService.selectFirstFacts(history.facts()).get(CUSTOMER).kind()).isEqualTo(SupportPaymentFacts.Kind.DEPOSIT);
        var body=json.treeToValue(command.path("request"),DeviceOrderActionRequest.class);String key=command.path("key").asText();
        assertThat(body.terminalState()).isEqualTo("refunded");assertThat(body.refundChannel()).isEqualTo("WALLET");
        var claims=physical("SELECT * FROM nx_admin_idempotency_record WHERE scope='E4_ORDER_REFUND' AND idempotency_key=?",key);
        assertHistoricalRows(claims,command.path("claim"));
        var intent=new LinkedHashMap<String,Object>();intent.put("event","SAME_COMMITTED_REFUND_KEY_REPLAY");intent.put("key",key);intent.put("actor",current);intent.put("originalSceneSha256","0851f5ef452942104e93d81d2e3ea54c7ae22bfbf19aa5bfd8ed17cf9dad9ece");committedCommands.add(intent);saveIntent();
        var replay=request("POST","/api/admin/devices/orders/"+ORDER+"/refund",boss,body,key);ok(replay);assertThat(replay.body()).isEqualTo(command.path("body"));intent.put("response",replay.body());saveIntent();
        assertThat(refundWitness()).as("Original-key replay creates no second refund, ledger, wallet or audit delta").isEqualTo(before);
        assertNet(CUSTOMER,NET,"COMMITTED_REFUND_REPLAY_FINAL_HTTP_READBACK");
        assertThat(retainedCustomerPage().path("records").get(0).path("first")).isEqualTo(originalFirst);
        checked("Continues the SHA-pinned successful refund: independent stored rows unchanged, NET 849 Reader/HTTP and original deposit first preserved, original actor/key replay has no second financial effect. No reserve injection/new purchase/new refund was executed.");
    }
    @Override JsonNode retainedCustomerPage() throws Exception {
        var result=ok(request("GET","/api/admin/content/support-workbench/analytics?view=CUSTOMERS&basis=CURRENT_CUSTOMER_HISTORY&currency=USDT&groupId=687&keyword="+encoded(priorNickname),boss,null,null));
        assertThat(result.path("records").size()).isEqualTo(1);assertThat(result.path("records").get(0).path("customerId").asText()).isEqualTo(Long.toString(CUSTOMER));return result;
    }
}
