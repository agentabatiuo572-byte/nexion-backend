package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.content.domain.SupportAvatarAsset;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.content.mapper.SupportLeaderboardAuthorizationMapper.*;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupportLeaderboardServiceTest {
    static final Instant NOW=Instant.parse("2026-10-09T01:00:00.123456Z");
    static void login(String subject,String... caps){var token=new UsernamePasswordAuthenticationToken("7","unused",Arrays.stream(caps).map(SimpleGrantedAuthority::new).toList());
        token.setDetails(Map.of("subjectType",subject));SecurityContextHolder.getContext().setAuthentication(token);}
    @AfterEach void clear(){SecurityContextHolder.clearContext();TransactionSynchronizationManager.clear();}
    static Context context(Board b,String unit,Instant at){return new Context(b,b==Board.customers?null:YearMonth.of(2026,10),YearMonth.of(2026,10),unit,Scope.all,Set.of(),SupportLeaderboardPolicy.DEFINITION,at);}
    static Candidate candidate(Context c,long id,long count){return new Candidate(id,"公开客服"+id,
        "/api/admin/content/support-workbench/leaderboard/"+id+"/avatar?assetVersion=1","当前组",SupportLeaderboard.Qualification.ACTIVE,
        new Count(count,Coverage.COMPLETE,Reason.NONE),new Count(count,Coverage.COMPLETE,Reason.NONE),
        new Amount(c.board()==Board.netContribution?BigDecimal.valueOf(count):null,c.currency(),c.referenceMonth(),AmountKind.NET_CONTRIBUTION,c.board()==Board.netContribution?Coverage.COMPLETE:Coverage.UNKNOWN,c.board()==Board.netContribution?Reason.NONE:Reason.SOURCE_INCOMPLETE,"fixture-finance-source"),null);}
    static class Harness {
        final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
        final SupportLeaderboardAuthorizationMapper auth=mock(SupportLeaderboardAuthorizationMapper.class);
        final SupportLeaderboardMapper mapper=mock(SupportLeaderboardMapper.class);
        final SupportLeaderboardSourceService source=mock(SupportLeaderboardSourceService.class);
        final SupportLeaderboardPublicationService publication=mock(SupportLeaderboardPublicationService.class);
        final SupportLeaderboardCache cache=mock(SupportLeaderboardCache.class);
        final SupportLeaderboardPublicationMapper publicationMapper=mock(SupportLeaderboardPublicationMapper.class);
        final PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        final SupportAdminAvatarService avatars=mock(SupportAdminAvatarService.class);
        final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
        final SupportLeaderboardService service;
        Publication cached;
        Harness(){login("ADMIN","service_m1_read");when(ownership.actorId()).thenReturn(7L);
            when(auth.account(7)).thenReturn(new Account(7L,1L));when(auth.roles(7)).thenReturn(List.of(new Role(1L,1L,"SUPPORT")));
            when(auth.grants(7)).thenReturn(List.of(new Grant(1L,1L,1L,1L,"service_m1_read")));
            when(auth.qualifications(7)).thenReturn(List.of(new SupportLeaderboardAuthorizationMapper.Qualification(1L,"SERVICE",1L,1L)));
            when(auth.ownGroups(7)).thenReturn(List.of(new Member(1L,1L,1L,"本人组",1L)));
            when(mapper.nowUtc()).thenReturn(LocalDateTime.ofInstant(NOW,ZoneOffset.UTC));
            when(config.activeValue(SupportLeaderboardService.REFRESH_KEY)).thenReturn(Optional.of("5"));
            when(source.readForAuthorizedLeaderboard(any())).thenAnswer(i->{Context c=i.getArgument(0);return new SupportLeaderboardSourceService.Read(c,"fixture-real-shaped-tuple",Coverage.COMPLETE,
                List.of(candidate(c,7,1),candidate(c,9,2)),List.of(YearMonth.of(2026,10)),null);});
            when(publication.previousBusinessDay(any())).thenReturn(List.of());
            when(publication.publish(any())).thenAnswer(i->new Publication(24,NOW.plusSeconds(1),i.getArgument(0)));
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(cache.current()).thenThrow(new BizException(503,"SUPPORT_LEADERBOARD_CACHE_NOT_READY"));
            service=service(avatars);
        }
        SupportLeaderboardService service(SupportAdminAvatarService avatarService){return new SupportLeaderboardService(ownership,auth,cache,avatarService,Clock.fixed(NOW,ZoneOffset.UTC));}
        void cache(Instant at){Context c=context(Board.netContribution,"USDT",at);Snapshot s=SupportLeaderboard.calculate(c,"cached-source",Coverage.COMPLETE,List.of(candidate(c,7,1),candidate(c,9,2)));
            cached=new Publication(23,at.plusSeconds(1),s);when(publicationMapper.latest(anyString())).thenReturn(mock(SupportLeaderboardPublicationMapper.Stored.class));
            when(publication.latest(any(),isNull())).thenReturn(cached);
            doReturn(new SupportLeaderboardCache.Generation(Map.of(SupportLeaderboardPublicationService.streamKey(c),new SupportLeaderboardCache.Entry(cached,false)))).when(cache).current();}
    }
    @Test void ordinarySupportCanReadOtherGroupPublicRowsButNeverPrivateAllScope(){Harness h=new Harness();h.cache(NOW.minusSeconds(30));
        var body=h.service.page(Map.of());assertEquals(h.cached.snapshot().viewVersion(),body.get("viewVersion"));
        List<?> rows=(List<?>)body.get("rows");Map<?,?> other=(Map<?,?>)rows.get(0);assertEquals("9",other.get("agentId"));assertEquals(false,other.get("canViewCustomers"));
        verify(h.ownership,never()).queryScope(any(),any(),any());verify(h.publication,never()).publish(any());
        var bindings=mock(SupportBindingMapper.class);var groups=mock(SupportGroupMapper.class);when(bindings.roles(7L)).thenReturn(List.of("SUPPORT"));
        var original=new SupportOwnershipService(bindings,groups);
        assertEquals(403,assertThrows(BizException.class,()->original.queryScope(ReadMode.ALL,null,null)).getCode());
        assertFalse(original.canReadAgent(7L,9L));
    }
    @Test void cacheKeepsVersionAcrossPageSelfAndDetailDespiteNewSourceClock(){Harness h=new Harness();h.cache(NOW.minusSeconds(30));String v=h.cached.snapshot().viewVersion();
        var first=h.service.page(Map.of("pageSize",List.of("1")));var second=h.service.page(Map.of("pageNum",List.of("2"),"pageSize",List.of("1"),"expectedVersion",List.of(v)));
        var detail=h.service.detail(Map.of("expectedVersion",List.of(v)),7);assertEquals(v,first.get("viewVersion"));assertEquals(v,second.get("viewVersion"));assertEquals(v,detail.get("viewVersion"));
        verify(h.publication,never()).publish(any());assertEquals("7",((Map<?,?>)detail.get("row")).get("agentId"));
        assertEquals(2,((Map<?,?>)first.get("self")).get("pageNum"));
        assertEquals(first.get("podium"),second.get("podium"));
        Map<?,?> podium=(Map<?,?>)second.get("podium");assertEquals(2,podium.get("total"));
        assertEquals(2,((List<?>)podium.get("rows")).size());
        assertFalse(((Map<?,?>)((List<?>)podium.get("rows")).get(0)).containsKey("userId"));
    }





    @Test void sameExactJwtAndDbApiCapabilityMustIntersectAndAdminSubjectRequired(){for(String jwt:List.of("service_m3_read","platform_a1_read")){Harness h=new Harness();login("ADMIN",jwt);
        assertEquals(403,assertThrows(BizException.class,()->h.service.page(Map.of())).getCode());verifyNoInteractions(h.source,h.publication);}
        Harness app=new Harness();login("USER","service_m1_read");assertEquals(403,assertThrows(BizException.class,()->app.service.page(Map.of())).getCode());
        Harness anonymous=new Harness();SecurityContextHolder.clearContext();when(anonymous.ownership.actorId()).thenThrow(new BizException(401,"LOGIN_REQUIRED"));
        assertEquals(401,assertThrows(BizException.class,()->anonymous.service.page(Map.of())).getCode());}
    @Test void inactiveMissingQualificationAndPureRoleCannotGrantPublicRead(){for(boolean missingAccount:List.of(false,true)){Harness h=new Harness();if(missingAccount)when(h.auth.account(7)).thenReturn(null);
        else when(h.auth.qualifications(7)).thenReturn(List.of());assertEquals(403,assertThrows(BizException.class,()->h.service.page(Map.of())).getCode());verifyNoInteractions(h.source);}}
    @Test void revokedFinalGrantWinsOverSourceFailureAndExpectedVersionConflict(){for(boolean sourceFailure:List.of(false,true)){Harness h=new Harness();h.cache(NOW.minusSeconds(30));
        when(h.auth.grants(7)).thenReturn(List.of(new Grant(1L,1L,1L,1L,"service_m1_read")),List.of());
        if(sourceFailure)doThrow(new BizException(503,"SUPPORT_LEADERBOARD_SOURCE_FAILED")).when(h.source).readForAuthorizedLeaderboard(any());
        assertEquals(403,assertThrows(BizException.class,()->h.service.page(Map.of("expectedVersion",List.of("slb-v1:"+"a".repeat(64))))).getCode());}}
    @Test void currentGroupChangeIs409OnlyWhileReadStillAllowed(){Harness h=new Harness();h.cache(NOW.minusSeconds(30));when(h.auth.ownGroups(7)).thenReturn(
        List.of(new Member(1L,1L,1L,"本人组",1L)),List.of(new Member(2L,2L,1L,"新组",1L)));
        assertEquals(409,assertThrows(BizException.class,()->h.service.page(Map.of())).getCode());}





    @Test void publicJsonContainsOnlyWhitelistedIdsDecimalsAndVersionBoundAvatarUrl() throws Exception {Harness h=new Harness();h.cache(NOW.minusSeconds(30));var body=h.service.page(Map.of());
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);for(String forbidden:List.of("qualificationBirths","customerId","orderNo","phone","email","objectKey","assetId","uploader","evidence"))assertFalse(json.contains(forbidden),forbidden);
        var row=(Map<?,?>)((List<?>)body.get("rows")).get(0);String avatar=(String)row.get("avatarUrl");assertTrue(avatar.contains("expectedVersion="));assertTrue(avatar.contains("board=netContribution"));assertFalse(avatar.contains("assetVersion="));
        assertEquals("9",row.get("agentId"));assertEquals("2",((Map<?,?>)row.get("amount")).get("value"));
    }


    @Test void avatarMustBindPublicCandidateVersionAndCurrentAssetBeforeBytes(){Harness h=new Harness();h.cache(NOW.minusSeconds(30));String version=h.cached.snapshot().viewVersion();
        when(h.auth.avatarReference(9)).thenReturn(new AssetReference("private-asset",1L));when(h.avatars.publicLeaderboardContent(any())).thenReturn(new SupportAttachmentService.Content("image/png",new byte[]{1,2}));
        assertEquals(2,h.service.avatar(Map.of("expectedVersion",List.of(version)),9).bytes().length);
        when(h.auth.avatarReference(9)).thenReturn(new AssetReference("changed-asset",2L));assertEquals(409,assertThrows(BizException.class,()->h.service.avatar(Map.of("expectedVersion",List.of(version)),9)).getCode());
        assertEquals(404,assertThrows(BizException.class,()->h.service.avatar(Map.of("expectedVersion",List.of(version)),99)).getCode());
        assertEquals(422,assertThrows(BizException.class,()->h.service.avatar(Map.of(),9)).getCode());verify(h.avatars,times(1)).publicLeaderboardContent(any());
    }
    @Test void avatarHelperReusesBytesAndOldPrivateScopeRemainsClosed() throws Exception {Harness h=new Harness();h.cache(NOW.minusSeconds(30));
        var avatarMapper=mock(SupportAdminAvatarMapper.class);var attachments=mock(SupportAttachmentService.class);var storage=mock(ObjectStorageService.class);
        String asset="11111111-1111-1111-1111-111111111111";when(h.auth.avatarReference(9)).thenReturn(new AssetReference(asset,1L));
        when(attachments.actor("ADMIN")).thenReturn(7L);when(avatarMapper.reference(9L)).thenReturn(Map.of("assetId",asset,"version",1L));
        when(avatarMapper.lock(asset)).thenReturn(new SupportAvatarAsset(asset,7L,"client-id","idem-key","digest","image/png",3L,"private-key","ATTACHED",9L,NOW.plusSeconds(100).atOffset(ZoneOffset.UTC).toLocalDateTime()));
        when(storage.get("private-key")).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));
        var real=new SupportAdminAvatarService(avatarMapper,mock(ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper.class),h.ownership,attachments,mock(SupportAttachmentPolicy.class),storage,mock(ffdd.opsconsole.content.mapper.SupportGroupMapper.class));
        TransactionSynchronizationManager.setActualTransactionActive(true);assertEquals(3,h.service(real).avatar(Map.of("expectedVersion",List.of(h.cached.snapshot().viewVersion())),9).bytes().length);
        assertEquals(404,assertThrows(BizException.class,()->real.supportContent(9L,null)).getCode());
        verify(storage,times(1)).get("private-key");assertEquals(403,assertThrows(BizException.class,()->real.publicLeaderboardContent(null)).getCode());
    }




    private static SupportLeaderboardService service(Harness h,SupportLeaderboardPublicationService publication) {
        return new SupportLeaderboardService(h.ownership,h.auth,h.cache,h.avatars,Clock.fixed(NOW,ZoneOffset.UTC));
    }
    private static SupportLeaderboardPublicationService legacyPublication(Harness h) throws Exception {
        Context old=new Context(Board.netContribution,YearMonth.of(2026,10),YearMonth.of(2026,10),"USDT",Scope.all,Set.of(),"support-leaderboard-v1",NOW.minusSeconds(30));
        Snapshot snapshot=SupportLeaderboardPublicationServiceTest.snapshot(old,Coverage.COMPLETE);
        h.cached=new Publication(23,old.evaluatedAt().plusSeconds(1),snapshot);
        when(h.publicationMapper.latest(anyString())).thenReturn(SupportLeaderboardPublicationServiceTest.stored(23,snapshot,h.cached.publishedAt()));
        var real=spy(new SupportLeaderboardPublicationService(h.publicationMapper));
        doAnswer(i->new Publication(24,NOW.plusSeconds(1),i.getArgument(0))).when(real).publish(any());
        return real;
    }
    @Test void expiredSnapshotNeverRefreshesAndPreservesTrueAsOf(){Harness h=new Harness();h.cache(NOW.minusSeconds(601));
        var body=h.service.page(Map.of());assertEquals(true,body.get("stale"));assertEquals(h.cached.snapshot().context().evaluatedAt().toString(),body.get("asOf"));
        verifyNoInteractions(h.source);verify(h.publication,never()).publish(any());}
    @Test void missingRedisFailsClosedWithoutSourceOrPublicationReads(){Harness h=new Harness();assertEquals("SUPPORT_LEADERBOARD_CACHE_NOT_READY",assertThrows(BizException.class,()->h.service.page(Map.of())).getMessage());
        verifyNoInteractions(h.source,h.publication,h.publicationMapper);}
    @Test void publishedHistoryIsSelectableAndEmptyScopeCannotBorrowGlobal(){Harness h=new Harness();YearMonth month=YearMonth.of(2026,9);
        Context c=new Context(Board.firstPayment,month,month,"USDT",Scope.all,Set.of(),SupportLeaderboardPolicy.DEFINITION,NOW.minusSeconds(30));
        var snapshot=SupportLeaderboard.calculate(c,"history",Coverage.COMPLETE,List.of(candidate(c,9,1)));
        var publication=new Publication(23,NOW,snapshot);doReturn(new SupportLeaderboardCache.Generation(Map.of(SupportLeaderboardPublicationService.streamKey(c),new SupportLeaderboardCache.Entry(publication,true)))).when(h.cache).current();
        var body=h.service.detail(Map.of("board",List.of("firstPayment"),"month",List.of("2026-09"),"expectedVersion",List.of(snapshot.viewVersion())),9);
        assertEquals(List.of("2026-09"),body.get("selectableMonths"));assertEquals(true,body.get("refreshFailed"));verifyNoInteractions(h.source);
        assertEquals(503,assertThrows(BizException.class,()->h.service.page(Map.of("scope",List.of("ownGroup")))).getCode());}
    @Test void crossMonthDefaultSelectsNewMonthAndOldMonthRequiresExplicitQuery(){Harness h=new Harness();h.cache(NOW);
        var nextMonth=Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"),ZoneOffset.UTC);
        var service=new SupportLeaderboardService(h.ownership,h.auth,h.cache,h.avatars,nextMonth);
        assertEquals(503,assertThrows(BizException.class,()->service.page(Map.of())).getCode());
        assertEquals("2026-10",service.page(Map.of("month",List.of("2026-10"))).get("rankMonth"));}
}
