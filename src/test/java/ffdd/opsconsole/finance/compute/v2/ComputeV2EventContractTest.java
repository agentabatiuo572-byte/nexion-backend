package ffdd.opsconsole.finance.compute.v2;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event;
import ffdd.opsconsole.device.compute.v2.ComputeV2Persistence.SealedBatch;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComputeV2EventContractTest {
    private final Instant at=Instant.parse("2026-11-01T00:00:00Z");
    private final ComputeV2SettlementMySqlIntegrationTest examples=new ComputeV2SettlementMySqlIntegrationTest();

    @Test void exactMillionthsAndBothVerifiedCurrenciesRoundTripWithoutFloatingPointMoney() throws Exception {
        var event=examples.completion(1,1,999999,at);
        var json=new ObjectMapper().findAndRegisterModules();
        var decoded=json.readValue(json.writeValueAsString(event),ComputeV2Event.class);
        assertThat(decoded).isEqualTo(event);assertThat(decoded.task().rewardUsdtMicros()).isEqualTo(1);
        assertThat(decoded.evidence().rewardNexMicros()).isEqualTo(999999);
        assertThat(ComputeV2SettlementService.money(1)).isEqualByComparingTo("0.000001");
    }
    @Test void invalidMoneyAndMalformedDailyEntitlementAreRejectedAtTheEnvelopeBoundary() {
        assertThatThrownBy(()->examples.completion(1,-1,0,at)).isInstanceOf(IllegalArgumentException.class);
        var e=examples.completion(1,1,1,at);var p=e.evidence();
        var wrong=new ComputeV2Event.Verified(p.proofHash(),p.inputSha256(),p.resultSha256(),p.verifiedAt(),p.completedAt(),p.artifactRef(),p.artifactBytes(),1,"CLOUD_SHARE_DAILY:7:2026-11-01",p.utcDay());
        assertThatThrownBy(()->new ComputeV2Event(2,e.eventId(),e.kind(),e.deploymentScope(),"PRODUCTION","",e.userId(),e.deviceId(),e.instanceNo(),e.taskNo(),e.generation(),e.occurredAt(),e.task(),wrong,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void startRetryCannotExtendTheFrozenProofOrExecutionLease() {
        var task=examples.completion(1,1,0,at).task();
        assertThat(task.withStarted(task.startedAt(),task.leaseExpiresAt(),task.proofExpiresAt())).isSameAs(task);
        assertThatThrownBy(()->task.withStarted(task.startedAt(),task.leaseExpiresAt().plusSeconds(1),task.proofExpiresAt())).isInstanceOf(IllegalArgumentException.class);
        assertThat(task.taskLockMinutes()).isZero();
    }
    @Test void sealedWindowIsExplicitImmutableAndExactlySixtySeconds() {
        var events=List.of(examples.completion(1,1,0,at));
        assertThat(new SealedBatch("batch",at,at.plusSeconds(60),events).events()).isEqualTo(events);
        assertThatThrownBy(()->new SealedBatch("batch",at,at.plusSeconds(59),events)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SealedBatch("offset-batch",at.plusSeconds(13),at.plusSeconds(73),events).windowStart()).isEqualTo(at.plusSeconds(13));
        assertThatThrownBy(()->new SealedBatch("batch",at.plusNanos(1000),at.plusSeconds(60).plusNanos(1000),events)).isInstanceOf(IllegalArgumentException.class);
    }
}
