package ffdd.opsconsole.promotion.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromotionEvidenceCommandTest {
    private static final String ASSET="YWRtaW4vZ3Jvd3RoL3Byb21vdGlvbi1ldmlkZW5jZS8yMDI2MTAyMy83MmFjZjlhZi1lZDA5LTRiNTQtOWU2YS04ZDIwYTA4MjVhMTIucG5n";
    private static final String KEY="admin/growth/promotion-evidence/20261023/72acf9af-ed09-4b54-9e6a-8d20a0825a12.png";
    private final PromotionMapper db=mock(PromotionMapper.class);
    private final ObjectStorageService storage=mock(ObjectStorageService.class);
    private final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    private final PromotionAdminService admin=new PromotionAdminService(db,new PromotionContractValidator(new ObjectMapper()),null,null,null,null,null,
            null,null,idempotency,mock(AuditLogService.class),mock(A2RuntimePolicy.class),null,new PromotionBannerService(db,storage),null);

    @AfterEach void clearAuthentication(){SecurityContextHolder.clearContext();}

    @Test @SuppressWarnings("unchecked") void officialEvidenceIsVerifiedBeforeCommandAndPersistedWithItsReceipt(){
        authenticate();
        when(idempotency.executeRetained(anyString(),anyString(),anyString(),eq(Map.class),any()))
                .thenAnswer(call->((Supplier<Map<String,Object>>)call.getArgument(4)).get());
        when(storage.exists(KEY)).thenReturn(true);
        var request=values("expectedRevision",5,"reason","Keep original manual review with uploaded evidence","evidenceRefs",List.of(ASSET),"decision","KEEP_MANUAL_REVIEW");
        var result=admin.command("resolveReward","PO-1","evidence-command-1",request,()->{
            verify(storage).exists(KEY);
            new PromotionContractValidator(new ObjectMapper()).validate("ResolveInput",request);
            return PromotionAdminService.resource("REWARD","PO-1",5L);
        });
        assertEquals("SUCCEEDED",result.get("status"));
        assertEquals(5L,map(result.get("resource")).get("revision"));
    }

    @Test @SuppressWarnings("unchecked") void missingEvidenceCannotReachBusinessMutation(){
        authenticate();
        when(idempotency.executeRetained(anyString(),anyString(),anyString(),eq(Map.class),any()))
                .thenAnswer(call->((Supplier<Map<String,Object>>)call.getArgument(4)).get());
        var failure=assertThrows(BizException.class,()->admin.command("resolveReward","PO-1","missing-evidence-1",
                values("reason","Missing evidence must not mutate reward","evidenceRefs",List.of(ASSET)),
                ()->{throw new AssertionError("Invalid evidence reached business mutation");}));
        assertEquals(422,failure.getCode());
        assertEquals("PROMOTION_FIELD_INVALID:$.evidenceRefs[0]",failure.getMessage());
        verifyNoInteractions(db);
    }

    @Test void originalReceiptReplayDoesNotDependOnEvidenceStorageAvailability(){
        authenticate();
        var original=values("status","SUCCEEDED","commandId","PC-1");
        when(idempotency.executeRetained(anyString(),anyString(),anyString(),eq(Map.class),any())).thenReturn(original);
        assertSame(original,admin.command("resolveReward","PO-1","replay-evidence-1",
                values("reason","Replay original successful review","evidenceRefs",List.of(ASSET)),
                ()->{throw new AssertionError("Replay executed business mutation");}));
        verifyNoInteractions(storage,db);
    }

    @Test void rewardDetailReturnsStoredAttemptEvidenceAndLeavesOtherAttemptsEmpty(){
        String time="2026-10-23T01:00:00Z";
        var localized=values("zh","原奖励证据","en","Original reward evidence","vi","Bằng chứng phần thưởng gốc");
        var policyRef=values("policyId","PP-1","version",1,"contentHash","a".repeat(64));
        var spec=values("rewardRuleId","reward-1","beneficiaryRole","BUYER","type","USDT","calculation","FIXED","amount","0.000001","assetPolicy",policyRef);
        var snapshot=values("award",values("reward",spec),"contract",values("title",localized,"terms",localized),
                "policies",values("PP-1:1",values("content",values("availabilityDescription",localized)),
                        "$refund",values("content",values("terms",localized))));
        var reward=values("obligation_id","PO-1","snapshot_json",json(snapshot),"activity_id","PROMO-1","version",1,
                "order_no","ORD-1","order_line_id","1","rule_id","rule-1","reward_rule_id","reward-1","unit_seq",0,
                "beneficiary_role","BUYER","beneficiary_id",7,"status","MANUAL_REVIEW","revision",5,"snapshot_hash",hash(snapshot),
                "original_ledger_no","PROMOTION-ISSUE-PO-1","original_earnings_entry_no","ER-1","last_command_id","PC-resolve","updated_at",time);
        when(db.requiredRow(anyString(),any(Object[].class))).thenAnswer(call->call.getArgument(0,String.class).contains("nx_wallet_ledger")?values("created_at",time):reward);
        var review=values("attempt_id","PA-resolve","command_id","PC-resolve","status","MANUAL_REVIEW","started_at",time,"finished_at",time,
                "evidence_json",json(values("decision","KEEP_MANUAL_REVIEW","evidenceRefs",List.of(ASSET))));
        var issue=values("attempt_id","PA-issue","command_id","PC-issue","status","ISSUED","started_at",time,"finished_at",time,
                "evidence_json",json(values("ledgerBizNo","PROMOTION-ISSUE-PO-1","earningsEntryNo","ER-1")));
        when(db.list(startsWith("SELECT * FROM nx_promotion_reward_attempt"),eq("PO-1"))).thenReturn(List.of(review,issue));
        var result=new PromotionRewardService(db,null,null,null,null,null).get(null,"PO-1",true);
        var attempts=maps(result.get("attempts"));
        assertEquals(List.of(ASSET),attempts.get(0).get("evidenceRefs"));
        assertEquals(List.of(),attempts.get(1).get("evidenceRefs"));
        new PromotionContractValidator(new ObjectMapper()).validate("RewardAdmin",result);
    }

    private static void authenticate(){
        var auth=new UsernamePasswordAuthenticationToken("7","n/a",List.of(new SimpleGrantedAuthority("growth_promotion_reward_resolve")));
        auth.setDetails(Map.of("subjectType","ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
