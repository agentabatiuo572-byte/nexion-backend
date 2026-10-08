package ffdd.opsconsole.promotion.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import ffdd.opsconsole.platform.application.AuditReplayBusinessPermissionGuard;
import ffdd.opsconsole.platform.domain.*;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.promotion.web.OpsPromotionController;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;

class PromotionA2GovernanceTest {
    private final AuditReplayBusinessPermissionGuard guard=new AuditReplayBusinessPermissionGuard(null,mock(AdminOperatorRoleResolver.class),null,null,mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class));
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void auth(String... permissions){var auth=new UsernamePasswordAuthenticationToken(71L,"",Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList());auth.setDetails(Map.of("subjectType","ADMIN"));SecurityContextHolder.getContext().setAuthentication(auth);}
    Map<String,Object> params(){return values("obligationId","PR1","expectedRevision",3,"snapshotHash","a".repeat(64),"action","CANCEL","asset","USDT");}
    @Test void correctionCannotBorrowGeneralGrowthOrOtherDispositionPermission(){
        var command=new AuditReplayCommand("H","promotion_reward_correction",params());
        auth("platform_a2_proposal_create","growth_h4_write","growth_promotion_reward_reverse");assertEquals(403,guard.validateProposal(command).getCode());
        auth("platform_a2_proposal_create","growth_promotion_reward_cancel");assertEquals(0,guard.validateProposal(command).getCode());
        var malformed=params();malformed.put("amount","900");assertEquals(422,guard.validateProposal(new AuditReplayCommand("H","promotion_reward_correction",malformed)).getCode());
        malformed=params();malformed.put("expectedRevision",0);assertEquals(422,guard.validateApproval(new AuditReplayCommand("H","promotion_reward_correction",malformed)).getCode());
    }
    @Test void correctionDescriptorBindsTheExactObligation(){
        auth("platform_a2_proposal_create","growth_promotion_reward_cancel");var command=new AuditReplayCommand("H","promotion_reward_correction",params());
        var request=new AuditOperationProposalRequest("client text","PR1","before","after","maker","growth","fund",false,false,"gate","真实差错审批依据说明","H",command,new AuditLockTarget("H","promotion_reward","PR1"),null);
        var result=guard.validateProposalContext(request);assertEquals(0,result.getCode());assertEquals("PR1",result.getData().target().id());
        var wrong=new AuditOperationProposalRequest("client text","PR2","before","after","maker","growth","fund",false,false,"gate","真实差错审批依据说明","H",command,new AuditLockTarget("H","promotion_reward","PR2"),null);
        assertEquals(403,guard.validateProposalContext(wrong).getCode());
    }
    @Test void everyFrozenAdminEndpointHasItsOwnRequiredCapability() throws Exception {
        var spec=new ObjectMapper().readTree(Files.readString(Path.of("docs/specs/growth-promotions/openapi.json")));
        Map<String,String> actual=new HashMap<>();
        for(var method:OpsPromotionController.class.getDeclaredMethods()){
            String verb=null,path=null;if(method.isAnnotationPresent(GetMapping.class)){verb="get";path=method.getAnnotation(GetMapping.class).value()[0];}
            if(method.isAnnotationPresent(PostMapping.class)){verb="post";path=method.getAnnotation(PostMapping.class).value()[0];}
            if(method.isAnnotationPresent(PutMapping.class)){verb="put";path=method.getAnnotation(PutMapping.class).value()[0];}
            if(verb!=null){assertNotNull(method.getAnnotation(PreAuthorize.class));actual.put(verb+" /api/admin/growth"+path,method.getAnnotation(PreAuthorize.class).value());}
        }
        for(var paths=spec.get("paths").fields();paths.hasNext();){var entry=paths.next();if(!entry.getKey().startsWith("/api/admin/growth/"))continue;
            for(var methods=entry.getValue().fields();methods.hasNext();){var method=methods.next();String key=method.getKey()+" "+entry.getKey();assertEquals("hasAuthority('"+method.getValue().get("x-required-permission").asText()+"')",actual.remove(key),key);}}
        assertTrue(actual.isEmpty());
    }
    @Test void existingGrowthReplayUsesTheRealPromotionApprovalService() {
        var promotion=mock(PromotionAdminService.class);
        var growth=new ffdd.opsconsole.growth.application.OpsGrowthService(null,null,null,null,null,null,null,Optional.empty(),Optional.empty(),Optional.empty(),null,null,Optional.empty(),null,promotion);
        var context=new AuditReplayContext("checker","真实差错审批依据说明","correction-approval");var command=new AuditReplayCommand("H","promotion_reward_correction",params());
        doReturn(ffdd.opsconsole.shared.api.ApiResult.ok(values("obligationId","PR1"))).when(promotion).approveCorrection(command.params(),context);
        assertEquals(0,growth.replay(command,context).getCode());verify(promotion).approveCorrection(command.params(),context);
        var legacy=new ffdd.opsconsole.growth.application.OpsGrowthService(null,null,null,null,null,null,null,Optional.empty(),Optional.empty(),Optional.empty(),null,null,Optional.empty(),null);
        assertEquals(503,legacy.replay(command,context).getCode());
    }
}
