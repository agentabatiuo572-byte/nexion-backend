package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.finance.mapper.SupportFundsReadMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SupportFundsNetBatchTest {
    private final SupportFundReadService reader=mock(SupportFundReadService.class);
    private final SupportFundsReadMapper mapper=mock(SupportFundsReadMapper.class);
    private final SupportFundsReadService service=new SupportFundsReadService(mapper,reader);
    private final List<List<Long>> reads=new ArrayList<>();
    @BeforeEach void snapshot() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        doAnswer(call->{
            Collection<Long> input=call.getArgument(0);
            if(input==null || input.stream().anyMatch(id->id==null || id<=0))throw new IllegalArgumentException("Explicit positive customer scope required");
            List<Long> ids=List.copyOf(input);reads.add(ids);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            return ids.size()>4096?unknown(ids,"CLOSURE_LIMIT"):known(ids);
        }).when(reader).read(any());
    }
    @AfterEach void clearSnapshot(){TransactionSynchronizationManager.clear();}
    @Test void allStaffAggregateCanRead8420IndependentRootsWithoutTreatingThemAsOneOversizedComponent() {
        var ids=new ArrayList<>(LongStream.rangeClosed(1,8420).boxed().toList());ids.add(1L);
        var result=service.readNetContributions(ids);
        assertThat(result.customers()).hasSize(8420).allSatisfy(row->assertThat(row.state()).isEqualTo(EvidenceState.READY));
        assertThat(result.customers()).extracting(NetCustomer::customerId).containsExactlyElementsOf(LongStream.rangeClosed(1,8420).boxed().toList());
        assertThat(result.origins()).hasSize(8420).extracting(ContributionOrigin::contributionId).doesNotHaveDuplicates();
        assertThat(result.changes()).hasSize(8420);
        assertThat(result.customers().get(8419).usdt()).isEqualByComparingTo("8420.123456");
        assertThat(result.customers().get(8419).nex()).isEqualByComparingTo("2.654321");
        assertThat(reads).hasSizeGreaterThan(1).allSatisfy(batch->assertThat(batch.size()).isBetween(1,1000));
        verifyNoInteractions(mapper);
    }
    @Test void unrelatedRootsDoNotPushALegal4096CustomerConnectedComponentOverItsLimit() {
        doAnswer(call->{
            List<Long> ids=List.copyOf(call.getArgument(0,Collection.class));reads.add(ids);
            return ids.contains(7L) && ids.size()+4095>4096?unknown(ids,"CLOSURE_LIMIT"):known(ids);
        }).when(reader).read(any());
        var result=service.readNetContributions(LongStream.rangeClosed(1,10).boxed().toList());
        assertThat(result.customers()).hasSize(10).allSatisfy(row->assertThat(row.state()).isEqualTo(EvidenceState.READY));
        assertThat(result.origins()).hasSize(10).extracting(ContributionOrigin::contributionId).doesNotHaveDuplicates();
        assertThat(result.changes()).hasSize(10);assertThat(reads).contains(List.of(7L));
    }
    @Test void aTrulyOversizedComponentStaysUnknownWithoutHidingIndependentCustomers() {
        doAnswer(call->{
            List<Long> ids=List.copyOf(call.getArgument(0,Collection.class));reads.add(ids);
            return ids.contains(7L)?unknown(ids,"CLOSURE_LIMIT"):known(ids);
        }).when(reader).read(any());
        var result=service.readNetContributions(LongStream.rangeClosed(1,10).boxed().toList());
        assertThat(result.customers()).filteredOn(row->row.customerId()==7).singleElement().satisfies(row->{
            assertThat(row.state()).isEqualTo(EvidenceState.UNKNOWN);assertThat(row.usdt()).isNull();assertThat(row.nex()).isNull();
            assertThat(row.reasons()).containsExactly("CLOSURE_LIMIT");
        });
        assertThat(result.customers()).filteredOn(row->row.customerId()!=7).hasSize(9).allSatisfy(row->assertThat(row.state()).isEqualTo(EvidenceState.READY));
        assertThat(result.origins()).hasSize(9);assertThat(result.changes()).hasSize(9);
    }
    @Test void sourceUnknownIsPreservedWithoutRetryOrInventedZero() {
        doReturn(unknown(List.of(1L,2L),"SOURCE")).when(reader).read(any());
        var result=service.readNetContributions(List.of(2L,1L,2L));
        assertThat(result.customers()).hasSize(2).allSatisfy(row->{assertThat(row.usdt()).isNull();assertThat(row.nex()).isNull();assertThat(row.reasons()).containsExactly("SOURCE");});
        assertThat(result.origins()).isEmpty();assertThat(result.changes()).isEmpty();verify(reader).read(List.of(1L,2L));
    }
    @Test void invalidScopesAreRejectedBeforeAnySourceQuery() {
        assertThatThrownBy(()->service.readNetContributions(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.readNetContributions(Arrays.asList(1L,null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.readNetContributions(List.of(0L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.readNetContributions(List.of(-1L))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(reader,mapper);
    }
    @Test void emptyScopeStillUsesTheExistingPureEmptyRead() {
        TransactionSynchronizationManager.clear();doReturn(new NetSnapshot(List.of(),List.of(),List.of())).when(reader).read(List.of());
        var result=service.readNetContributions(List.of());assertThat(result.customers()).isEmpty();verify(reader).read(List.of());verifyNoInteractions(mapper);
    }
    @Test void aLaterUnexpectedFailureCannotPublishTheAlreadyReadPartialAggregate() {
        doAnswer(call->{
            List<Long> ids=List.copyOf(call.getArgument(0,Collection.class));
            if(ids.contains(1001L))throw new IllegalStateException("source failure");return known(ids);
        }).when(reader).read(any());
        assertThatThrownBy(()->service.readNetContributions(LongStream.rangeClosed(1,2000).boxed().toList())).hasMessage("source failure");
    }
    private static NetSnapshot unknown(List<Long> ids,String reason) {
        return new NetSnapshot(ids.stream().map(id->new NetCustomer(id,EvidenceState.UNKNOWN,List.of(reason),null,null)).toList(),List.of(),List.of());
    }
    private static NetSnapshot known(List<Long> ids) {
        var at=LocalDateTime.of(2026,10,11,12,0);
        return new NetSnapshot(ids.stream().map(id->new NetCustomer(id,EvidenceState.READY,List.of(),new BigDecimal(id+".123456"),new BigDecimal("2.654321"))).toList(),
            ids.stream().map(id->new ContributionOrigin("C:"+id,id,"P:"+id,at,"Asia/Shanghai",0,"SRC:"+id)).toList(),
            ids.stream().map(id->new ContributionChange("C:"+id,id,"USDT",new BigDecimal("1.123456"),at,"Asia/Shanghai",0,"SRC:"+id)).toList());
    }
}
