package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.*;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SupportAnalyticsFactSnapshotTest {
    static final Instant AT=Instant.parse("2026-10-29T00:00:00Z");
    static SupportAnalyticsFactSnapshot sample() {
        var from=LocalDateTime.of(2026,10,1,0,0);
        var coverage=List.of(new Coverage(Source.CARD_TOPUP,Status.UNKNOWN,Status.UNKNOWN,Status.READY,Status.UNKNOWN,null,List.of("FOREIGN_GAP"),3,"v1"));
        var payment=new ffdd.opsconsole.finance.facade.SupportPaymentFacts.Snapshot(List.of(),List.of(new Issue(Source.CARD_TOPUP,"foreign","FOREIGN_GAP",2L)),coverage,"Asia/Shanghai",AT,
            List.of(new FirstHistory(1,Status.READY,List.of()),new FirstHistory(2,Status.UNKNOWN,List.of("FOREIGN_GAP"))),
            List.of(new CustomerCoverage(1,Source.CARD_TOPUP,Status.READY,Status.READY,Status.READY,from,List.of()),
                new CustomerCoverage(2,Source.CARD_TOPUP,Status.UNKNOWN,Status.READY,Status.UNKNOWN,null,List.of("FOREIGN_GAP"))),
            List.of(new ExcludedRows(1,Source.CARD_TOPUP,1),new ExcludedRows(2,Source.CARD_TOPUP,2)));
        var net=new NetSnapshot(List.of(new NetCustomer(1,EvidenceState.READY,List.of(),new BigDecimal("-3.123456"),BigDecimal.ZERO),
            new NetCustomer(2,EvidenceState.UNKNOWN,List.of("FOREIGN_GAP"),null,null)),List.of(),List.of());
        return new SupportAnalyticsFactSnapshot(AT,List.of(1L,2L),payment,net,List.of(attr("first",1,7L,10L),attr("second",2,8L,20L)),List.of());
    }
    static AttributionRow attr(String id,long customer,long agent,long group) {
        return new AttributionRow(id,customer,"DEPOSIT","CARD_TOPUP",1L,"source",null,null,null,"USDT",BigDecimal.ONE,
            LocalDateTime.of(2026,10,1,0,0),"Asia/Shanghai","paid_at",0,"NEW_SUCCESS","v1",agent,group,9L,"KNOWN","KNOWN","KNOWN");
    }
    @Test void subsetPreservesExactCoverageAndExclusionsWithoutForeignIssuesOrMoney() {
        var snapshot=sample();var own=snapshot.payments(List.of(1L));
        assertThat(own.issues()).isEmpty();
        assertThat(own.firstHistory()).singleElement().extracting(FirstHistory::status).isEqualTo(Status.READY);
        assertThat(own.coverage()).singleElement().satisfies(c->{
            assertThat(c.historyStatus()).isEqualTo(Status.READY);assertThat(c.observedStatus()).isEqualTo(Status.READY);
            assertThat(c.excludedFreeOrNonProductionRows()).isEqualTo(1);assertThat(c.reasons()).doesNotContain("FOREIGN_GAP");
        });
        assertThat(snapshot.contributions(List.of(1L)).customers()).singleElement()
            .satisfies(c->assertThat(c.usdt()).isEqualByComparingTo("-3.123456"));
        assertThat(own.evaluatedAt()).isEqualTo(AT);
    }
    @Test void currentManagedGroupsAndPersonalIdentityAuthorizeEveryEventProjection() {
        var snapshot=sample();var supervisor=new ReadScope(9L,ReadMode.MANAGED,null,null);
        assertThat(snapshot.eventCandidates(supervisor,Set.of(10L))).extracting(EventCandidate::factId).containsExactly("first");
        assertThat(snapshot.eventCandidates(supervisor,Set.of())).isEmpty();
        assertThat(snapshot.eventCandidates(new ReadScope(8L,ReadMode.PERSONAL,null,null),Set.of())).extracting(EventCandidate::factId).containsExactly("second");
        assertThat(snapshot.attributions(List.of("first","second"),supervisor,Set.of(10L),Set.of())).extracting(AttributionRow::factId).containsExactly("first");
        assertThat(snapshot.attributions(List.of("first","second"),supervisor,Set.of(),Set.of())).isEmpty();
    }
    @Test void newSubjectCannotBecomeFakeZeroOrCompleteHistory() {
        var snapshot=sample();
        assertThat(snapshot.payments(List.of(99L)).firstHistory()).singleElement().satisfies(h->assertThat(h.status()).isEqualTo(Status.UNKNOWN));
        assertThat(snapshot.contributions(List.of(99L)).customers()).singleElement().satisfies(c->{
            assertThat(c.state()).isEqualTo(EvidenceState.UNKNOWN);assertThat(c.usdt()).isNull();
        });
    }
    @Test void freshnessIsMeasuredFromCaptureStartIncludingComputeAndResponseTime() {
        var snapshot=sample();snapshot.requireFresh(AT.plusMillis(4500));
        assertThatThrownBy(()->snapshot.requireFresh(AT.plusMillis(4500).plusNanos(1))).hasMessageContaining("EXPIRED");
        assertThatThrownBy(()->snapshot.requireFresh(AT.minusNanos(1))).hasMessageContaining("EXPIRED");
    }
    @Test void monotonicAgeCannotBeExtendedByAClockCorrection() {
        var base=sample();var snapshot=new SupportAnalyticsFactSnapshot(AT,base.subjects(),base.payments(),base.contributions(),base.attributions(),base.proofs(),100L);
        snapshot.requireFresh(AT,4_500_000_100L);
        assertThatThrownBy(()->snapshot.requireFresh(AT,4_500_000_101L)).hasMessageContaining("EXPIRED");
    }
}
