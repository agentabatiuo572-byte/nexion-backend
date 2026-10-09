package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.finance.mapper.SupportFundsReadMapper;
import ffdd.opsconsole.finance.mapper.SupportFundsReadMapper.WalletRow;
import ffdd.opsconsole.finance.mapper.SupportFundsReadMapper.WithdrawalRow;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SupportFundsReadServiceTest {
    private static final LocalDateTime AT=LocalDateTime.of(2026,10,9,12,0,0,123456000);
    private final SupportFundsReadMapper mapper=mock(SupportFundsReadMapper.class);
    private final SupportFundsReadService service=new SupportFundsReadService(mapper);
    @BeforeEach void callerSnapshot() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        when(mapper.wallets(any())).thenReturn(List.of(wallet(1)));
        when(mapper.withdrawals(any())).thenReturn(List.of());
    }
    @AfterEach void clearCallerSnapshot() {TransactionSynchronizationManager.clear();}

    @Test void emptyScopeIsPureEmptyEvidenceAndNeverQueriesAllCustomers() {
        TransactionSynchronizationManager.clear();var result=service.readCurrent(List.of());
        assertThat(result.customerIds()).isEmpty();assertThat(result.wallets().rows()).isEmpty();assertThat(result.withdrawals().rows()).isEmpty();
        assertThat(result.wallets().state()).isEqualTo(ReadState.READY);assertThat(result.withdrawals().state()).isEqualTo(ReadState.READY);
        assertThat(result.withdrawals().historicalEnvironmentStatus()).isEqualTo(CoverageState.UNKNOWN);
        assertThat(result.withdrawals().eventOwnershipStatus()).isEqualTo(CoverageState.UNKNOWN);verifyNoInteractions(mapper);
    }
    @Test void duplicateIdsBecomeOneSortedBatchPerIndependentSourceWithoutChangingCallerSnapshot() {
        var expected=List.of(1L,2L);when(mapper.wallets(expected)).thenReturn(List.of(wallet(2),wallet(1),wallet(1)));
        var result=service.readCurrent(List.of(2L,1L,2L));
        assertThat(result.customerIds()).containsExactly(1L,2L);assertThat(result.wallets().rows()).extracting(WalletEvidence::customerId).containsExactly(1L,2L);
        verify(mapper).wallets(expected);verify(mapper).withdrawals(expected);verifyNoMoreInteractions(mapper);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
    }
    @Test void invalidScopeFailsBeforeAnyRead() {
        assertThatThrownBy(()->service.readCurrent(null)).hasMessage("INVALID_SUPPORT_FUNDS_SCOPE");
        assertThatThrownBy(()->service.readCurrent(Arrays.asList(1L,null))).hasMessage("INVALID_SUPPORT_FUNDS_SCOPE");
        assertThatThrownBy(()->service.readCurrent(List.of(0L))).hasMessage("INVALID_SUPPORT_FUNDS_SCOPE");
        assertThatThrownBy(()->service.readCurrent(List.of(-1L))).hasMessage("INVALID_SUPPORT_FUNDS_SCOPE");verifyNoInteractions(mapper);
    }
    @Test void nonemptyScopeRequiresTheCallersActiveRrAndNeverStartsATransaction() throws Exception {
        TransactionSynchronizationManager.clear();assertThatThrownBy(()->service.readCurrent(List.of(1L))).hasMessage("SUPPORT_FUNDS_REPEATABLE_READ_REQUIRED");
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        assertThatThrownBy(()->service.readCurrent(List.of(1L))).hasMessage("SUPPORT_FUNDS_REPEATABLE_READ_REQUIRED");verifyNoInteractions(mapper);
        assertThat(SupportFundsReadService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(SupportFundsReadService.class.getMethod("readCurrent",java.util.Collection.class).getAnnotation(Transactional.class)).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"WALLET","WITHDRAWAL"})
    void foreignRowsHardFailBeforeQualityChecksOrFilters(String domain) {
        if(domain.equals("WALLET"))when(mapper.wallets(any())).thenReturn(Arrays.asList(null,new WalletRow(null,9L,null,null,null,null,null)));
        else when(mapper.withdrawals(any())).thenReturn(Arrays.asList(null,new WithdrawalRow(null,9L,null,null,null,null,null,null,null,null)));
        assertThatThrownBy(()->service.readCurrent(List.of(1L))).hasMessage("INVALID_SUPPORT_FUNDS_SCOPE");
        if(domain.equals("WALLET"))verify(mapper,never()).withdrawals(any());
    }
    @Test void duplicateConflictingWalletIdentityAndWithdrawalProjectionCannotChooseOneRow() {
        when(mapper.wallets(any())).thenReturn(List.of(wallet(1),new WalletRow(101L,1L,2L,BigDecimal.ZERO,BigDecimal.ZERO,AT,AT)));
        assertThatThrownBy(()->service.readCurrent(List.of(1L))).hasMessage("CONFLICTING_SUPPORT_FUNDS_WALLET_ROW");
        when(mapper.wallets(any())).thenReturn(List.of(wallet(1)));
        when(mapper.withdrawals(any())).thenReturn(List.of(withdrawal(201,1,"USDT","10","1","9","CONFIRMED",AT),withdrawal(201,1,"USDT","11","1","10","CONFIRMED",AT)));
        assertThatThrownBy(()->service.readCurrent(List.of(1L))).hasMessage("CONFLICTING_SUPPORT_FUNDS_WITHDRAWAL_ROW");
    }
    @Test void oneWalletIdCannotBelongToTwoAuthorizedCustomersAndIdenticalWithdrawalsDeduplicate() {
        when(mapper.wallets(any())).thenReturn(List.of(wallet(1),new WalletRow(101L,2L,1L,BigDecimal.ZERO,BigDecimal.ZERO,AT,AT)));
        assertThatThrownBy(()->service.readCurrent(List.of(1L,2L))).hasMessage("CONFLICTING_SUPPORT_FUNDS_WALLET_ROW");
        when(mapper.wallets(any())).thenReturn(List.of(wallet(1)));var row=withdrawal(201,1,"USDT","10","1","9","CONFIRMED",AT);
        when(mapper.withdrawals(any())).thenReturn(List.of(row,row));assertThat(service.readCurrent(List.of(1L)).withdrawals().rows()).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"WALLET","WITHDRAWAL","BOTH"})
    void dataAccessFailureIsIndependentAndNeverBecomesKnownZero(String failed) {
        if(!failed.equals("WITHDRAWAL"))when(mapper.wallets(any())).thenThrow(new DataAccessResourceFailureException("private address/customer"));
        if(!failed.equals("WALLET"))when(mapper.withdrawals(any())).thenThrow(new DataAccessResourceFailureException("private chain transaction"));
        else when(mapper.withdrawals(any())).thenReturn(List.of(withdrawal(201,1,"USDT","10","1","9","CONFIRMED",AT)));
        var result=service.readCurrent(List.of(1L));
        assertThat(result.wallets().state()).isEqualTo(failed.equals("WITHDRAWAL")?ReadState.READY:ReadState.FAILED);
        assertThat(result.withdrawals().state()).isEqualTo(failed.equals("WALLET")?ReadState.READY:ReadState.FAILED);
        if(!failed.equals("WITHDRAWAL"))assertThat(result.wallets().rows()).singleElement().satisfies(w->{assertThat(w.usdtAvailable()).isNull();assertThat(w.nexAvailable()).isNull();assertThat(w.state()).isEqualTo(EvidenceState.UNKNOWN);assertThat(w.reasons()).containsExactly(Reason.WALLET_READ_FAILED);});
        assertThat(result.toString()).doesNotContain("private address","private chain","customer/");
        verify(mapper).wallets(List.of(1L));verify(mapper).withdrawals(List.of(1L));
    }
    @Test void missingWalletIsUnknownWhileAnActualZeroBalanceIsKnownAndPrecisionIsUnchanged() {
        var actual=new WalletRow(101L,1L,0L,new BigDecimal("0.000000"),new BigDecimal("999999999999.123456"),AT,AT);
        when(mapper.wallets(any())).thenReturn(List.of(actual));var result=service.readCurrent(List.of(1L,2L));
        assertThat(result.wallets().state()).isEqualTo(ReadState.PARTIAL);
        assertThat(result.wallets().rows()).filteredOn(w->w.customerId()==1).singleElement().satisfies(w->{assertThat(w.state()).isEqualTo(EvidenceState.READY);assertThat(w.usdtAvailable()).isEqualTo(new BigDecimal("0.000000"));assertThat(w.nexAvailable()).isEqualTo(new BigDecimal("999999999999.123456"));});
        assertThat(result.wallets().rows()).filteredOn(w->w.customerId()==2).singleElement().satisfies(w->{assertThat(w.walletId()).isNull();assertThat(w.version()).isNull();assertThat(w.usdtAvailable()).isNull();assertThat(w.nexAvailable()).isNull();assertThat(w.reasons()).containsExactly(Reason.WALLET_MISSING);});
        when(mapper.wallets(any())).thenReturn(List.of());assertThat(service.readCurrent(List.of(1L)).wallets().state()).isEqualTo(ReadState.UNKNOWN);
    }
    @Test void incompleteWalletDoesNotFillOrConfirmItsMissingCurrencyOrVersion() {
        when(mapper.wallets(any())).thenReturn(List.of(new WalletRow(101L,1L,null,new BigDecimal("1.123456"),null,AT,AT)));
        var result=service.readCurrent(List.of(1L));assertThat(result.wallets().state()).isEqualTo(ReadState.UNKNOWN);
        assertThat(result.wallets().rows()).singleElement().satisfies(w->{assertThat(w.usdtAvailable()).isEqualTo(new BigDecimal("1.123456"));assertThat(w.nexAvailable()).isNull();assertThat(w.reasons()).contains(Reason.WALLET_IDENTITY_UNVERIFIED,Reason.NEX_BALANCE_UNVERIFIED);});
    }
    @ParameterizedTest
    @CsvSource({"CONFIRMED,SUCCESS","SUCCESS,SUCCESS","SUBMITTED,PROCESSING","PENDING,PROCESSING","REVIEW_PENDING,PROCESSING","REVIEWING,PROCESSING",
        "EXTENDED_HOLD,PROCESSING","DELAYED,PROCESSING","REVIEW_PASSED,PROCESSING","PENDING_CHAIN,PROCESSING","PROCESSING,PROCESSING","SENT,PROCESSING","CHAIN_SUBMITTED,PROCESSING",
        "REVIEW_REJECTED,NOT_SUCCESSFUL","REJECTED,NOT_SUCCESSFUL","ADDRESS_INVALID,NOT_SUCCESSFUL","TX_FAILED,NOT_SUCCESSFUL","FAILED,NOT_SUCCESSFUL","REFUNDED,NOT_SUCCESSFUL",
        "FROZEN,HELD","TX_ORPHANED,RECOVERY","DEAD,RECOVERY","COMPLETED,UNKNOWN","CANCELLED,UNKNOWN","new-status,UNKNOWN"})
    void canonicalWithdrawalStatesRetainSuccessProcessingFailuresHoldsRecoveryAndUnknown(String status,WithdrawalState expected) {
        when(mapper.withdrawals(any())).thenReturn(List.of(withdrawal(201,1,"USDT","10.123456","0.123456","10.000000",status,expected==WithdrawalState.SUCCESS?AT:null)));
        var result=service.readCurrent(List.of(1L));assertThat(result.withdrawals().rows()).singleElement().satisfies(w->{
            assertThat(w.state()).isEqualTo(expected);assertThat(w.status()).isEqualTo(status);assertThat(w.canonicalStatus()).isEqualTo(D2WithdrawalStateMachine.canonical(status));
            assertThat(w.principal()).isEqualTo(new BigDecimal("10.123456"));assertThat(w.actualFee()).isEqualTo(new BigDecimal("0.123456"));assertThat(w.net()).isEqualTo(new BigDecimal("10.000000"));
        });
        assertThat(result.withdrawals().historicalEnvironmentStatus()).isEqualTo(CoverageState.UNKNOWN);assertThat(result.withdrawals().eventOwnershipStatus()).isEqualTo(CoverageState.UNKNOWN);
    }
    @ParameterizedTest
    @ValueSource(strings={"NO_COMPLETED","NO_FEE","NO_NET","ZERO","NEGATIVE","BAD_FEE","BAD_NET","UNBALANCED","FUTURE_COMPLETED","PRECISION","UNKNOWN_CURRENCY","PROCESSING_COMPLETED"})
    void incompleteContradictorySuccessAndProcessingRemainUnknownRatherThanSuccessfulZero(String defect) {
        String principal=defect.equals("ZERO")?"0":defect.equals("NEGATIVE")?"-10":defect.equals("PRECISION")?"10.0000001":"10";
        String fee=defect.equals("NO_FEE")?null:defect.equals("BAD_FEE")?"-1":"1";
        String net=defect.equals("NO_NET")?null:defect.equals("BAD_NET")?"-9":defect.equals("UNBALANCED")?"8":"9";
        LocalDateTime completed=defect.equals("NO_COMPLETED")?null:defect.equals("FUTURE_COMPLETED")?AT.plusSeconds(1):AT;
        when(mapper.withdrawals(any())).thenReturn(List.of(withdrawal(201,1,defect.equals("UNKNOWN_CURRENCY")?null:"USDT",principal,fee,net,defect.equals("PROCESSING_COMPLETED")?"PROCESSING":"CONFIRMED",completed)));
        var result=service.readCurrent(List.of(1L));assertThat(result.withdrawals().state()).isEqualTo(ReadState.UNKNOWN);
        assertThat(result.withdrawals().rows()).singleElement().satisfies(w->{assertThat(w.state()).isEqualTo(WithdrawalState.UNKNOWN);assertThat(w.reasons()).isNotEmpty();});
    }
    @Test void multiCurrencyWithdrawalRowsKeepSourceAmountsAndLocalTimeWithoutAnyConversionOrTotals() {
        when(mapper.withdrawals(any())).thenReturn(List.of(withdrawal(201,1,"USDT","10.123456","0.123456","10.000000","CONFIRMED",AT),
            withdrawal(202,1,"NEX","999999999999.123456","0.000001","999999999999.123455","SUCCESS",AT)));
        var result=service.readCurrent(List.of(1L));assertThat(result.businessZone()).isEqualTo("Asia/Shanghai");
        assertThat(result.withdrawals().rows()).extracting(WithdrawalEvidence::currency).containsExactly("USDT","NEX");
        assertThat(result.withdrawals().rows()).allSatisfy(w->{assertThat(w.state()).isEqualTo(WithdrawalState.SUCCESS);assertThat(w.completedAt()).isEqualTo(AT);assertThat(w.principal()).isEqualByComparingTo(w.actualFee().add(w.net()));});
    }
    @Test void partialWithdrawalEvidenceRetainsKnownRowsWithoutClaimingCompleteHistoryOrSuccessfulZero() {
        when(mapper.withdrawals(any())).thenReturn(List.of(withdrawal(201,1,"USDT","10","1","9","CONFIRMED",AT),
            withdrawal(202,1,"USDT","10",null,null,"SUCCESS",AT)));
        var read=service.readCurrent(List.of(1L)).withdrawals();assertThat(read.state()).isEqualTo(ReadState.PARTIAL);
        assertThat(read.rows()).extracting(WithdrawalEvidence::state).containsExactly(WithdrawalState.SUCCESS,WithdrawalState.UNKNOWN);
        assertThat(read.rows().get(1).actualFee()).isNull();assertThat(read.rows().get(1).net()).isNull();
        assertThat(read.historicalEnvironmentStatus()).isEqualTo(CoverageState.UNKNOWN);assertThat(read.eventOwnershipStatus()).isEqualTo(CoverageState.UNKNOWN);
    }
    @Test void nullResponsesUnexpectedExceptionsAndConflictingStatementTimesAreNotSwallowed() {
        when(mapper.wallets(any())).thenReturn(null);assertThatThrownBy(()->service.readCurrent(List.of(1L))).hasMessage("INVALID_SUPPORT_FUNDS_WALLET_RESPONSE");
        when(mapper.wallets(any())).thenThrow(new IllegalStateException("programming fault"));assertThatThrownBy(()->service.readCurrent(List.of(1L))).hasMessage("programming fault");
        doReturn(List.of(wallet(1),new WalletRow(102L,2L,1L,BigDecimal.ZERO,BigDecimal.ZERO,AT,AT.plusSeconds(1)))).when(mapper).wallets(any());
        assertThatThrownBy(()->service.readCurrent(List.of(1L,2L))).hasMessage("INVALID_SUPPORT_FUNDS_OBSERVATION_TIME");
        when(mapper.wallets(any())).thenReturn(List.of(wallet(1)));when(mapper.withdrawals(any())).thenReturn(null);
        assertThatThrownBy(()->service.readCurrent(List.of(1L))).hasMessage("INVALID_SUPPORT_FUNDS_WITHDRAWAL_RESPONSE");
    }
    @Test void evidenceListsAreImmutableAndNoFacadeComponentCanCarryAddressTransactionOrGroupOwnership() {
        var result=service.readCurrent(List.of(1L));assertThatThrownBy(()->result.customerIds().add(2L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->result.wallets().rows().clear()).isInstanceOf(UnsupportedOperationException.class);
        var components=new ArrayList<String>();for(Class<?> type:SupportFundsReadFacade.class.getDeclaredClasses())if(type.isRecord())Arrays.stream(type.getRecordComponents()).map(c->c.getName()).forEach(components::add);
        assertThat(components).doesNotContain("address","targetAddress","chain","chainTxHash","phone","name","agentAdminId","groupId","ownerAdminId","pendingWithdraw","cumulativeDepositUsdt");
        assertThat(CoverageState.values()).containsExactly(CoverageState.UNKNOWN);
    }
    private static WalletRow wallet(long customer) {return new WalletRow(100+customer,customer,1L,new BigDecimal("1.123456"),new BigDecimal("2.654321"),AT,AT);}
    private static WithdrawalRow withdrawal(long id,long customer,String currency,String principal,String fee,String net,String status,LocalDateTime completed) {
        return new WithdrawalRow(id,customer,currency,decimal(principal),decimal(fee),decimal(net),status,completed,AT,AT);
    }
    private static BigDecimal decimal(String value) {return value==null?null:new BigDecimal(value);}
}
