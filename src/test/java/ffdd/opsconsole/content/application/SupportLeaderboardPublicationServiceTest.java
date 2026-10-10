package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.content.mapper.SupportLeaderboardPublicationMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardPublicationMapper.Stored;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupportLeaderboardPublicationServiceTest {
    static final Instant NOW = Instant.parse("2026-10-09T01:00:00.123456Z");
    static Context context(Board board, String currency, String definition, Instant at) {
        return new Context(board,board == Board.customers ? null : YearMonth.of(2026,10),YearMonth.of(2026,10),
            currency,Scope.all,Set.of(),definition,at);
    }
    static Snapshot snapshot(Context c, Coverage coverage) {
        Count count = new Count(0L,Coverage.COMPLETE,Reason.NONE);
        return SupportLeaderboard.calculate(c,"real-source-tuple",coverage,List.of(new Candidate(7,"公开客服",null,"组一",
            Qualification.ACTIVE,count,count,new Amount(BigDecimal.ZERO,c.currency(),c.referenceMonth(),
            c.board() == Board.purchase ? AmountKind.PURCHASE : AmountKind.DEPOSIT,Coverage.COMPLETE,Reason.NONE,"money-source"),null)));
    }
    static Stored stored(long id, Snapshot s, Instant published) throws Exception {
        String payload = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(s);
        return stored(id,s,published,payload);
    }
    static Stored stored(long id, Snapshot s, Instant published, String payload) throws Exception {
        Context c=s.context(); String groups=String.join(",",c.approvedGroupIds().stream().sorted().map(Object::toString).toList());
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        return new Stored(id,SupportLeaderboardPublicationService.streamKey(c),c.board().name(),
            c.rankMonth()==null?null:c.rankMonth().toString(),c.referenceMonth().toString(),c.currency(),c.rankCurrency(),
            c.scope().name(),groups,c.definitionVersion(),s.sourceVersion(),s.viewVersion(),s.comparisonKey(),
            LocalDateTime.ofInstant(c.evaluatedAt(),ZoneOffset.UTC),LocalDateTime.ofInstant(published,ZoneOffset.UTC),s.state().name(),payload,sha);
    }
    static void beginRead() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
    }
    static void endRead() { TransactionSynchronizationManager.clear(); }
    static void assert503(Runnable action) { assertEquals(503,assertThrows(BizException.class,action::run).getCode()); }

    @Test void publishesInsertThenCasAndReadsDatabaseTime() throws Exception {
        var mapper=mock(SupportLeaderboardPublicationMapper.class); var service=new SupportLeaderboardPublicationService(mapper);
        Snapshot s=snapshot(context(Board.firstPayment,"USDT","defA",NOW),Coverage.COMPLETE);
        Stored row=stored(32,s,NOW.plusSeconds(4));
        when(mapper.lockPointer(anyString())).thenReturn(null);
        when(mapper.byVersionForUpdate(anyString(),anyString())).thenReturn(null,row);
        when(mapper.insert(anyMap())).thenReturn(1); when(mapper.advance(anyString(),isNull(),eq(32L))).thenReturn(1);
        Publication publication=service.publish(s);
        assertEquals(32,publication.publicationId()); assertEquals(NOW.plusSeconds(4),publication.publishedAt());
        var order=inOrder(mapper); order.verify(mapper).ensurePointer(anyString()); order.verify(mapper).lockPointer(anyString());
        order.verify(mapper).byVersionForUpdate(anyString(),eq(s.viewVersion())); order.verify(mapper).insert(anyMap());
        order.verify(mapper).byVersionForUpdate(anyString(),eq(s.viewVersion())); order.verify(mapper).advance(anyString(),isNull(),eq(32L));
    }
    @Test void replayReturnsExistingImmutableVersionWithoutAdvancingCurrent() throws Exception {
        var mapper=mock(SupportLeaderboardPublicationMapper.class); var service=new SupportLeaderboardPublicationService(mapper);
        Snapshot s=snapshot(context(Board.deposit,"USDT","defA",NOW),Coverage.COMPLETE);
        when(mapper.lockPointer(anyString())).thenReturn(90L);
        when(mapper.byVersionForUpdate(anyString(),eq(s.viewVersion()))).thenReturn(stored(33,s,NOW.plusSeconds(1)));
        assertEquals(33,service.publish(s).publicationId()); verify(mapper,never()).insert(anyMap());
        verify(mapper,never()).advance(anyString(),any(),anyLong()); verify(mapper,never()).byId(anyLong());
    }
    @Test void lateAndEqualDifferentVersionsCannotMoveLatestBackward() throws Exception {
        for (Instant at:List.of(NOW.minusSeconds(1),NOW)) {
            var mapper=mock(SupportLeaderboardPublicationMapper.class); var service=new SupportLeaderboardPublicationService(mapper);
            Snapshot previous=snapshot(context(Board.deposit,"USDT","defA",NOW),Coverage.COMPLETE);
            Snapshot incoming=snapshot(context(Board.deposit,"USDT","defB",at),Coverage.COMPLETE);
            when(mapper.lockPointer(anyString())).thenReturn(2L); when(mapper.byId(2)).thenReturn(stored(2,previous,NOW.plusSeconds(2)));
            assertEquals(409,assertThrows(BizException.class,()->service.publish(incoming)).getCode());
            verify(mapper,never()).insert(anyMap()); verify(mapper,never()).advance(anyString(),any(),anyLong());
        }
    }
    @Test void provisionalIsPersistableButEmptyUnprovedOrFailedCandidatesCannotWrite() throws Exception {
        var mapper=mock(SupportLeaderboardPublicationMapper.class); var service=new SupportLeaderboardPublicationService(mapper);
        Context c=context(Board.customers,"VND","defA",NOW); Snapshot partial=snapshot(c,Coverage.PARTIAL);
        when(mapper.lockPointer(anyString())).thenReturn(null);
        assertEquals(State.PROVISIONAL,partial.state());
        when(mapper.byVersionForUpdate(anyString(),anyString())).thenReturn(null,stored(10,partial,NOW.plusSeconds(2)));
        when(mapper.insert(anyMap())).thenReturn(1); when(mapper.advance(anyString(),isNull(),eq(10L))).thenReturn(1);
        assertEquals(State.PROVISIONAL,service.publish(partial).snapshot().state());
        var blocked=mock(SupportLeaderboardPublicationMapper.class); var safe=new SupportLeaderboardPublicationService(blocked);
        Snapshot empty=SupportLeaderboard.calculate(c,"tuple",Coverage.UNKNOWN,List.of());
        Snapshot fakeComplete=new Snapshot(c,empty.sourceVersion(),empty.candidateCoverage(),State.COMPLETE,
            empty.comparisonKey(),empty.viewVersion(),empty.rows(),empty.qualificationBirths());
        assert503(()->safe.publish(fakeComplete));
        Snapshot fakeFailed=new Snapshot(c,"tuple",Coverage.FAILED,State.PROVISIONAL,"a".repeat(64),"slb-v1:"+"b".repeat(64),List.of(),Map.of());
        assert503(()->safe.publish(fakeFailed)); verifyNoInteractions(blocked);
    }
    @Test void emptyUncertifiedMonthlySourcePersistsAsProvisionalWithoutFabricatingCompleteZero() throws Exception {
        for (Board board:List.of(Board.firstPayment,Board.deposit,Board.purchase)) {
            for (Coverage coverage:List.of(Coverage.PARTIAL,Coverage.UNKNOWN)) {
                var mapper=mock(SupportLeaderboardPublicationMapper.class);
                var service=new SupportLeaderboardPublicationService(mapper);
                Snapshot empty=SupportLeaderboard.withMovement(SupportLeaderboard.calculate(
                    context(board,"USDT","defA",NOW),"uncertified-real-source",coverage,List.of()),List.of());
                when(mapper.lockPointer(anyString())).thenReturn(null);
                when(mapper.byVersionForUpdate(anyString(),anyString())).thenReturn(null,stored(10,empty,NOW.plusSeconds(2)));
                when(mapper.insert(anyMap())).thenReturn(1);
                when(mapper.advance(anyString(),isNull(),eq(10L))).thenReturn(1);
                Publication published=service.publish(empty);
                assertEquals(empty,published.snapshot());
                assertEquals(State.PROVISIONAL,published.snapshot().state());
                assertEquals(coverage,published.snapshot().candidateCoverage());
                assertTrue(published.snapshot().rows().isEmpty());
                var page=SupportLeaderboard.page(published.snapshot(),null,1,20,null,7);
                assertEquals(0,page.ranked());assertNull(page.self().gap());
                verify(mapper).advance(anyString(),isNull(),eq(10L));
            }
        }
    }
    @Test void insertedReadbackOrPointerFailureThrowsRatherThanReturnPartialSuccess() throws Exception {
        for (boolean corrupt:List.of(false,true)) {
            var mapper=mock(SupportLeaderboardPublicationMapper.class); var service=new SupportLeaderboardPublicationService(mapper);
            Snapshot s=snapshot(context(Board.deposit,"USDT","defA",NOW),Coverage.COMPLETE);
            when(mapper.lockPointer(anyString())).thenReturn(null);
            when(mapper.byVersionForUpdate(anyString(),anyString())).thenReturn(null,corrupt?null:stored(5,s,NOW.plusSeconds(1)));
            when(mapper.insert(anyMap())).thenReturn(1); assert503(()->service.publish(s));
            verify(mapper).insert(anyMap());
            if (corrupt) verify(mapper,never()).advance(anyString(),any(),anyLong());
            // Mockito only proves propagation/order; a real MySQL transaction must prove rollback.
        }
    }
    @Test void lockAndInsertExceptionsPropagateWithoutAdvancingPointer() {
        for (boolean lockFailure:List.of(false,true)) {
            var mapper=mock(SupportLeaderboardPublicationMapper.class); var service=new SupportLeaderboardPublicationService(mapper);
            Snapshot s=snapshot(context(Board.purchase,"USDT","defA",NOW),Coverage.COMPLETE);
            if (lockFailure) when(mapper.lockPointer(anyString())).thenThrow(new IllegalStateException("lock failure"));
            else { when(mapper.lockPointer(anyString())).thenReturn(null);
                when(mapper.insert(anyMap())).thenThrow(new IllegalStateException("insert failure")); }
            assertThrows(IllegalStateException.class,()->service.publish(s)); verify(mapper,never()).advance(anyString(),any(),anyLong());
        }
    }
    @Test void latestChecksExpectedVersionDefinitionAndCorruptUnknownTypedPayload() throws Exception {
        var mapper=mock(SupportLeaderboardPublicationMapper.class); var service=new SupportLeaderboardPublicationService(mapper);
        Context c=context(Board.firstPayment,"USDT","defA",NOW); Snapshot s=snapshot(c,Coverage.COMPLETE);
        beginRead(); try {
            when(mapper.latest(anyString())).thenReturn(stored(2,s,NOW.plusSeconds(1)));
            assertEquals(s,service.latest(c,s.viewVersion()).snapshot());
            assertEquals(409,assertThrows(BizException.class,()->service.latest(c,"slb-v1:"+"a".repeat(64))).getCode());
            assertEquals(409,assertThrows(BizException.class,()->service.latest(context(Board.firstPayment,"USDT","defB",NOW),null)).getCode());
            assertEquals(422,assertThrows(BizException.class,()->service.latest(c,"bad")).getCode());
            when(mapper.latest(anyString())).thenReturn(stored(2,s,NOW.plusSeconds(1),"{\"unknown\":true}"));
            assert503(()->service.latest(c,null));
            when(mapper.latest(anyString())).thenReturn(stored(2,s,NOW.plusSeconds(1),"null")); assert503(()->service.latest(c,null));
            Snapshot other=snapshot(context(Board.firstPayment,"VND","defA",NOW),Coverage.COMPLETE);
            when(mapper.latest(anyString())).thenReturn(stored(3,other,NOW.plusSeconds(1))); assert503(()->service.latest(c,null));
            when(mapper.latest(anyString())).thenReturn(null); assert503(()->service.latest(c,null));
        } finally { endRead(); }
    }
    @Test void yesterdayCountReadsAllCurrenciesAndLastDifferentDefinitionRemainsVisible() throws Exception {
        var mapper=mock(SupportLeaderboardPublicationMapper.class); var service=new SupportLeaderboardPublicationService(mapper);
        Context c=context(Board.firstPayment,"USDT","defA",NOW);
        Snapshot earlier=snapshot(context(Board.firstPayment,"USDT","defA",NOW.minusSeconds(86400)),Coverage.COMPLETE);
        Snapshot later=snapshot(context(Board.firstPayment,"VND","defB",NOW.minusSeconds(86000)),Coverage.COMPLETE);
        when(mapper.previousDay(anyMap())).thenReturn(List.of(stored(20,later,NOW.minusSeconds(85000)),stored(19,earlier,NOW.minusSeconds(85500))));
        beginRead(); try {
            List<Publication> result=service.previousBusinessDay(c); assertEquals(List.of(19L,20L),result.stream().map(Publication::publicationId).toList());
            assertEquals(Reason.DEFINITION_CHANGED,SupportLeaderboard.withMovement(snapshot(c,Coverage.COMPLETE),result).rows().get(0).movement().reason());
            var capture=org.mockito.ArgumentCaptor.forClass(Map.class); verify(mapper).previousDay(capture.capture());
            assertNull(capture.getValue().get("rankCurrency"));
            assertEquals(LocalDateTime.parse("2026-10-07T16:00:00"),capture.getValue().get("from"));
            assertEquals(LocalDateTime.parse("2026-10-08T16:00:00"),capture.getValue().get("to"));
        } finally { endRead(); }
    }
    @Test void yesterdayRejectsProvisionalOutOfDayAndWrongScopeRatherThanSkippingCorruption() throws Exception {
        var mapper=mock(SupportLeaderboardPublicationMapper.class); var service=new SupportLeaderboardPublicationService(mapper);
        Context c=context(Board.deposit,"USDT","defA",NOW); Context yesterday=context(Board.deposit,"USDT","defA",NOW.minusSeconds(86400));
        Context otherScope=new Context(yesterday.board(),yesterday.rankMonth(),yesterday.referenceMonth(),yesterday.currency(),
            Scope.ownGroup,Set.of(5L),yesterday.definitionVersion(),yesterday.evaluatedAt());
        beginRead(); try {
            for (Stored bad:List.of(stored(1,snapshot(yesterday,Coverage.PARTIAL),NOW.minusSeconds(86300)),
                    stored(2,snapshot(yesterday,Coverage.COMPLETE),NOW),stored(3,snapshot(otherScope,Coverage.COMPLETE),NOW.minusSeconds(86300)))) {
                when(mapper.previousDay(anyMap())).thenReturn(List.of(bad)); assert503(()->service.previousBusinessDay(c));
            }
        } finally { endRead(); }
    }
    @Test void streamIgnoresDefinitionButKeepsReferenceCurrencyAndApprovedGroupSet() {
        Context a=context(Board.firstPayment,"USDT","defA",NOW); Context b=context(Board.firstPayment,"USDT","defB",NOW.plusSeconds(1));
        assertEquals(SupportLeaderboardPublicationService.streamKey(a),SupportLeaderboardPublicationService.streamKey(b));
        assertNotEquals(SupportLeaderboardPublicationService.streamKey(a),SupportLeaderboardPublicationService.streamKey(context(Board.firstPayment,"VND","defA",NOW)));
        Context x=new Context(a.board(),a.rankMonth(),a.referenceMonth(),a.currency(),Scope.managedGroups,Set.of(9L,3L),a.definitionVersion(),NOW);
        Context y=new Context(a.board(),a.rankMonth(),a.referenceMonth(),a.currency(),Scope.managedGroups,new LinkedHashSet<>(List.of(3L,9L)),a.definitionVersion(),NOW);
        assertEquals(SupportLeaderboardPublicationService.streamKey(x),SupportLeaderboardPublicationService.streamKey(y));
    }
    @Test void verifiedLegacyDefinitionRequiresRefreshButVersionBoundReaderStillConflicts() throws Exception {
        var mapper=mock(SupportLeaderboardPublicationMapper.class);var service=new SupportLeaderboardPublicationService(mapper);
        Context old=context(Board.deposit,"USDT","support-leaderboard-v1",NOW.minusSeconds(30));
        Snapshot snapshot=snapshot(old,Coverage.COMPLETE);
        Context current=context(Board.deposit,"USDT",SupportLeaderboardPolicy.DEFINITION,NOW);
        when(mapper.latest(anyString())).thenReturn(stored(2,snapshot,NOW.minusSeconds(29)));
        beginRead();try {
            assertEquals("support-leaderboard-v2-deposit-no-refund",current.definitionVersion());
            assertNull(service.latest(current,null));
            assertEquals(409,assertThrows(BizException.class,()->service.latest(current,snapshot.viewVersion())).getCode());
            assertEquals(snapshot,service.latest(old,snapshot.viewVersion()).snapshot());
            verify(mapper,never()).insert(anyMap());verify(mapper,never()).advance(anyString(),any(),anyLong());
        }finally {endRead();}
    }
    @Test void invalidOrUnrecognizedLegacyPublicationCannotMasqueradeAsDefinitionRefresh() throws Exception {
        var mapper=mock(SupportLeaderboardPublicationMapper.class);var service=new SupportLeaderboardPublicationService(mapper);
        Context current=context(Board.deposit,"USDT",SupportLeaderboardPolicy.DEFINITION,NOW);
        Snapshot old=snapshot(context(Board.deposit,"USDT","support-leaderboard-v1",NOW.minusSeconds(30)),Coverage.COMPLETE);
        beginRead();try {
            for(String payload:List.of("null","{\"unknown\":true}")) {
                when(mapper.latest(anyString())).thenReturn(stored(2,old,NOW.minusSeconds(29),payload));assert503(()->service.latest(current,null));
            }
            when(mapper.latest(anyString())).thenReturn(null);assert503(()->service.latest(current,null));
            Snapshot future=snapshot(context(Board.deposit,"USDT","support-leaderboard-future",NOW.minusSeconds(30)),Coverage.COMPLETE);
            when(mapper.latest(anyString())).thenReturn(stored(2,future,NOW.minusSeconds(29)));
            assertEquals(409,assertThrows(BizException.class,()->service.latest(current,null)).getCode());
        }finally {endRead();}
    }
    @Test void noRefundDefinitionCannotCompareAgainstYesterdayNetDefinition() throws Exception {
        var mapper=mock(SupportLeaderboardPublicationMapper.class);var service=new SupportLeaderboardPublicationService(mapper);
        Context current=context(Board.deposit,"USDT",SupportLeaderboardPolicy.DEFINITION,NOW);
        Snapshot yesterday=snapshot(context(Board.deposit,"USDT","support-leaderboard-v1",NOW.minusSeconds(86400)),Coverage.COMPLETE);
        when(mapper.previousDay(anyMap())).thenReturn(List.of(stored(2,yesterday,NOW.minusSeconds(86399))));
        beginRead();try {
            var comparison=SupportLeaderboard.withMovement(snapshot(current,Coverage.COMPLETE),service.previousBusinessDay(current));
            assertEquals(MovementKind.UNAVAILABLE,comparison.rows().get(0).movement().kind());
            assertEquals(Reason.DEFINITION_CHANGED,comparison.rows().get(0).movement().reason());
        }finally {endRead();}
    }
    @Test void exactMicrosecondsAndRealReadBoundaryAreRequired() {
        var mapper=mock(SupportLeaderboardPublicationMapper.class); var service=new SupportLeaderboardPublicationService(mapper);
        Context c=context(Board.deposit,"USDT","defA",NOW);
        assertThrows(IllegalStateException.class,()->service.latest(c,null));
        assertThrows(IllegalStateException.class,()->service.previousBusinessDay(c));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        try { assertThrows(IllegalStateException.class,()->service.latest(c,null)); } finally { endRead(); }
        assert503(()->service.publish(snapshot(context(Board.deposit,"USDT","defA",NOW.plusNanos(1)),Coverage.COMPLETE)));
        verifyNoInteractions(mapper);
    }
    @Test void transactionAnnotationsRequireIndependentRollbackWriterAndMandatoryRrReaders() throws Exception {
        Transactional write=SupportLeaderboardPublicationService.class.getMethod("publish",Snapshot.class).getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRES_NEW,write.propagation()); assertEquals(Isolation.REPEATABLE_READ,write.isolation());
        assertArrayEquals(new Class[]{Exception.class},write.rollbackFor());
        for (String method:List.of("latest","previousBusinessDay")) {
            var reflected=method.equals("latest")?SupportLeaderboardPublicationService.class.getMethod(method,Context.class,String.class)
                :SupportLeaderboardPublicationService.class.getMethod(method,Context.class);
            Transactional read=reflected.getAnnotation(Transactional.class); assertEquals(Propagation.MANDATORY,read.propagation());
            assertEquals(Isolation.REPEATABLE_READ,read.isolation()); assertTrue(read.readOnly());
        }
    }

    static Row withMovement(Row r, Movement movement) {
        return new Row(r.agentId(),r.name(),r.avatarUrl(),r.groupName(),r.qualification(),r.rank(),r.isTied(),
            r.firstPayment(),r.customers(),r.amount(),r.rankMetricCoverage(),movement);
    }
    static Snapshot replaceFirst(Snapshot s, Row row, boolean reseal) throws Exception {
        List<Row> rows=new ArrayList<>(s.rows()); rows.set(0,row);
        Snapshot changed=new Snapshot(s.context(),s.sourceVersion(),s.candidateCoverage(),s.state(),s.comparisonKey(),s.viewVersion(),rows,s.qualificationBirths());
        if (!reseal) return changed;
        // Reuse the core digest in this test-only corruption seam; never duplicate its algorithm.
        var digest=SupportLeaderboard.class.getDeclaredMethod("viewVersion",Context.class,String.class,Coverage.class,List.class,Map.class);
        digest.setAccessible(true);
        String version=(String)digest.invoke(null,changed.context(),changed.sourceVersion(),changed.candidateCoverage(),changed.rows(),changed.qualificationBirths());
        Snapshot sealed=new Snapshot(changed.context(),changed.sourceVersion(),changed.candidateCoverage(),changed.state(),changed.comparisonKey(),version,changed.rows(),changed.qualificationBirths());
        assertTrue(SupportLeaderboard.matchesSnapshotVersion(sealed));
        return sealed;
    }
    static void rejectsBeforeWriterAndOnTypedReadback(Snapshot invalid) throws Exception {
        var mapper=mock(SupportLeaderboardPublicationMapper.class); var service=new SupportLeaderboardPublicationService(mapper);
        assert503(()->service.publish(invalid)); verifyNoInteractions(mapper);
        when(mapper.latest(anyString())).thenReturn(stored(11,invalid,invalid.context().evaluatedAt().plusSeconds(1)));
        beginRead(); try { assert503(()->service.latest(invalid.context(),null)); } finally { endRead(); }
    }
    static Candidate candidate(Context c, long id, long first, Instant birth) {
        Count count=new Count(first,Coverage.COMPLETE,Reason.NONE);
        return new Candidate(id,"客服"+id,null,"组一",Qualification.ACTIVE,count,new Count(0L,Coverage.COMPLETE,Reason.NONE),
            new Amount(BigDecimal.ZERO,c.currency(),c.referenceMonth(),AmountKind.DEPOSIT,Coverage.COMPLETE,Reason.NONE,"money-source"),birth);
    }
    @Test void legalBaseAndCoreEnrichedUpDownSameNewPublishAndDecode() throws Exception {
        Context yesterday=context(Board.firstPayment,"USDT","defA",NOW.minusSeconds(86400));
        Snapshot previous=SupportLeaderboard.calculate(yesterday,"old-source",Coverage.COMPLETE,
            List.of(candidate(yesterday,7,30,null),candidate(yesterday,8,20,null),candidate(yesterday,9,10,null)));
        Context c=context(Board.firstPayment,"USDT","defA",NOW);
        Snapshot base=SupportLeaderboard.calculate(c,"current-source",Coverage.COMPLETE,
            List.of(candidate(c,7,20,null),candidate(c,8,40,null),candidate(c,9,10,null),candidate(c,10,5,NOW.minusSeconds(40000))));
        Snapshot enriched=SupportLeaderboard.withMovement(base,List.of(new Publication(10,yesterday.evaluatedAt().plusSeconds(1),previous)));
        assertEquals(Set.of(MovementKind.UP,MovementKind.DOWN,MovementKind.SAME,MovementKind.NEW),
            new HashSet<>(enriched.rows().stream().map(r->r.movement().kind()).toList()));
        for (Snapshot valid:List.of(base,enriched)) {
            var mapper=mock(SupportLeaderboardPublicationMapper.class); var service=new SupportLeaderboardPublicationService(mapper);
            Stored row=stored(12,valid,NOW.plusSeconds(1)); when(mapper.lockPointer(anyString())).thenReturn(null);
            when(mapper.byVersionForUpdate(anyString(),anyString())).thenReturn(null,row);
            when(mapper.insert(anyMap())).thenReturn(1); when(mapper.advance(anyString(),isNull(),eq(12L))).thenReturn(1);
            assertEquals(valid,service.publish(valid).snapshot());
            when(mapper.latest(anyString())).thenReturn(row);
            beginRead(); try { assertEquals(valid,service.latest(c,valid.viewVersion()).snapshot()); } finally { endRead(); }
        }
    }
    @Test void formattedFakeVersionAndTamperedRowFailBeforeWriterAndTypedReadback() throws Exception {
        Snapshot valid=snapshot(context(Board.firstPayment,"USDT","defA",NOW),Coverage.COMPLETE);
        Snapshot fakeVersion=new Snapshot(valid.context(),valid.sourceVersion(),valid.candidateCoverage(),valid.state(),valid.comparisonKey(),
            "slb-v1:"+"a".repeat(64),valid.rows(),valid.qualificationBirths());
        assertTrue(SupportLeaderboard.validVersion(fakeVersion.viewVersion())); assertFalse(SupportLeaderboard.matchesSnapshotVersion(fakeVersion));
        rejectsBeforeWriterAndOnTypedReadback(fakeVersion);
        Row r=valid.rows().get(0); Row changedName=new Row(r.agentId(),"变更展示名",r.avatarUrl(),r.groupName(),r.qualification(),r.rank(),r.isTied(),
            r.firstPayment(),r.customers(),r.amount(),r.rankMetricCoverage(),r.movement());
        rejectsBeforeWriterAndOnTypedReadback(replaceFirst(valid,changedName,false));
    }
    @Test void matchingDigestDoesNotAllowWrongDeltaDirectionMissingOrFutureBaseline() throws Exception {
        Snapshot s=snapshot(context(Board.firstPayment,"USDT","defA",NOW),Coverage.COMPLETE);
        Instant at=NOW.minusSeconds(100); String baseline=s.viewVersion();
        List<Movement> bad=List.of(
            new Movement(MovementKind.UP,null,2,at,baseline,Reason.NONE),
            new Movement(MovementKind.UP,-1,2,at,baseline,Reason.NONE),
            new Movement(MovementKind.UP,9,2,at,baseline,Reason.NONE),
            new Movement(MovementKind.UP,1,null,at,baseline,Reason.NONE),
            new Movement(MovementKind.UP,1,0,at,baseline,Reason.NONE),
            new Movement(MovementKind.UP,1,2,null,baseline,Reason.NONE),
            new Movement(MovementKind.UP,1,2,NOW,baseline,Reason.NONE),
            new Movement(MovementKind.UP,1,2,NOW.plusSeconds(1),baseline,Reason.NONE),
            new Movement(MovementKind.UP,1,2,at,null,Reason.NONE),
            new Movement(MovementKind.UP,1,2,at,"bad-version",Reason.NONE),
            new Movement(MovementKind.UP,1,2,at,baseline,Reason.NO_BASELINE),
            new Movement(MovementKind.DOWN,1,2,at,baseline,Reason.NONE),
            new Movement(MovementKind.SAME,1,1,at,baseline,Reason.NONE),
            new Movement(MovementKind.SAME,0,2,at,baseline,Reason.NONE),
            new Movement(MovementKind.UP,0,1,at,baseline,Reason.NONE));
        for (Movement movement:bad) rejectsBeforeWriterAndOnTypedReadback(replaceFirst(s,withMovement(s.rows().get(0),movement),true));
    }
    @Test void matchingDigestRejectsUnprovedOrOutOfScopeNewAndComparableProvisional() throws Exception {
        Context c=context(Board.firstPayment,"USDT","defA",NOW); Instant baseline=NOW.minusSeconds(100);
        for (Instant birth:Arrays.asList(null,baseline,baseline.minusSeconds(1))) {
            Snapshot s=SupportLeaderboard.calculate(c,"tuple",Coverage.COMPLETE,List.of(candidate(c,7,0,birth)));
            rejectsBeforeWriterAndOnTypedReadback(replaceFirst(s,withMovement(s.rows().get(0),
                new Movement(MovementKind.NEW,null,null,baseline,s.viewVersion(),Reason.NONE)),true));
        }
        Context group=new Context(c.board(),c.rankMonth(),c.referenceMonth(),c.currency(),Scope.ownGroup,Set.of(1L),c.definitionVersion(),NOW);
        Snapshot grouped=SupportLeaderboard.calculate(group,"tuple",Coverage.COMPLETE,List.of(candidate(group,7,0,NOW.minusSeconds(10))));
        rejectsBeforeWriterAndOnTypedReadback(replaceFirst(grouped,withMovement(grouped.rows().get(0),
            new Movement(MovementKind.NEW,null,null,baseline,grouped.viewVersion(),Reason.NONE)),true));
        Snapshot newborn=SupportLeaderboard.calculate(c,"tuple",Coverage.COMPLETE,List.of(candidate(c,7,0,NOW.minusSeconds(10))));
        for (Movement bad:List.of(new Movement(MovementKind.NEW,0,null,baseline,newborn.viewVersion(),Reason.NONE),
            new Movement(MovementKind.NEW,null,1,baseline,newborn.viewVersion(),Reason.NONE)))
            rejectsBeforeWriterAndOnTypedReadback(replaceFirst(newborn,withMovement(newborn.rows().get(0),bad),true));
        Snapshot partial=snapshot(c,Coverage.PARTIAL);
        rejectsBeforeWriterAndOnTypedReadback(replaceFirst(partial,withMovement(partial.rows().get(0),
            new Movement(MovementKind.UP,1,2,baseline,partial.viewVersion(),Reason.NONE)),true));
    }
    @Test void matchingDigestRejectsUnavailableWithComparableFieldsOrNoReason() throws Exception {
        Snapshot s=snapshot(context(Board.firstPayment,"USDT","defA",NOW),Coverage.COMPLETE);
        List<Movement> bad=List.of(new Movement(MovementKind.UNAVAILABLE,0,null,null,null,Reason.NO_BASELINE),
            new Movement(MovementKind.UNAVAILABLE,null,1,null,null,Reason.NO_BASELINE),
            new Movement(MovementKind.UNAVAILABLE,null,null,NOW.minusSeconds(1),null,Reason.NO_BASELINE),
            new Movement(MovementKind.UNAVAILABLE,null,null,null,s.viewVersion(),Reason.NO_BASELINE),
            new Movement(MovementKind.UNAVAILABLE,null,null,null,null,Reason.NONE));
        for (Movement movement:bad) rejectsBeforeWriterAndOnTypedReadback(replaceFirst(s,withMovement(s.rows().get(0),movement),true));
    }
}
