package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SupportAnalyticsStatsTest {
    private static final LocalDateTime FROM=LocalDateTime.of(2026,10,9,0,0);

    @Test void zoneIsRequiredAndCurrentAssetCannotPretendToBeAnHistoricalWindow() {
        assertThatThrownBy(() -> query(Basis.CURRENT_ASSET,null,null,null,null)).hasMessage("SUPPORT_ANALYTICS_QUERY_INVALID");
        assertThatThrownBy(() -> query(Basis.CURRENT_ASSET,null,null,"missing/zone",null)).hasMessage("SUPPORT_ANALYTICS_QUERY_INVALID");
        assertThatThrownBy(() -> query(Basis.CURRENT_ASSET,FROM,FROM.plusDays(1),"UTC",null)).hasMessage("SUPPORT_ANALYTICS_WINDOW_INVALID");
        assertThatThrownBy(() -> query(Basis.CURRENT_CUSTOMER_HISTORY,FROM,FROM.plusDays(1),"UTC",null)).hasMessage("SUPPORT_ANALYTICS_WINDOW_INVALID");
        assertThat(query(Basis.CURRENT_ASSET,null,null,"UTC",null).mode()).isNull();
    }

    @Test void periodHasAnOrderedBoundedWindowAndExplicitCurrency() {
        assertThatThrownBy(() -> query(Basis.PERIOD_EVENT,null,FROM,"UTC",null)).hasMessage("SUPPORT_ANALYTICS_WINDOW_INVALID");
        assertThatThrownBy(() -> query(Basis.PERIOD_EVENT,FROM,FROM,"UTC",null)).hasMessage("SUPPORT_ANALYTICS_WINDOW_INVALID");
        assertThatThrownBy(() -> query(Basis.PERIOD_EVENT,FROM,FROM.minusDays(1),"UTC",null)).hasMessage("SUPPORT_ANALYTICS_WINDOW_INVALID");
        assertThatThrownBy(() -> query(Basis.PERIOD_EVENT,FROM,FROM.plusDays(1),"UTC","usdt")).hasMessage("SUPPORT_ANALYTICS_CURRENCY_INVALID");
        assertThatThrownBy(() -> new Query(ReadMode.ALL,0L,null,Basis.CURRENT_ASSET,null,null,"UTC",null)).hasMessage("SUPPORT_ANALYTICS_QUERY_INVALID");
        assertThat(query(Basis.PERIOD_EVENT,FROM,FROM.plusDays(1),"UTC","USDT").currency()).isEqualTo("USDT");
    }

    @Test void daylightSavingBusinessDayIsTwentyThreeHoursButAmbiguousOrMissingEndpointsAreRejected() {
        LocalDateTime day=LocalDateTime.of(2026,3,8,0,0);
        Query query=query(Basis.PERIOD_EVENT,day,day.plusDays(1),"America/New_York",null);
        ZoneId zone=ZoneId.of(query.businessZone());
        assertThat(Duration.between(query.fromInclusive().atZone(zone).toInstant(),query.toExclusive().atZone(zone).toInstant()).toHours()).isEqualTo(23);
        assertThatThrownBy(() -> query(Basis.PERIOD_EVENT,day.withHour(2).withMinute(30),day.withHour(4),"America/New_York",null))
            .hasMessage("SUPPORT_ANALYTICS_WINDOW_INVALID");
        LocalDateTime overlap=LocalDateTime.of(2026,11,1,1,30);
        assertThatThrownBy(() -> query(Basis.PERIOD_EVENT,overlap,overlap.withHour(3),"America/New_York",null))
            .hasMessage("SUPPORT_ANALYTICS_WINDOW_INVALID");
    }

    @Test void resultCollectionsCannotBeMutatedAfterTheirUnknownHistoryBoundaryIsConstructed() {
        var reasons=new ArrayList<>(List.of("COMPLETE_HISTORY_NOT_PROVEN"));
        FirstSelection selection=new FirstSelection(null,Status.UNKNOWN,reasons);
        reasons.clear();
        assertThat(selection.reasons()).containsExactly("COMPLETE_HISTORY_NOT_PROVEN");
        assertThat(selection.state()).isEqualTo(FirstState.UNKNOWN);
        assertThatThrownBy(() -> selection.reasons().clear()).isInstanceOf(UnsupportedOperationException.class);
        Count first=new Count(0L,null,Status.UNKNOWN);
        assertThat(first.observedValue()).isZero();assertThat(first.confirmedValue()).isNull();
    }
    @Test void financeCompatibilityConstructorsPreserveUnknownAndCopyExplicitFirstHistory() {
        var old=new ffdd.opsconsole.finance.facade.SupportPaymentFacts.Snapshot(List.of(),List.of(),List.of(),"Asia/Shanghai",java.time.Instant.EPOCH);
        assertThat(old.firstHistory()).isEmpty();
        var issue=new ffdd.opsconsole.finance.facade.SupportPaymentFacts.Issue(ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source.CARD_TOPUP,null,"SOURCE_READ_FAILED");
        assertThat(issue.customerId()).isNull();
        var reasons=new ArrayList<>(List.of("NEW_ACCOUNT_BIRTH_NOT_PROVEN"));
        var unknown=new ffdd.opsconsole.finance.facade.SupportPaymentFacts.FirstHistory(7,ffdd.opsconsole.finance.facade.SupportPaymentFacts.Status.UNKNOWN,reasons);
        reasons.clear();assertThat(unknown.reasons()).containsExactly("NEW_ACCOUNT_BIRTH_NOT_PROVEN");
        assertThatThrownBy(()->unknown.reasons().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Query query(Basis basis,LocalDateTime from,LocalDateTime to,String zone,String currency) {
        return new Query(null,null,null,basis,from,to,zone,currency);
    }
    @Test void oldCustomerAndResultConstructorsCannotTurnMissingCurrentMetricsIntoAvailableZero() {
        var old=new Customer(1,Category.BOUND,Placement.GROUPED,false,new FirstSelection(null,Status.UNKNOWN,List.of("HISTORY_UNVERIFIED")));
        assertThat(old.metrics().lifetimeBasis()).isEqualTo(Basis.CURRENT_CUSTOMER_HISTORY);assertThat(old.metrics().lifetimeStatus()).isEqualTo(Status.UNAVAILABLE);
        assertThat(old.metrics().invitations().directCustomers().observedValue()).isNull();assertThat(old.metrics().devices().held().confirmedValue()).isNull();
        var result=new Result(query(Basis.CURRENT_ASSET,null,null,"UTC",null),new CurrentScope(ReadMode.PERSONAL,null,null,Status.AVAILABLE,1L,1L,0L,0L,List.of()),
            List.of(old),new FinancialSummary(Status.UNAVAILABLE,List.of(),SupportAnalyticsStats.unavailableCount(),List.of(),List.of()),
            new RestrictedSummary(SupportAnalyticsStats.unavailableCount(),SupportAnalyticsStats.unavailableCount()),List.of(),java.time.Instant.EPOCH,List.of());
        assertThat(result.currentMetrics().status()).isEqualTo(Status.UNAVAILABLE);assertThat(result.personnel().groups().confirmedValue()).isNull();assertThat(result.groups()).isEmpty();
    }
    @Test void summaryCollectionsAreCopiesAndAcquisitionDoesNotClaimUnprovenGiftOrFreeTrial() {
        var currencies=new ArrayList<InvitationCurrencyTotal>();var reasons=new ArrayList<>(List.of("ACQUISITION_NOT_PROVEN"));
        var invitation=new InvitationSummary(new Count(0L,0L,Status.AVAILABLE),new Count(0L,0L,Status.AVAILABLE),currencies,Status.AVAILABLE,reasons);
        reasons.clear();assertThat(invitation.reasons()).containsExactly("ACQUISITION_NOT_PROVEN");
        assertThatThrownBy(()->invitation.descendantDeposits().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(Acquisition.values()).containsExactly(Acquisition.PAID_PURCHASE,Acquisition.UNKNOWN);
        assertThat(SupportAnalyticsStats.unavailablePersonnel().accounts()).isEmpty();assertThat(SupportAnalyticsStats.unavailablePersonnel().people().observedValue()).isNull();
    }
}
