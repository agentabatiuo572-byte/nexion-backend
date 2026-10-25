package ffdd.opsconsole.promotion.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromotionDeleteTest {
    final PromotionMapper db=mock(PromotionMapper.class);
    final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final PromotionAdminService admin=new PromotionAdminService(db,new PromotionContractValidator(new ObjectMapper()),null,null,null,null,null,
        null,null,idempotency,audit,mock(A2RuntimePolicy.class),null,mock(PromotionBannerService.class),null);
    final Map<String,Object> request=values("expectedRevision",7,"expectedDraftRevision",3,"reason","Delete isolated inactive promotion","evidenceRefs",List.of());

    @BeforeEach void setup(){
        authenticate("growth_promotion_archive","growth_promotion_read");
        when(db.write(anyString(),any(Object[].class))).thenReturn(1);
        when(db.list(anyString(),any(Object[].class))).thenReturn(List.of());
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    void authenticate(String... permissions){
        var auth=new UsernamePasswordAuthenticationToken(17L,"",Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(values("subjectType","ADMIN"));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    Map<String,Object> root(String state){
        var root=values("activity_id","PROMO-delete","revision",7L,"status",state,"active_version","DRAFT".equals(state)?null:1L,"draft_version","DRAFT".equals(state)?1L:null);
        when(db.activity(eq("PROMO-delete"),anyBoolean())).thenReturn(root);
        when(db.version(eq("PROMO-delete"),eq(1L),anyBoolean())).thenReturn(values("status","DRAFT","revision",3,"contract_json",json(values("startsAt",Instant.now().minusSeconds(60).toString(),"endsAt",Instant.now().plusSeconds(60).toString()))));
        return root;
    }
    void rejected(int status,String message,Runnable action){
        var error=assertThrows(BizException.class,action::run);assertEquals(status,error.getCode());assertEquals(message,error.getMessage());
        verify(db,never()).write(anyString(),any(Object[].class));
    }

    @ParameterizedTest @ValueSource(strings={"DRAFT","ENDED"})
    void deletionOnlyMarksTheLockedRootAndNeverChangesExistingObligations(String state){
        root(state);assertEquals(8L,admin.delete("PROMO-delete",request).get("revision"));
        verify(db).activity("PROMO-delete",true);
        verify(db).write("UPDATE nx_promotion SET deleted_at=NOW(6),deleted_by=?,revision=revision+1,updated_at=NOW(6) WHERE activity_id=? AND revision=? AND deleted_at IS NULL",17L,"PROMO-delete",7L);
        verify(db,times(1)).write(anyString(),any(Object[].class));
        verify(db,never()).count(anyString(),any(Object[].class));
    }
    @ParameterizedTest @ValueSource(strings={"ACTIVE","SCHEDULED","PAUSED","ARCHIVED"})
    void rejectsEveryOtherCurrentState(String state){root(state);rejected(409,"PROMOTION_STATE_INVALID",()->admin.delete("PROMO-delete",request));}
    @ParameterizedTest @ValueSource(strings={"PENDING_APPROVAL","APPROVED"})
    void pendingOrApprovedVersionCannotBeDeletedAsDraft(String state){
        root("DRAFT");when(db.version("PROMO-delete",1L,true)).thenReturn(values("status",state));
        rejected(409,"PROMOTION_STATE_INVALID",()->admin.delete("PROMO-delete",request));
    }
    @Test void naturalExpiryUsesThePublishedWindowAndKeepsHistoricalLifecycle(){
        root("ACTIVE");when(db.version("PROMO-delete",1L,true)).thenReturn(values("contract_json",json(values("startsAt",Instant.now().minusSeconds(120).toString(),"endsAt",Instant.now().minusSeconds(1).toString()))));
        assertEquals(8L,admin.delete("PROMO-delete",request).get("revision"));
        verify(db,never()).write(contains("SET status="),any(Object[].class));
    }
    @Test void draftEditedWithoutChangingRootRevisionCannotBeDeletedFromStaleConfirmation(){
        root("DRAFT");when(db.version("PROMO-delete",1L,true)).thenReturn(values("status","DRAFT","revision",4));
        rejected(409,"PROMOTION_CONCURRENT_CHANGE",()->admin.delete("PROMO-delete",request));
    }
    @Test void staleRevisionCannotDelete(){root("DRAFT").put("revision",8L);rejected(409,"PROMOTION_CONCURRENT_CHANGE",()->admin.delete("PROMO-delete",request));}
    @Test void deletedRootPreservesHistoricalReadAndRejectsEveryMutation(){
        root("DRAFT").put("deleted_at",Instant.now());
        when(db.requiredRow(anyString(),any(Object[].class))).thenReturn(values("reserved",2,"unpaid_orders",1,"pending",3,"unfulfilled",3,"obligations",3,"failed",0,"unknown_count",0,"reversing",0,"manual_count",0));
        var historical=admin.get("PROMO-delete");
        assertEquals(true,historical.get("deleted"));
        assertEquals(3L,map(historical.get("impact")).get("unfulfilledRewards"));
        assertEquals(2L,map(historical.get("impact")).get("reserved"));
        assertEquals(1L,admin.version("PROMO-delete",1).get("version"));
        assertEquals(List.of(),admin.versions("PROMO-delete",null,20).get("items"));
        rejected(404,"PROMOTION_RESOURCE_NOT_FOUND",()->admin.delete("PROMO-delete",request));
        rejected(404,"PROMOTION_RESOURCE_NOT_FOUND",()->admin.state("PROMO-delete","resume",values("expectedRevision",7,"reason","Resume deleted activity","evidenceRefs",List.of())));
        rejected(404,"PROMOTION_RESOURCE_NOT_FOUND",()->admin.newVersion("PROMO-delete",values("expectedRevision",7,"reason","Copy deleted activity","evidenceRefs",List.of()),true));
    }
    @Test void deletionRequiresTheArchiveAuthorityBeforeAnyDatabaseAccess(){
        authenticate("growth_promotion_edit");
        rejected(403,"PROMOTION_PERMISSION_DENIED",()->admin.command("deletePromotion","PROMO-delete","delete-denied-1",request,()->admin.delete("PROMO-delete",request)));
        verifyNoInteractions(idempotency);
    }
    @Test void successfulReceiptCanReplayAfterTheResourceDisappears(){
        var original=values("status","SUCCEEDED","commandId","PC-delete");
        when(idempotency.executeRetained(anyString(),anyString(),anyString(),eq(Map.class),any())).thenReturn(original);
        assertSame(original,admin.command("deletePromotion","PROMO-delete","delete-replay-1",request,()->{throw new AssertionError("Replayed mutation");}));
        verifyNoInteractions(db,audit);
    }
    @Test @SuppressWarnings("unchecked") void deletionUsesTheExistingAuditedCommandEnvelope(){
        root("DRAFT");when(idempotency.executeRetained(anyString(),anyString(),anyString(),eq(Map.class),any()))
            .thenAnswer(call->((Supplier<Map<String,Object>>)call.getArgument(4)).get());
        var result=admin.command("deletePromotion","PROMO-delete","delete-audited-1",request,()->admin.delete("PROMO-delete",request));
        assertEquals("SUCCEEDED",result.get("status"));assertEquals("deletePromotion",result.get("operation"));
        verify(audit,atLeastOnce()).recordRequired(any());
    }
    @Test void oldListSnapshotContainingADeletedActivityMustBeRefreshed(){
        when(db.requiredRow(startsWith("SELECT snapshot_id"),eq("PLS-old"),eq(17L))).thenReturn(values("expires_at",Instant.now().plusSeconds(60)));
        when(db.count(contains("p.deleted_at IS NOT NULL"),eq("PLS-old"),eq(17L))).thenReturn(1L);
        rejected(409,"PROMOTION_LIST_SNAPSHOT_EXPIRED",()->admin.page(Map.of(),"PLS-old",null,20));
    }
}
