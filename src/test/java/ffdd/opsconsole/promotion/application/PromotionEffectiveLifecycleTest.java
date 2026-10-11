package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromotionEffectiveLifecycleTest {
    private static final Instant NOW=Instant.parse("2026-10-11T00:00:00Z");
    private PromotionMapper db;
    private PromotionPolicyResolver resolver;
    private PromotionAdminService admin;
    private MockedStatic<Instant> clock;

    @BeforeEach void setup(){
        db=mock(PromotionMapper.class);resolver=mock(PromotionPolicyResolver.class);
        admin=new PromotionAdminService(db,mock(PromotionContractValidator.class),resolver,null,
            mock(PromotionNativeContractResolver.class),null,null,null,null,null,null,null,null,mock(PromotionBannerService.class));
        when(db.write(anyString(),any(Object[].class))).thenReturn(1);
        when(db.list(anyString(),any(Object[].class))).thenReturn(List.of());
        when(db.requiredRow(anyString(),any(Object[].class))).thenReturn(values("reserved",0,"unpaid_orders",0,
            "failed",0,"unknown_count",0,"reversing",0,"manual_count",0,"pending",0,"unfulfilled",0,"obligations",0));
        when(resolver.resolve(anyMap(),anyBoolean())).thenReturn(values("$authorization",values("content",values("separateMakerChecker",false))));
        var auth=new UsernamePasswordAuthenticationToken(17L,"",List.of(new SimpleGrantedAuthority("growth_promotion_read")));
        auth.setDetails(values("subjectType","ADMIN"));SecurityContextHolder.getContext().setAuthentication(auth);
        clock=mockStatic(Instant.class,CALLS_REAL_METHODS);clock.when(Instant::now).thenReturn(NOW);
    }
    @AfterEach void teardown(){clock.close();SecurityContextHolder.clearContext();}

    private Map<String,Object> contract(Instant start,Instant end){
        return values("name","test","category","PROMOTION","template","SKU_GIFT","startsAt",start.toString(),
            "endsAt",end.toString(),"displayTimezone","UTC","buyerAudience",values("devicePresence","ANY","registrationAge",values(),"lastValidPurchaseAge",values()),"rules",List.of(),"budgets",List.of());
    }
    private Map<String,Object> activity(String id,String state,Long draft,Instant start,Instant end,String draftState){
        var root=values("activity_id",id,"event_code",id,"revision",7,"status",state,"active_version",1,"draft_version",draft,"updated_at",NOW);
        var published=values("version",1,"revision",7,"status","PUBLISHED","contract_json",json(contract(start,end)));
        when(db.activity(eq(id),anyBoolean())).thenReturn(root);when(db.version(eq(id),eq(1L),anyBoolean())).thenReturn(published);
        if(draft!=null){
            var candidate=values("version",draft,"revision",7,"status",draftState,"contract_json",json(contract(NOW.minusSeconds(1),NOW.plusSeconds(86400))));
            when(db.version(eq(id),eq(draft),anyBoolean())).thenReturn(candidate);
        }
        return root;
    }
    private Map<String,Object> action(){return values("expectedRevision",7,"version",2,"draft",values("name","edited"));}
    private void rejected(String code,Runnable action){
        var failure=assertThrows(BizException.class,action::run);assertEquals(409,failure.getCode());assertEquals(code,failure.getMessage());
        verify(db,never()).write(anyString(),any(Object[].class));
    }

    @ParameterizedTest
    @CsvSource({
        "ACTIVE,-1,SCHEDULED","ACTIVE,0,ACTIVE","ACTIVE,1,ACTIVE","ACTIVE,9,ACTIVE","ACTIVE,10,ENDED","ACTIVE,11,ENDED",
        "SCHEDULED,-1,SCHEDULED","SCHEDULED,0,ACTIVE","SCHEDULED,1,ACTIVE","SCHEDULED,9,ACTIVE","SCHEDULED,10,ENDED","SCHEDULED,11,ENDED",
        "PAUSED,-1,PAUSED","PAUSED,0,PAUSED","PAUSED,9,PAUSED","PAUSED,10,ENDED","PAUSED,11,ENDED",
        "ENDED,-1,ENDED","ENDED,0,ENDED","ENDED,11,ENDED","ARCHIVED,-1,ARCHIVED","ARCHIVED,11,ARCHIVED",
        "DRAFT,-1,DRAFT","DRAFT,0,DRAFT","DRAFT,11,DRAFT"})
    void lifecycleUsesHalfOpenWindowAndNeverReopensExplicitStops(String stored,long seconds,String expected){
        assertEquals(expected,effectiveLifecycle(stored,contract(NOW,NOW.plusSeconds(10)),NOW.plusSeconds(seconds)));
    }
    @Test void unpublishedDraftNeedsNoInventedDates(){
        assertEquals("DRAFT",effectiveLifecycle("DRAFT",Map.of(),NOW));
        assertThrows(RuntimeException.class,()->effectiveLifecycle("ACTIVE",Map.of(),NOW));
    }
    @Test void detailAndPublicSharePublishedWindowWhileFutureDraftCannotReopenIt(){
        activity("expired","ACTIVE",2L,NOW.minusSeconds(10),NOW,"APPROVED");
        var detail=admin.get("expired");assertEquals("ENDED",detail.get("state"));assertEquals("APPROVED",detail.get("draftVersionState"));
        assertEquals(NOW.toString(),detail.get("endsAt"));assertEquals(NOW.toString(),detail.get("asOf"));
        var row=values("activity_id","expired","active_version",1,"status","ACTIVE","contract_json",json(contract(NOW.minusSeconds(10),NOW)));
        when(db.requiredRow(startsWith("SELECT p.*"),any(Object[].class))).thenReturn(row);
        assertEquals(detail.get("state"),new PromotionPublicService(db,null,null,mock(PromotionBannerService.class)).get(null,"expired").get("state"));
    }
    @Test void allFilteredRowsAndSummaryFreezeBeforePagination(){
        activity("starts-now","SCHEDULED",null,NOW,NOW.plusSeconds(10),"DRAFT");
        activity("active","ACTIVE",null,NOW.minusSeconds(10),NOW.plusSeconds(10),"DRAFT");
        activity("ended","ACTIVE",null,NOW.minusSeconds(10),NOW,"DRAFT");
        activity("paused","PAUSED",null,NOW.minusSeconds(10),NOW.plusSeconds(10),"DRAFT");
        var snapshots=new HashMap<String,Map<String,Object>>();
        when(db.list(startsWith("SELECT p.activity_id"),any(Object[].class))).thenAnswer(call->{
            assertFalse(call.getArgument(0,String.class).contains("AND p.status"));
            return List.of("starts-now","active","ended","paused").stream().map(id->{
                var root=db.activity(id,false);return values("activity_id",id,"status",root.get("status"),"contract_json",db.version(id,1,false).get("contract_json"));
            }).toList();
        });
        when(db.write(startsWith("INSERT INTO nx_promotion_list_snapshot"),any(Object[].class))).thenAnswer(call->{
            Object[] a=(Object[])call.getRawArguments()[1];snapshots.put(text(a[0]),values("snapshot_id",a[0],"actor_id",a[1],"query_json",a[2],
                "rows_json",a[3],"summary_json",a[4],"total",a[5],"as_of",a[6],"expires_at",a[7]));return 1;
        });
        when(db.requiredRow(startsWith("SELECT * FROM nx_promotion_list_snapshot"),any(Object[].class)))
            .thenAnswer(call->snapshots.get(text(((Object[])call.getRawArguments()[1])[0])));
        var first=admin.page(values("state","ACTIVE"),null,null,1);
        assertEquals(2,number(first.get("total")));assertEquals(2,number(map(first.get("summary")).get("activeActivities")));
        assertEquals("starts-now",maps(first.get("items")).get(0).get("activityId"));assertEquals("1",first.get("nextCursor"));
        assertEquals(NOW.toString(),map(first.get("summary")).get("asOf"));
        var later=NOW.plusSeconds(60);clock.when(Instant::now).thenReturn(later);
        var second=admin.page(values("state","ACTIVE"),text(first.get("querySnapshot")),"1",1);
        assertEquals("active",maps(second.get("items")).get(0).get("activityId"));assertEquals("ACTIVE",maps(second.get("items")).get(0).get("state"));
        assertEquals(first.get("summary"),second.get("summary"));assertEquals(first.get("asOf"),second.get("asOf"));assertEquals(false,second.get("hasMore"));
        var fresh=admin.page(values("state","ENDED"),null,null,1);
        assertEquals(4,number(fresh.get("total")));assertEquals(0,number(map(fresh.get("summary")).get("activeActivities")));
    }

    @ParameterizedTest @ValueSource(strings={"ACTIVE","SCHEDULED","PAUSED","ENDED","ARCHIVED"})
    void expiredOrExplicitlyStoppedActivitiesRejectSave(String state){
        activity("test",state,2L,NOW.minusSeconds(1),NOW,"DRAFT");rejected("PROMOTION_READ_ONLY",()->admin.save("test",action()));
    }
    @ParameterizedTest @ValueSource(strings={"ACTIVE","SCHEDULED","PAUSED","ENDED","ARCHIVED"})
    void expiredOrExplicitlyStoppedActivitiesRejectNewVersion(String state){
        activity("test",state,null,NOW.minusSeconds(1),NOW,"DRAFT");rejected("PROMOTION_DRAFT_EXISTS_OR_READ_ONLY",()->admin.newVersion("test",action(),false));
    }
    @ParameterizedTest @CsvSource({"submit,DRAFT","approve,PENDING_APPROVAL","reject,PENDING_APPROVAL","withdraw,APPROVED","publish,APPROVED"})
    void futureDraftCannotBypassExpiredPublishedVersion(String operation,String draftState){
        activity("test","ACTIVE",2L,NOW.minusSeconds(1),NOW,draftState);rejected("PROMOTION_VERSION_NOT_CURRENT",()->admin.versionAction("test",operation,action()));
        verifyNoInteractions(resolver);
    }
    @ParameterizedTest @CsvSource({"ACTIVE,pause","ACTIVE,end","SCHEDULED,pause","SCHEDULED,end","PAUSED,resume","PAUSED,end"})
    void expirationRejectsLiveStateSwitches(String state,String operation){
        activity("test",state,null,NOW.minusSeconds(1),NOW,"DRAFT");rejected("PROMOTION_STATE_INVALID",()->admin.state("test",operation,action()));
    }
    @ParameterizedTest @CsvSource({"ACTIVE,pause,PAUSED","SCHEDULED,pause,PAUSED","ACTIVE,end,ENDED","PAUSED,end,ENDED","PAUSED,resume,ACTIVE"})
    void validWindowStillAllowsAuthorizedStateSwitches(String state,String operation,String expected){
        activity("test",state,null,NOW.minusSeconds(1),NOW.plusSeconds(1),"DRAFT");admin.state("test",operation,action());
        verify(db).write(startsWith("UPDATE nx_promotion SET status=?"),eq(expected),eq("test"));
        var calls=inOrder(db);calls.verify(db).activity("test",true);calls.verify(db,times("resume".equals(operation)?2:1)).version("test",1,true);
    }
    @ParameterizedTest @CsvSource({"submit,DRAFT","approve,PENDING_APPROVAL","reject,PENDING_APPROVAL","withdraw,APPROVED","publish,APPROVED"})
    void livePublishedVersionStillAllowsDraftWorkflow(String operation,String draftState){
        activity("test","ACTIVE",2L,NOW.minusSeconds(1),NOW.plusSeconds(1),draftState);
        assertEquals(8,number(admin.versionAction("test",operation,action()).get("revision")));
    }
    @Test void publishingCannotExtendAnOldWindowThatEndsDuringGovernanceChecks(){
        activity("test","ACTIVE",2L,NOW.minusSeconds(1),NOW.plusSeconds(1),"APPROVED");var later=NOW.plusSeconds(2);
        var policies=values("$authorization",values("content",values("separateMakerChecker",false)));
        when(resolver.resolve(anyMap(),anyBoolean())).thenAnswer(call->{clock.when(Instant::now).thenReturn(later);return policies;});
        rejected("PROMOTION_VERSION_NOT_CURRENT",()->admin.versionAction("test","publish",action()));
    }
    @Test void liveSaveAndNewVersionStillWork(){
        activity("save","PAUSED",2L,NOW.minusSeconds(1),NOW.plusSeconds(1),"DRAFT");assertEquals(8,number(admin.save("save",action()).get("revision")));
        activity("version","SCHEDULED",null,NOW.plusSeconds(1),NOW.plusSeconds(10),"DRAFT");
        when(db.count(startsWith("SELECT COALESCE(MAX(version)"),any(Object[].class))).thenReturn(2L);
        assertEquals("version:2",admin.newVersion("version",action(),false).get("id"));
    }
    @ParameterizedTest @ValueSource(strings={"ACTIVE","SCHEDULED","PAUSED","ENDED"})
    void endedByTimeOrCommandCanArchiveWithoutChangingOldVersions(String state){
        activity("test",state,null,NOW.minusSeconds(1),NOW,"DRAFT");assertEquals(8,number(admin.state("test","archive",action()).get("revision")));
        verify(db).write(startsWith("UPDATE nx_promotion SET status=?"),eq("ARCHIVED"),eq("test"));
        verify(db,never()).write(contains("nx_promotion_version"),any(Object[].class));
        verify(db).count(eq("SELECT COUNT(*) FROM nx_promotion_reservation WHERE activity_id=? AND status='RESERVED'"),eq("test"));
        verify(db).count(eq("SELECT COUNT(*) FROM nx_promotion_reward WHERE activity_id=? AND status NOT IN ('ISSUED','CANCELLED','REVERSED')"),eq("test"));
    }
    @ParameterizedTest @ValueSource(strings={"reservation","reward"})
    void openObligationsStillBlockTimeDerivedArchive(String kind){
        activity("test","ACTIVE",null,NOW.minusSeconds(1),NOW,"DRAFT");
        when(db.count(contains("FROM nx_promotion_"+kind),any(Object[].class))).thenReturn(1L);
        rejected("PROMOTION_OBLIGATIONS_OPEN",()->admin.state("test","archive",action()));
    }
    @Test void settledRewardStatesRemainOutsideTheArchiveObligationCount(){
        activity("test","ACTIVE",null,NOW.minusSeconds(1),NOW,"DRAFT");
        when(db.count(contains("FROM nx_promotion_reward"),any(Object[].class))).thenReturn(1L);
        when(db.count(eq("SELECT COUNT(*) FROM nx_promotion_reward WHERE activity_id=? AND status NOT IN ('ISSUED','CANCELLED','REVERSED')"),eq("test"))).thenReturn(0L);
        assertEquals(8,number(admin.state("test","archive",action()).get("revision")));
        verify(db).count(eq("SELECT COUNT(*) FROM nx_promotion_reward WHERE activity_id=? AND status NOT IN ('ISSUED','CANCELLED','REVERSED')"),eq("test"));
        verify(db).write(startsWith("UPDATE nx_promotion SET status=?"),eq("ARCHIVED"),eq("test"));
    }
    @Test void liveActivityCannotArchiveEvenWithoutObligations(){
        activity("test","ACTIVE",null,NOW.minusSeconds(1),NOW.plusSeconds(1),"DRAFT");
        rejected("PROMOTION_STATE_INVALID",()->admin.state("test","archive",action()));
    }
    @Test void firstPublicationHasNoPublishedLifecycleToExpire(){
        var root=activity("test","DRAFT",null,NOW.minusSeconds(1),NOW,"DRAFT");root.put("active_version",null);root.put("draft_version",2L);
        var candidate=values("version",2,"revision",7,"status","APPROVED","contract_json",json(contract(NOW,NOW.plusSeconds(10))));
        when(db.version(eq("test"),eq(2L),anyBoolean())).thenReturn(candidate);
        assertEquals(8,number(admin.versionAction("test","publish",action()).get("revision")));
        verify(db).write(startsWith("UPDATE nx_promotion SET category=?"),eq("PROMOTION"),eq("SKU_GIFT"),eq(2L),eq("ACTIVE"),eq("test"));
    }
    @Test void revisionConflictStillWinsBeforeAnyLifecycleWrite(){
        activity("test","ACTIVE",null,NOW.minusSeconds(1),NOW,"DRAFT");var stale=action();stale.put("expectedRevision",6);
        rejected("PROMOTION_CONCURRENT_CHANGE",()->admin.state("test","archive",stale));verify(db,never()).version(anyString(),anyLong(),eq(true));
    }
    @Test void copyingEndedConfigurationOnlyCreatesAnUnpublishedActivity(){
        activity("test","ENDED",null,NOW.minusSeconds(1),NOW,"DRAFT");assertEquals("PROMOTION",admin.newVersion("test",action(),true).get("type"));
        verify(db,never()).write(startsWith("UPDATE nx_promotion SET"),any(Object[].class));
        var start=timestamp(NOW.minusSeconds(1));var end=timestamp(NOW);
        verify(db).write(startsWith("INSERT INTO nx_promotion_version"),any(),eq(1L),eq(start),eq(end),any(),any());
    }
}
