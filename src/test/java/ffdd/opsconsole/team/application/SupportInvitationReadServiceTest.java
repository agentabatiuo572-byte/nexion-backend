package ffdd.opsconsole.team.application;

import ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Completeness;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Reason;
import ffdd.opsconsole.team.mapper.SupportInvitationReadMapper;
import ffdd.opsconsole.team.mapper.SupportInvitationReadMapper.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SupportInvitationReadServiceTest {
    private final SupportInvitationReadMapper mapper = mock(SupportInvitationReadMapper.class);
    private final SupportInvitationReadService service = new SupportInvitationReadService(mapper);

    @Test void invalidOrImplicitRootScopeNeverQueriesTheSource() {
        for (var roots : Arrays.asList(null, List.<Long>of(), List.of(0L), List.of(-1L), Arrays.asList(1L, null)))
            assertThatThrownBy(() -> service.readInvitations(roots))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_INVITATION_ROOT_SCOPE");
        verifyNoInteractions(mapper);
    }

    @Test void deepChainIsReadUntilEmptyAndNeverCountsTheRoot() {
        var rows = new ArrayList<Row>();
        rows.add(row(1, null));
        for (long id = 2; id <= 13; id++) rows.add(row(id, id - 1));
        graph(rows);
        var result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.rootSandbox()).isZero();
        assertThat(result.directCustomerIds()).containsExactly(2L);
        assertThat(result.descendantCustomerIds()).containsExactly(2L,3L,4L,5L,6L,7L,8L,9L,10L,11L,12L,13L);
        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(result.reasons()).isEmpty();
        verify(mapper).children(List.of(13L));
        assertThatThrownBy(() -> result.descendantCustomerIds().add(14L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void overlappingRootsAndDuplicateInputsShareFrontierReadsButDeduplicateEachRootIndependently() {
        graph(List.of(row(1,null), row(2,1L), row(3,2L), row(4,1L)));
        var result = service.readInvitations(List.of(2L,1L,2L));
        assertThat(result).extracting(r -> r.rootCustomerId()).containsExactly(1L,2L);
        assertThat(result.get(0).directCustomerIds()).containsExactly(2L,4L);
        assertThat(result.get(0).descendantCustomerIds()).containsExactly(2L,3L,4L);
        assertThat(result.get(1).directCustomerIds()).containsExactly(3L);
        assertThat(result.get(1).descendantCustomerIds()).containsExactly(3L);
        assertThat(result).allSatisfy(r -> assertThat(r.completeness()).isEqualTo(Completeness.COMPLETE));
        verify(mapper).readUsers(List.of(1L,2L));
        verify(mapper).children(List.of(1L,2L));
        verify(mapper).children(List.of(3L,4L));
        verifyNoMoreInteractions(mapper);
    }

    @Test void cycleTerminatesWithUniqueObservedDescendantsAndAnExplicitPartialReason() {
        graph(List.of(row(1,3L), row(2,1L), row(3,2L)));
        var result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.directCustomerIds()).containsExactly(2L);
        assertThat(result.descendantCustomerIds()).containsExactly(2L,3L);
        assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
        assertThat(result.reasons()).containsExactly(Reason.CYCLE);
        verify(mapper,times(3)).children(any());
    }

    @Test void repeatedEdgeIsNotDoubleCountedOrPresentedAsComplete() {
        graph(List.of(row(1,null), row(2,1L), row(2,1L), row(3,2L)));
        var result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.descendantCustomerIds()).containsExactly(2L,3L);
        assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
        assertThat(result.reasons()).contains(Reason.DUPLICATE_EDGE);
    }

    @Test void conflictingParentsCannotProduceACompleteGraph() {
        graph(List.of(row(1,null), row(2,1L), row(3,1L), row(4,2L), row(4,3L), row(5,4L)));
        var result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.descendantCustomerIds()).containsExactly(2L,3L,4L,5L);
        assertThat(result.reasons()).contains(Reason.CONFLICTING_EDGE);
        assertThat(result.reasons()).doesNotContain(Reason.CYCLE);
        assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
    }

    @Test void disabledAndSoftDeletedIntermediateNodesNeverPruneTheirDescendants() {
        graph(List.of(row(1,null), new Row(2L,1L,0,0,"DISABLED"), new Row(3L,2L,0,1,"ACTIVE"), row(4,3L)));
        var result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.descendantCustomerIds()).containsExactly(2L,4L);
        assertThat(result.reasons()).containsExactly(Reason.DELETED_NODE);
        assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
        verify(mapper).children(List.of(3L));
    }

    @Test void deletedDirectChildIsNotCountedButItsRetainedDescendantIsStillObserved() {
        graph(List.of(row(1,null),new Row(2L,1L,0,1,"DISABLED"),row(3,2L)));
        var result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.directCustomerIds()).isEmpty();
        assertThat(result.descendantCustomerIds()).containsExactly(3L);
        assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
        assertThat(result.reasons()).containsExactly(Reason.DELETED_NODE);
        verify(mapper).children(List.of(2L));
    }

    @Test void disabledRootAndNullOrZeroSponsorAreLegitimateRelationshipOrigins() {
        graph(List.of(new Row(1L,0L,0,0,"DISABLED"), row(2,1L), row(3,null)));
        var results = service.readInvitations(List.of(1L,3L));
        assertThat(results).allSatisfy(r -> {
            assertThat(r.completeness()).isEqualTo(Completeness.COMPLETE);
            assertThat(r.reasons()).isEmpty();
        });
        assertThat(results.get(0).descendantCustomerIds()).containsExactly(2L);
        assertThat(results.get(1).descendantCustomerIds()).isEmpty();
    }

    @Test void unknownRootAndActuallyMissingPositiveSponsorRemainDistinct() {
        graph(List.of(row(1,99L),row(2,1L),row(3,null)));
        var result = service.readInvitations(List.of(1L,3L,7L));
        assertThat(result.get(0).descendantCustomerIds()).containsExactly(2L);
        assertThat(result.get(0).reasons()).containsExactly(Reason.DANGLING_SPONSOR);
        assertThat(result.get(0).completeness()).isEqualTo(Completeness.PARTIAL);
        assertThat(result.get(1).completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(result.get(2).completeness()).isEqualTo(Completeness.UNKNOWN);
        assertThat(result.get(2).rootSandbox()).isNull();
        assertThat(result.get(2).reasons()).containsExactly(Reason.MISSING_ROOT);
        verify(mapper).readUsers(List.of(99L));
    }

    @Test void actualReferencedParentEnvironmentConflictsAndMalformedReadsCannotClaimAnOrphan() {
        graph(List.of(row(1,99L),new Row(99L,null,1,0,"ACTIVE"),row(2,1L)));
        var result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
        assertThat(result.reasons()).containsExactly(Reason.ENVIRONMENT_CONFLICT);
        assertThat(result.descendantCustomerIds()).containsExactly(2L);
        doReturn(null).when(mapper).readUsers(List.of(99L));
        result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
        assertThat(result.reasons()).containsExactly(Reason.INVALID_ROW);
        assertThat(result.reasons()).doesNotContain(Reason.DANGLING_SPONSOR);
    }

    @Test void environmentEvidenceIsRequiredAndPollutedPathsCannotReenterTheEligibleSet() {
        graph(List.of(row(1,null),new Row(2L,1L,1,0,"ACTIVE"),row(3,2L),new Row(4L,1L,null,0,"ACTIVE"),row(5,4L),row(6,1L)));
        var result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.directCustomerIds()).containsExactly(6L);
        assertThat(result.descendantCustomerIds()).containsExactly(6L);
        assertThat(result.reasons()).contains(Reason.ENVIRONMENT_CONFLICT,Reason.UNKNOWN_ENVIRONMENT);
        assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
        verify(mapper).children(List.of(2L,4L,6L));
        verify(mapper).children(List.of(3L,5L));
        graph(List.of(new Row(1L,null,null,0,"ACTIVE"),row(2,1L)));
        result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.rootSandbox()).isNull();
        assertThat(result.completeness()).isEqualTo(Completeness.UNKNOWN);
        assertThat(result.descendantCustomerIds()).isEmpty();
        assertThat(result.reasons()).containsExactly(Reason.UNKNOWN_ENVIRONMENT);
        graph(List.of(new Row(1L,null,1,0,"ACTIVE"),new Row(2L,1L,1,0,"ACTIVE")));
        result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.rootSandbox()).isEqualTo(1);
        assertThat(result.descendantCustomerIds()).containsExactly(2L);
        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
    }

    @Test void sourceFailureBeforeAnyEvidenceIsFailedAndAfterObservedEdgesIsPartial() {
        when(mapper.readUsers(any())).thenThrow(new DataAccessResourceFailureException("private source details"));
        var result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.completeness()).isEqualTo(Completeness.FAILED);
        assertThat(result.reasons()).containsExactly(Reason.SOURCE_READ_FAILED);
        assertThat(result.rootSandbox()).isNull();
        assertThat(result.toString()).doesNotContain("private source details");
        reset(mapper);
        graph(List.of(row(1,null),row(2,1L)));
        doThrow(new DataAccessResourceFailureException("private source details")).when(mapper).children(List.of(2L));
        result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.descendantCustomerIds()).containsExactly(2L);
        assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
        assertThat(result.reasons()).containsExactly(Reason.SOURCE_READ_FAILED);
    }

    @Test void foreignRootOrSponsorIsRejectedEvenWhenAnotherReturnedRowIsMalformed() {
        when(mapper.readUsers(any())).thenReturn(Arrays.asList(null,row(9,null)));
        assertThatThrownBy(() -> service.readInvitations(List.of(1L)))
            .hasMessage("INVALID_INVITATION_ROOT_SCOPE");
        reset(mapper);
        graph(List.of(row(1,null)));
        doReturn(Arrays.asList(null,row(2,9L))).when(mapper).children(any());
        assertThatThrownBy(() -> service.readInvitations(List.of(1L)))
            .hasMessage("INVALID_INVITATION_FRONTIER_SCOPE");
    }

    @Test void missingOrMalformedSourceRowsCannotBecomeACompleteZero() {
        graph(List.of(row(1,null)));
        doReturn(null).when(mapper).children(any());
        var result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
        assertThat(result.reasons()).containsExactly(Reason.INVALID_ROW);
        reset(mapper);
        graph(List.of(row(1,null)));
        doReturn(Arrays.asList(null,new Row(null,1L,0,0,"ACTIVE"))).when(mapper).children(any());
        result = service.readInvitations(List.of(1L)).get(0);
        assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
        assertThat(result.reasons()).containsExactly(Reason.INVALID_ROW);
    }

    private void graph(List<Row> rows) {
        doAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return rows.stream().filter(row -> ids.contains(row.customerId())).toList();
        }).when(mapper).readUsers(any());
        doAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return rows.stream().filter(row -> row.sponsorCustomerId()!=null && ids.contains(row.sponsorCustomerId())).toList();
        }).when(mapper).children(any());
    }

    private static Row row(long id,Long sponsor) { return new Row(id,sponsor,0,0,"ACTIVE"); }
}
