package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.facade.SupportFundAuthBirthFacade.AuthenticatedRegistrationFacts;
import ffdd.opsconsole.finance.facade.SupportFundAuthBirthFacade.Outcome;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportFundAuthBirthAdapterTest {
    private static final String INC="12345678-1234-1234-1234-123456789abc",EVENT="0123456789abcdef0123456789abcdef";
    private final SupportFundJournalMapper mapper=mock(SupportFundJournalMapper.class);
    private final SupportFundJournalFacade binder=mock(SupportFundJournalFacade.class);
    private final SupportFundAuthBirthAdapter birth=new SupportFundAuthBirthAdapter(mapper,binder,new ObjectMapper());
    private AuthenticatedRegistrationFacts facts() {var t=LocalDateTime.of(2026,10,11,0,0);return new AuthenticatedRegistrationFacts(7,t,INC,EVENT,9,t,1);}
    private void candidate() {
        when(mapper.lockWallet(7)).thenReturn(List.of(Map.of("userId",7L,"id",17L,"incarnation",INC)));
        when(mapper.journal(INC,-1,0)).thenReturn(List.of(Map.of("observation","NEW_ZERO","id",19L,"revision",0L)));
        when(binder.beginBirth(7)).thenReturn(mock(SupportFundJournalFacade.Before.class));
        when(binder.bind(any(),any())).thenReturn(new SupportFundJournalFacade.BoundReceipt(21,"AUTH_FUND_BIRTH:7","a".repeat(64),1));
    }
    @Test void actualFinanceFactsAreRecordedWithOneRevisionZeroLegAndNoContribution() throws Exception {
        candidate();assertThat(birth.record(facts())).isEqualTo(Outcome.BOUND);
        var r=ArgumentCaptor.forClass(SupportFundJournalFacade.ReceiptInput.class);verify(binder).bind(any(),r.capture());
        assertThat(r.getValue().operationKey()).isEqualTo("AUTH_FUND_BIRTH:7");assertThat(r.getValue().orderedEventsJson()).isEqualTo("[]");
        assertThat(r.getValue().sourceKey()).isEqualTo(EVENT);
        var snapshot=new ObjectMapper().readTree(r.getValue().sourceSnapshotJson());
        assertThat(snapshot.path("wallet").path("id").asLong()).isEqualTo(17);
        assertThat(snapshot.path("birthJournal").path("id").asLong()).isEqualTo(19);
    }
    @Test void orphanWalletWithAffectedRowsOneStillStaysUnknownAndDoesNotEnterBirthGuard() {
        when(mapper.lockWallet(7)).thenReturn(List.of(Map.of("userId",7L,"id",17L,"incarnation","22345678-1234-1234-1234-123456789abc")));
        assertThat(birth.record(facts())).isEqualTo(Outcome.UNKNOWN);verifyNoInteractions(binder);verify(mapper,never()).journal(any(),anyLong(),anyLong());
    }
    @Test void unprovenRebuiltWalletAndMissingHistoryRemainUnknown() {
        candidate();when(mapper.journal(INC,-1,0)).thenReturn(List.of(Map.of("observation","NEW_UNPROVEN")),List.of());
        assertThat(birth.record(facts())).isEqualTo(Outcome.UNKNOWN);assertThat(birth.record(facts())).isEqualTo(Outcome.UNKNOWN);verifyNoInteractions(binder);
    }
    @Test void zeroCandidatesDoNotRelaxActualBirthGuardAndFailuresPropagate() {
        candidate();var failure=new IllegalStateException("INVALID_ZERO_WALLET_CANDIDATE");when(binder.beginBirth(7)).thenThrow(failure);
        assertThatThrownBy(()->birth.record(facts())).isSameAs(failure);verify(binder,never()).bind(any(),any());
    }
    @Test void sqlFailureIsNotAnUnknownHistoricalSource() {
        var failure=new org.springframework.dao.DataAccessResourceFailureException("database unavailable");when(mapper.lockWallet(7)).thenThrow(failure);
        assertThatThrownBy(()->birth.record(facts())).isSameAs(failure);verifyNoInteractions(binder);
    }
    @Test void missingWalletOrWrongCustomerCannotBeRecorded() {
        when(mapper.lockWallet(7)).thenReturn(List.of(),List.of(Map.of("userId",8L)));
        assertThatThrownBy(()->birth.record(facts())).hasMessage("AUTH_FUND_WALLET_MISSING_OR_AMBIGUOUS");
        assertThatThrownBy(()->birth.record(facts())).hasMessage("AUTH_FUND_WALLET_CUSTOMER_MISMATCH");verifyNoInteractions(binder);
    }
    @Test void boundReceiptMustContainOnlyTheBirthLeg() {
        candidate();when(binder.bind(any(),any())).thenReturn(new SupportFundJournalFacade.BoundReceipt(21,"AUTH_FUND_BIRTH:7","a".repeat(64),2));
        assertThatThrownBy(()->birth.record(facts())).hasMessage("AUTH_FUND_BIRTH_LEG_MISMATCH");
    }
}
