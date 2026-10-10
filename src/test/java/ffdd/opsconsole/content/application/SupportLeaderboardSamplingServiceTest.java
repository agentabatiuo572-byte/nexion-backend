package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper.*;
import ffdd.opsconsole.content.mapper.SupportLeaderboardSamplingMapper.*;
import ffdd.opsconsole.finance.facade.*;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ffdd.opsconsole.content.application.SupportLeaderboardTransactionTest.*;

class SupportLeaderboardSamplingServiceTest {
    static final LocalDateTime NOW=LocalDateTime.parse("2026-10-09T01:00:00.123456");
    static final Instant AT=NOW.toInstant(ZoneOffset.UTC);
    @AfterEach void clear(){SecurityContextHolder.clearContext();TransactionSynchronizationManager.clear();}

    static final class Fixture {
        final ResourceTransactions tx=new ResourceTransactions();
        final SupportLeaderboardSamplingMapper catalogue=mock(SupportLeaderboardSamplingMapper.class);
        final SupportLeaderboardMapper facts=mock(SupportLeaderboardMapper.class);
        final FinanceSupportPaymentFactsFacade finance=mock(FinanceSupportPaymentFactsFacade.class);
        final PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        final ProductionSupportPathGuard guard=mock(ProductionSupportPathGuard.class);
        final SupportLeaderboardPublicationMapper stored=mock(SupportLeaderboardPublicationMapper.class);
        final Probe publication=new Probe(stored);
        final SupportLeaderboardSourceService source;
        final SupportLeaderboardSamplingService sampling;
        Fixture(){
            when(guard.productionSupportAutomationAllowed()).thenReturn(true);
            when(config.activeValue(SupportLeaderboardService.REFRESH_KEY)).thenReturn(Optional.of("5"));
            when(facts.nowUtc()).thenReturn(NOW);
            when(catalogue.viewers(any())).thenReturn(List.of());when(catalogue.groups(any())).thenReturn(List.of());when(catalogue.members(any())).thenReturn(List.of());
            LocalDateTime start=NOW.minusMonths(1);
            when(facts.accounts()).thenReturn(List.of(new Account(7L,"公开客服",1,0,1L,1,0,1L,"SUPPORT",null,null,null,null,1)));
            when(facts.qualifications()).thenReturn(List.of(new QualificationInterval(1L,7L,"ENABLED",1L,start,null)));
            when(facts.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,1L,1L,start,null)));
            when(facts.groups()).thenReturn(List.of(new Group(1L,"当前组","ENABLED",70L,1L,start,start)));
            when(facts.currentBindings()).thenReturn(List.of(new Binding(1L,1L,7L,1L,start,null)));
            when(facts.productionCustomers()).thenReturn(List.of(1L));when(facts.attributions()).thenReturn(List.of());when(facts.attributionProofs()).thenReturn(List.of());
            when(finance.readHistory(List.of(1L))).thenAnswer(i->{assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                return new SupportPaymentFacts.Snapshot(List.of(),List.of(),List.of(),"Asia/Shanghai",AT,
                    List.of(new SupportPaymentFacts.FirstHistory(1,SupportPaymentFacts.Status.UNKNOWN,List.of("HISTORY_UNVERIFIED"))));});
            source=proxy(new SupportLeaderboardSourceService(facts,finance),tx);
            sampling=new SupportLeaderboardSamplingService(catalogue,facts,source,proxy(publication,tx),stored,config,guard,tx);
        }
        void ownGroup(){when(catalogue.viewers(any())).thenReturn(List.of(new Viewer(7,false,true,false)));
            when(catalogue.groups(any())).thenReturn(List.of(new SamplingGroup(1,70)));when(catalogue.members(any())).thenReturn(List.of(new SamplingMember(7,1)));}
    }

    /** A response/publisher fixture behind real Spring propagation, not JDBC persistence. */
    static class Probe extends SupportLeaderboardPublicationService {
        final List<Snapshot> committed=new ArrayList<>();final Set<String> late=new HashSet<>(),corruptLatest=new HashSet<>(),corruptBaseline=new HashSet<>();
        boolean fresh;
        int cachedAgeSeconds=400;
        List<Publication> baseline=List.of();
        Probe(SupportLeaderboardPublicationMapper mapper){super(mapper);}
        @Override @Transactional(propagation=Propagation.MANDATORY,readOnly=true,isolation=Isolation.REPEATABLE_READ)
        public Publication latest(Context context,String expected){
            if(corruptLatest.contains(streamKey(context)))return super.latest(context,expected);
            Context old=new Context(context.board(),context.rankMonth(),context.referenceMonth(),context.currency(),context.scope(),context.approvedGroupIds(),context.definitionVersion(),AT.minusSeconds(fresh?30:cachedAgeSeconds));
            return new Publication(99,old.evaluatedAt().plusSeconds(1),SupportLeaderboardPublicationServiceTest.snapshot(old,Coverage.COMPLETE));
        }
        @Override @Transactional(propagation=Propagation.MANDATORY,readOnly=true,isolation=Isolation.REPEATABLE_READ)
        public List<Publication> previousBusinessDay(Context context){assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            if(corruptBaseline.contains(streamKey(context)))throw new BizException(503,"SUPPORT_LEADERBOARD_PUBLICATION_UNAVAILABLE");return baseline;}
        @Override @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
        public Publication publish(Snapshot snapshot){assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertNull(SecurityContextHolder.getContext().getAuthentication());if(late.contains(streamKey(snapshot.context())))throw new BizException(409,"SUPPORT_LEADERBOARD_LATE_EVALUATION");
            committed.add(snapshot);return new Publication(committed.size(),AT.plusSeconds(1),snapshot);}
    }

    @Test void noRequestsSampleEightRealShapedStreamsWithOneCompleteFinancialRead(){Fixture f=new Fixture();
        var result=f.sampling.sample();assertEquals(8,result.committed());assertEquals(8,result.due());assertEquals(0,result.failed());
        assertEquals(Set.of("USDT","NEX"),f.publication.committed.stream().map(s->s.context().currency()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(4,f.publication.committed.stream().map(s->s.context().board()).distinct().count());
        for(Snapshot s:f.publication.committed){assertEquals(AT,s.context().evaluatedAt());assertNull(s.rows().get(0).amount().value());
            assertEquals(Coverage.UNKNOWN,s.rows().get(0).amount().coverage());assertEquals(MovementKind.UNAVAILABLE,s.rows().get(0).movement().kind());
            assertEquals(s.context().board()==Board.customers?State.COMPLETE:State.PROVISIONAL,s.state());}
        verify(f.finance,times(1)).readHistory(List.of(1L));verify(f.facts,times(1)).accounts();verify(f.facts,times(1)).productionCustomers();
        assertTrue(f.tx.begunReadOnly.contains(false));assertNull(f.tx.current.get());assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void manySlicesReuseOneBatchAndCurrentGroupRecomputesWholeRank(){Fixture f=new Fixture();f.ownGroup();
        var result=f.sampling.sample();assertEquals(16,result.committed());verify(f.finance,times(1)).readHistory(any());
        assertTrue(f.publication.committed.stream().filter(s->s.context().scope()==Scope.ownGroup).allMatch(s->s.rows().size()==1 && s.context().approvedGroupIds().equals(Set.of(1L))));}
    @Test void onlyGivenCompleteYesterdayPublicationCanProduceMovementAndNoBaselineIsFabricated(){Fixture f=new Fixture();
        Context current=SupportLeaderboardSamplingService.catalogue(AT,List.of(),List.of(),List.of()).values().stream()
            .filter(c->c.board()==Board.customers).findFirst().orElseThrow();
        Context yesterday=new Context(current.board(),null,current.referenceMonth(),current.currency(),current.scope(),current.approvedGroupIds(),current.definitionVersion(),AT.minusSeconds(86_400));
        Snapshot old=SupportLeaderboardPublicationServiceTest.snapshot(yesterday,Coverage.COMPLETE);
        f.publication.baseline=List.of(new Publication(40,AT.minusSeconds(86_399),old));assertEquals(8,f.sampling.sample().committed());
        for(Snapshot s:f.publication.committed){Movement movement=s.rows().get(0).movement();
            if(s.context().board()==Board.customers){assertEquals(MovementKind.SAME,movement.kind());assertEquals(old.viewVersion(),movement.baselineVersion());assertEquals(yesterday.evaluatedAt(),movement.baselineAt());}
            else assertEquals(MovementKind.UNAVAILABLE,movement.kind());}
        Fixture noYesterday=new Fixture();noYesterday.publication.baseline=List.of(new Publication(41,AT,old));noYesterday.sampling.sample();
        assertTrue(noYesterday.publication.committed.stream().allMatch(s->s.rows().get(0).movement().kind()==MovementKind.UNAVAILABLE));
    }
    @Test void noDueOrBlockedProfileNeverReadsFullFacts(){Fixture f=new Fixture();f.publication.fresh=true;
        when(f.stored.latest(anyString())).thenReturn(mock(SupportLeaderboardPublicationMapper.Stored.class));
        assertEquals("IDLE",f.sampling.sample().status());verifyNoInteractions(f.finance);verify(f.facts,never()).accounts();
        Fixture blocked=new Fixture();when(blocked.guard.productionSupportAutomationAllowed()).thenReturn(false);
        assertEquals("PROFILE_BLOCKED",blocked.sampling.sample().status());verifyNoInteractions(blocked.catalogue,blocked.finance,blocked.config,blocked.facts);}
    @Test void freshLegacyDefinitionIsDueAndSamplingDoesNotIdleOrFail() throws Exception {
        Fixture f=new Fixture();legacyStored(f);
        var result=f.sampling.sample();assertEquals("SAMPLED",result.status());assertEquals(8,result.due());assertEquals(8,result.committed());assertEquals(0,result.failed());
        assertTrue(f.publication.committed.stream().allMatch(s->s.context().definitionVersion().equals("support-leaderboard-v2-deposit-no-refund")));
        verify(f.finance,times(1)).readHistory(any());
    }
    @Test void legacyDefinitionSourceFailureCannotPublishOldMoney() throws Exception {
        Fixture f=new Fixture();legacyStored(f);when(f.facts.accounts()).thenThrow(new DataAccessResourceFailureException("private"));
        var result=f.sampling.sample();assertEquals("SOURCE_FAILED",result.status());assertEquals(8,result.due());assertEquals(0,result.committed());assertEquals(8,result.failed());
        assertTrue(f.publication.committed.isEmpty());
    }
    private static void legacyStored(Fixture f) throws Exception {
        for(var context:SupportLeaderboardSamplingService.catalogue(AT,List.of(),List.of(),List.of()).values()) {
            String key=SupportLeaderboardPublicationService.streamKey(context);f.publication.corruptLatest.add(key);
            Context old=new Context(context.board(),context.rankMonth(),context.referenceMonth(),context.currency(),context.scope(),context.approvedGroupIds(),"support-leaderboard-v1",AT.minusSeconds(30));
            var snapshot=SupportLeaderboardPublicationServiceTest.snapshot(old,Coverage.COMPLETE);
            when(f.stored.latest(key)).thenReturn(SupportLeaderboardPublicationServiceTest.stored(99,snapshot,AT.minusSeconds(29)));
        }
    }
    @Test void disabledMissingAndInvalidConfigDoNotFallBackButBoundsDriveDue(){for(Optional<String> value:List.of(Optional.<String>empty(),Optional.of("0"),Optional.of("61"),Optional.of("bad"))){
        Fixture f=new Fixture();when(f.config.activeValue(anyString())).thenReturn(value);assertEquals("PLAN_FAILED",f.sampling.sample().status());verifyNoInteractions(f.finance,f.catalogue);}
        for(String value:List.of("1","60")){Fixture f=new Fixture();f.publication.fresh=true;when(f.config.activeValue(anyString())).thenReturn(Optional.of(value));
            when(f.stored.latest(anyString())).thenReturn(mock(SupportLeaderboardPublicationMapper.Stored.class));assertEquals(0,f.sampling.sample().due());}
    }
    @Test void sourceFailureRollsBackOnlyItsBatchAndNextPollStillWorks(){Fixture f=new Fixture();
        when(f.facts.accounts()).thenThrow(new DataAccessResourceFailureException("private-order-secret")).thenReturn(List.of(new Account(7L,"公开客服",1,0,1L,1,0,1L,"SUPPORT",null,null,null,null,1)));
        assertEquals("SOURCE_FAILED",f.sampling.sample().status());assertTrue(f.publication.committed.isEmpty());assertEquals(1,f.tx.rollbacks);
        assertNull(f.tx.current.get());assertEquals(8,f.sampling.sample().committed());verify(f.finance,times(1)).readHistory(any());}
    @Test void corruptLatestIsDecodedNotTreatedAsMissingAndOneBadBaselineDoesNotKillOtherStreams(){Fixture f=new Fixture();
        String key=SupportLeaderboardSamplingService.catalogue(AT,List.of(),List.of(),List.of()).firstKey();
        f.publication.corruptLatest.add(key);when(f.stored.latest(eq(key))).thenReturn(mock(SupportLeaderboardPublicationMapper.Stored.class));
        var result=f.sampling.sample();assertEquals(1,result.failed());assertEquals(7,result.committed());assertTrue(f.publication.committed.stream().noneMatch(s->SupportLeaderboardPublicationService.streamKey(s.context()).equals(key)));
        Fixture baseline=new Fixture();baseline.publication.corruptBaseline.add(key);var other=baseline.sampling.sample();assertEquals(1,other.failed());assertEquals(7,other.committed());}
    @Test void lateWriterDoesNotRereadOrRetryOverwriteWinner(){Fixture f=new Fixture();String key=SupportLeaderboardSamplingService.catalogue(AT,List.of(),List.of(),List.of()).firstKey();f.publication.late.add(key);
        var result=f.sampling.sample();assertEquals(1,result.superseded());assertEquals(7,result.committed());assertEquals(0,result.failed());
        verify(f.stored,times(8)).latest(anyString());verify(f.finance,times(1)).readHistory(any());assertEquals(1,f.tx.rollbacks);}
    @Test void oldGroupSetRetiresWhenFreshSourceBatchUsesNewCurrentCatalogue(){Fixture f=new Fixture();f.ownGroup();
        when(f.catalogue.members(any())).thenReturn(List.of(new SamplingMember(7,1)),List.of(new SamplingMember(7,2)));
        var result=f.sampling.sample();assertEquals(8,result.retired());assertEquals(8,result.committed());
        assertTrue(f.publication.committed.stream().allMatch(s->s.context().scope()==Scope.all));}
    @Test void catalogueIsFiniteDeduplicatedAndNoArbitraryGroupSubset(){var contexts=SupportLeaderboardSamplingService.catalogue(AT,
        List.of(new Viewer(7,false,true,false),new Viewer(8,false,true,false),new Viewer(70,false,false,true),new Viewer(80,false,false,true),new Viewer(90,true,false,false)),
        List.of(new SamplingGroup(1,70),new SamplingGroup(2,70),new SamplingGroup(3,80)),List.of(new SamplingMember(7,1),new SamplingMember(8,1)));
        assertEquals(56,contexts.size());assertTrue(contexts.values().stream().noneMatch(c->c.approvedGroupIds().equals(Set.of(1L,3L))));
        assertEquals(8,contexts.values().stream().filter(c->c.scope()==Scope.ownGroup).count());}
    @Test void boundedRoundRobinDoesNotStarveStreamsBeyondFirstBatch(){List<Viewer> viewers=new ArrayList<>();List<SamplingMember> members=new ArrayList<>();
        for(int i=1;i<=20;i++){viewers.add(new Viewer(i,false,true,false));members.add(new SamplingMember(i,i));}
        var contexts=SupportLeaderboardSamplingService.catalogue(AT,viewers,List.of(),members);Set<String> visited=new HashSet<>();String cursor="";
        for(int poll=0;poll<4;poll++){var window=SupportLeaderboardSamplingService.window(contexts,cursor,64);assertEquals(64,window.size());visited.addAll(window);cursor=window.get(window.size()-1);}
        assertEquals(contexts.keySet(),visited);assertEquals(168,contexts.size());}
    @Test void samplerReportsDeferredAndRotatesActualLookupsWithoutReadingFinanceForFreshStreams(){Fixture f=new Fixture();f.publication.fresh=true;
        List<Viewer> viewers=new ArrayList<>();List<SamplingMember> members=new ArrayList<>();for(int i=1;i<=20;i++){viewers.add(new Viewer(i,false,true,false));members.add(new SamplingMember(i,i));}
        when(f.catalogue.viewers(any())).thenReturn(viewers);when(f.catalogue.members(any())).thenReturn(members);
        when(f.stored.latest(anyString())).thenReturn(mock(SupportLeaderboardPublicationMapper.Stored.class));
        for(int poll=0;poll<3;poll++){var result=f.sampling.sample();assertEquals(168,result.planned());assertEquals(64,result.scanned());assertEquals(104,result.deferred());assertEquals(0,result.due());}
        var keys=org.mockito.ArgumentCaptor.forClass(String.class);verify(f.stored,times(192)).latest(keys.capture());assertEquals(168,new HashSet<>(keys.getAllValues()).size());verifyNoInteractions(f.finance);}
    @Test void changingExistingRefreshParamTakesEffectNextPoll(){Fixture f=new Fixture();f.publication.cachedAgeSeconds=120;
        when(f.stored.latest(anyString())).thenReturn(mock(SupportLeaderboardPublicationMapper.Stored.class));assertEquals(0,f.sampling.sample().due());
        when(f.config.activeValue(SupportLeaderboardService.REFRESH_KEY)).thenReturn(Optional.of("1"));assertEquals(8,f.sampling.sample().committed());verify(f.finance,times(1)).readHistory(any());}
    @Test void batchClockCrossingMonthCannotPublishHintFromOldMonth(){Fixture f=new Fixture();
        when(f.facts.nowUtc()).thenReturn(LocalDateTime.parse("2026-10-31T15:59:59"),LocalDateTime.parse("2026-10-31T16:00:00"));
        var result=f.sampling.sample();assertEquals(8,result.retired());assertEquals(0,result.committed());}
}
