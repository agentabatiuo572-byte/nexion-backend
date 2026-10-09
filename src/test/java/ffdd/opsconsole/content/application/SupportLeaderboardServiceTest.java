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
    static Context context(Board b,String unit,Instant at){return new Context(b,b==Board.customers?null:YearMonth.of(2026,10),YearMonth.of(2026,10),unit,Scope.all,Set.of(),"support-leaderboard-v1",at);}
    static Candidate candidate(Context c,long id,long count){return new Candidate(id,"公开客服"+id,
        "/api/admin/content/support-workbench/leaderboard/"+id+"/avatar?assetVersion=1","当前组",SupportLeaderboard.Qualification.ACTIVE,
        new Count(count,Coverage.COMPLETE,Reason.NONE),new Count(count,Coverage.COMPLETE,Reason.NONE),
        new Amount(null,c.currency(),c.referenceMonth(),c.board()==Board.purchase?AmountKind.PURCHASE:AmountKind.DEPOSIT,Coverage.UNKNOWN,Reason.REFUNDS_UNKNOWN,"fixture-finance-source"),null);}
    static class Harness {
        final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
        final SupportLeaderboardAuthorizationMapper auth=mock(SupportLeaderboardAuthorizationMapper.class);
        final SupportLeaderboardMapper mapper=mock(SupportLeaderboardMapper.class);
        final SupportLeaderboardSourceService source=mock(SupportLeaderboardSourceService.class);
        final SupportLeaderboardPublicationService publication=mock(SupportLeaderboardPublicationService.class);
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
            service=service(avatars);
        }
        SupportLeaderboardService service(SupportAdminAvatarService avatarService){return new SupportLeaderboardService(ownership,auth,mapper,source,publication,publicationMapper,config,avatarService,transactions);}
        void cache(Instant at){Context c=context(Board.firstPayment,"USDT",at);Snapshot s=SupportLeaderboard.calculate(c,"cached-source",Coverage.COMPLETE,List.of(candidate(c,7,1),candidate(c,9,2)));
            cached=new Publication(23,at.plusSeconds(1),s);when(publicationMapper.latest(anyString())).thenReturn(mock(SupportLeaderboardPublicationMapper.Stored.class));
            when(publication.latest(any(),isNull())).thenReturn(cached);}
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
    }
    @Test void absentOrExpiredPublishesAndReturnsCommittedResultWithoutOuterRrReread(){for(boolean cached:List.of(false,true)){Harness h=new Harness();if(cached)h.cache(NOW.minusSeconds(400));
        var body=h.service.page(Map.of());assertEquals(NOW.toString(),body.get("asOf"));assertEquals(NOW.plusSeconds(1).toString(),body.get("publishedAt"));
        verify(h.publication).publish(any());verify(h.publication,times(cached?1:0)).latest(any(),isNull());}}
    @Test void genuineSourceFailurePreservesOldVersionAndExplicitStaleOrFailsWithoutHistory(){Harness h=new Harness();h.cache(NOW.minusSeconds(400));
        doThrow(new BizException(503,"SUPPORT_LEADERBOARD_SOURCE_FAILED")).when(h.source).readForAuthorizedLeaderboard(any());
        var body=h.service.page(Map.of());assertEquals(h.cached.snapshot().viewVersion(),body.get("viewVersion"));assertEquals(true,body.get("stale"));assertEquals(true,body.get("refreshFailed"));
        verify(h.publication,never()).publish(any());Harness absent=new Harness();doThrow(new BizException(503,"SUPPORT_LEADERBOARD_SOURCE_FAILED")).when(absent.source).readForAuthorizedLeaderboard(any());
        assertEquals(503,assertThrows(BizException.class,()->absent.service.page(Map.of())).getCode());verify(absent.publication,never()).publish(any());}
    @Test void corruptExistingPublicationIsNotTreatedAsMissingOrReplaced(){Harness h=new Harness();h.cache(NOW.minusSeconds(400));
        when(h.publication.latest(any(),isNull())).thenThrow(new BizException(503,"SUPPORT_LEADERBOARD_PUBLICATION_UNAVAILABLE"));
        assertEquals(503,assertThrows(BizException.class,()->h.service.page(Map.of())).getCode());verify(h.publication,never()).publish(any());}
    @Test void selectedSourceFailurePreservesPreviousButBaselineCorruptionNeverBecomesEmpty(){Harness h=new Harness();h.cache(NOW.minusSeconds(400));
        doAnswer(i->{Context c=i.getArgument(0);if(c.board()!=Board.customers)throw new BizException(503,"SUPPORT_LEADERBOARD_SOURCE_FAILED");
            return new SupportLeaderboardSourceService.Read(c,"tuple",Coverage.COMPLETE,List.of(candidate(c,7,1)),List.of(YearMonth.of(2026,10)),null);}).when(h.source).readForAuthorizedLeaderboard(any());
        assertEquals(true,h.service.page(Map.of()).get("refreshFailed"));verify(h.publication,never()).publish(any());
        Harness corrupt=new Harness();corrupt.cache(NOW.minusSeconds(400));when(corrupt.publication.previousBusinessDay(any())).thenThrow(new BizException(503,"SUPPORT_LEADERBOARD_PUBLICATION_UNAVAILABLE"));
        assertEquals(503,assertThrows(BizException.class,()->corrupt.service.page(Map.of())).getCode());verify(corrupt.publication,never()).publish(any());
    }
    @Test void nullProducerCannotLeakErrorOrBypassFinalRevocation(){Harness h=new Harness();doReturn(null).when(h.source).readForAuthorizedLeaderboard(any());
        assertEquals(503,assertThrows(BizException.class,()->h.service.page(Map.of())).getCode());verify(h.publication,never()).publish(any());
        Harness revoked=new Harness();doReturn(null).when(revoked.source).readForAuthorizedLeaderboard(any());
        when(revoked.auth.grants(7)).thenReturn(List.of(new Grant(1L,1L,1L,1L,"service_m1_read")),List.of());
        assertEquals(403,assertThrows(BizException.class,()->revoked.service.page(Map.of())).getCode());
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
    @Test void unknownDuplicatesForeignGroupAndHistoricalMonthCannotOpenUnprovenSlices(){Harness h=new Harness();
        for(Map<String,List<String>> bad:List.of(Map.of("sourceVersion",List.of("x")),Map.of("board",List.of("firstPayment","deposit")),
            Map.of("scope",List.of("ownGroup"),"groupId",List.of("99")),Map.of("month",List.of("2026-09")))){
            int expected=bad.containsKey("groupId")?403:422;assertEquals(expected,assertThrows(BizException.class,()->h.service.page(bad)).getCode());}
        verify(h.publication,never()).publish(any());}
    @Test void supervisorAndSuperGetOnlyCurrentManagedGroupsWhilePureSupervisorHasNoOwnGroup(){for(boolean superAdmin:List.of(false,true)){Harness h=new Harness();
        if(superAdmin){when(h.auth.roles(7)).thenReturn(List.of(new Role(1L,1L,"SUPER_ADMIN")));when(h.auth.qualifications(7)).thenReturn(List.of());}
        else when(h.auth.qualifications(7)).thenReturn(List.of(new SupportLeaderboardAuthorizationMapper.Qualification(1L,"SUPERVISOR",1L,null)));
        when(h.auth.managedGroups(7,superAdmin)).thenReturn(List.of(new Group(3L,"负责组",2L,7L)));
        doAnswer(i->{Context c=i.getArgument(0);return new SupportLeaderboardSourceService.Read(c,"tuple",Coverage.COMPLETE,List.of(candidate(c,9,2)),List.of(YearMonth.of(2026,10)),null);}).when(h.source).readForAuthorizedLeaderboard(any());
        var body=h.service.page(Map.of());var options=(List<?>)body.get("scopeOptions");assertEquals(2,options.size());assertEquals("managedGroups",((Map<?,?>)options.get(1)).get("scope"));
        assertNull(((Map<?,?>)body.get("self")).get("row"));assertEquals("NOT_A_CANDIDATE",((Map<?,?>)body.get("self")).get("reason"));
        assertEquals(403,assertThrows(BizException.class,()->h.service.page(Map.of("scope",List.of("ownGroup")))).getCode());}}
    @Test void producerCannotSubstituteAnUnauthorizedScopeBeforePublication(){Harness h=new Harness();
        doAnswer(i->{Context c=i.getArgument(0);if(c.board()==Board.customers)return new SupportLeaderboardSourceService.Read(c,"tuple",Coverage.COMPLETE,List.of(candidate(c,9,2)),List.of(YearMonth.of(2026,10)),null);
            Context replaced=new Context(c.board(),c.rankMonth(),c.referenceMonth(),c.currency(),Scope.ownGroup,Set.of(99L),c.definitionVersion(),c.evaluatedAt());
            return new SupportLeaderboardSourceService.Read(replaced,"tuple",Coverage.COMPLETE,List.of(candidate(replaced,9,2)),List.of(YearMonth.of(2026,10)),null);}).when(h.source).readForAuthorizedLeaderboard(any());
        assertEquals(409,assertThrows(BizException.class,()->h.service.page(Map.of())).getCode());verify(h.publication,never()).publish(any());
    }
    @Test void refreshIntervalUsesConfiguredBoundsAndNeverFallbacksOnMissingOrCorrupt(){for(String value:List.of("0","61","bad","5.5")){Harness h=new Harness();when(h.config.activeValue(SupportLeaderboardService.REFRESH_KEY)).thenReturn(Optional.of(value));
        assertEquals(503,assertThrows(BizException.class,()->h.service.page(Map.of())).getCode());verifyNoInteractions(h.source);}
        Harness absent=new Harness();when(absent.config.activeValue(SupportLeaderboardService.REFRESH_KEY)).thenReturn(Optional.empty());assertEquals(503,assertThrows(BizException.class,()->absent.service.page(Map.of())).getCode());
        for(String valid:List.of("1","60")){Harness h=new Harness();h.cache(NOW.minusSeconds(30));when(h.config.activeValue(SupportLeaderboardService.REFRESH_KEY)).thenReturn(Optional.of(valid));assertEquals(false,h.service.page(Map.of()).get("stale"));}}
    @Test void latePublishRaceReadsWinnerInFreshTransactionRatherThanOverwriting(){Harness h=new Harness();doThrow(new BizException(409,"SUPPORT_LEADERBOARD_LATE_EVALUATION")).when(h.publication).publish(any());
        Context c=context(Board.firstPayment,"USDT",NOW);Snapshot s=SupportLeaderboard.calculate(c,"winning-source",Coverage.COMPLETE,List.of(candidate(c,9,3)));
        Publication winner=new Publication(99,NOW.plusSeconds(2),s);when(h.publication.latest(any(),isNull())).thenReturn(winner);
        assertEquals(winner.snapshot().viewVersion(),h.service.page(Map.of()).get("viewVersion"));verify(h.transactions,times(3)).getTransaction(any());verify(h.transactions,times(3)).commit(any());}
    @Test void publicJsonContainsOnlyWhitelistedIdsDecimalsAndVersionBoundAvatarUrl() throws Exception {Harness h=new Harness();h.cache(NOW.minusSeconds(30));var body=h.service.page(Map.of());
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);for(String forbidden:List.of("qualificationBirths","customerId","orderNo","phone","email","objectKey","assetId","uploader","evidence"))assertFalse(json.contains(forbidden),forbidden);
        var row=(Map<?,?>)((List<?>)body.get("rows")).get(0);String avatar=(String)row.get("avatarUrl");assertTrue(avatar.contains("expectedVersion="));assertTrue(avatar.contains("board=firstPayment"));assertFalse(avatar.contains("assetVersion="));
        assertEquals("9",row.get("agentId"));assertNull(((Map<?,?>)row.get("amount")).get("value"));
    }
    @Test void moneyWhitelistPreservesPlainDecimalPrecisionAndStringAgentIds(){Harness h=new Harness();Context c=context(Board.deposit,"USDT",NOW.minusSeconds(30));
        var candidate=new Candidate(9,"公开客服",null,"当前组",SupportLeaderboard.Qualification.ACTIVE,new Count(0L,Coverage.COMPLETE,Reason.NONE),new Count(0L,Coverage.COMPLETE,Reason.NONE),
            new Amount(new BigDecimal("1000000000000000000.123456789"),"USDT",YearMonth.of(2026,10),AmountKind.DEPOSIT,Coverage.COMPLETE,Reason.NONE,"real-shaped-money-tuple"),null);
        Snapshot s=SupportLeaderboard.calculate(c,"tuple",Coverage.COMPLETE,List.of(candidate));when(h.publicationMapper.latest(anyString())).thenReturn(mock(SupportLeaderboardPublicationMapper.Stored.class));
        when(h.publication.latest(any(),isNull())).thenReturn(new Publication(23,c.evaluatedAt().plusSeconds(1),s));
        var row=(Map<?,?>)((List<?>)h.service.page(Map.of("board",List.of("deposit"))).get("rows")).get(0);
        assertEquals("9",row.get("agentId"));assertEquals("1000000000000000000.123456789",((Map<?,?>)row.get("amount")).get("value"));
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
        var real=new SupportAdminAvatarService(avatarMapper,mock(ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper.class),h.ownership,attachments,mock(SupportAttachmentPolicy.class),storage);
        TransactionSynchronizationManager.setActualTransactionActive(true);assertEquals(3,h.service(real).avatar(Map.of("expectedVersion",List.of(h.cached.snapshot().viewVersion())),9).bytes().length);
        assertEquals(404,assertThrows(BizException.class,()->real.supportContent(9L,null)).getCode());
        verify(storage,times(1)).get("private-key");assertEquals(403,assertThrows(BizException.class,()->real.publicLeaderboardContent(null)).getCode());
    }
    @Test void verifiedHistoricalMonthAvatarDoesNotRequireTargetCurrentServiceSeat(){Harness h=new Harness();YearMonth september=YearMonth.of(2026,9);
        Context c=new Context(Board.firstPayment,september,september,"USDT",Scope.all,Set.of(),"support-leaderboard-v1",NOW.minusSeconds(30));
        Candidate valid=candidate(c,9,1);Candidate disabled=new Candidate(valid.agentId(),valid.name(),valid.avatarUrl(),valid.groupName(),SupportLeaderboard.Qualification.DISABLED,
            valid.firstPayment(),valid.customers(),valid.amount(),null);Snapshot s=SupportLeaderboard.calculate(c,"verified-history-tuple",Coverage.COMPLETE,List.of(disabled));
        when(h.publicationMapper.latest(anyString())).thenReturn(mock(SupportLeaderboardPublicationMapper.Stored.class));when(h.publication.latest(any(),isNull())).thenReturn(new Publication(23,c.evaluatedAt().plusSeconds(1),s));
        doAnswer(i->{Context requested=i.getArgument(0);return new SupportLeaderboardSourceService.Read(requested,"tuple",Coverage.COMPLETE,List.of(candidate(requested,9,1)),List.of(september,YearMonth.of(2026,10)),null);}).when(h.source).readForAuthorizedLeaderboard(any());
        when(h.auth.avatarReference(9)).thenReturn(new AssetReference("historical-attached-asset",1L));when(h.avatars.publicLeaderboardContent(any())).thenReturn(new SupportAttachmentService.Content("image/png",new byte[]{1}));
        assertEquals(1,h.service.avatar(Map.of("month",List.of("2026-09"),"expectedVersion",List.of(s.viewVersion())),9).bytes().length);
        verify(h.ownership,never()).canReadAgent(any(),any());verify(h.publication,never()).publish(any());
    }
}
