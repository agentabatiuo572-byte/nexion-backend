package ffdd.opsconsole.promotion;

import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.promotion.application.PromotionPublicService;
import ffdd.opsconsole.promotion.application.PromotionEvaluationService;
import ffdd.opsconsole.promotion.domain.PromotionRules;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PromotionPublicServiceTest {
    @ParameterizedTest
    @CsvSource({"0,ESTIMATE_ELIGIBLE","1,INELIGIBLE"})
    void refundedPrincipalDoesNotAdvertiseRestoredEligibilityUntilRewardsAreRecovered(long consumed,String expected){
        var mapper=mock(PromotionMapper.class);var evaluator=mock(PromotionEvaluationService.class);Instant now=Instant.now();
        var audience=values("purchaseHistory","ANY","rankIds",List.of(),"markets",List.of(),"registrationAge",values(),"lastValidPurchaseAge",values());
        var contract=values("startsAt",now.minusSeconds(60).toString(),"endsAt",now.plusSeconds(60).toString(),"rules",List.of(),"template","FIRST_PURCHASE","buyerAudience",audience);
        var policies=values("$firstPurchase",values("content",values("restoreAfterRefund",true)));
        when(mapper.requiredRow(anyString(),any())).thenReturn(values("activity_id","test","active_version",1,"status","ACTIVE","contract_json",json(contract),"resolved_policies_json",json(policies)));
        when(evaluator.account(17L,audience,policies,null)).thenReturn(new PromotionRules.Account(17,now.minusSeconds(3600),"V0","",null,true,1,0,null,0,consumed));
        assertEquals(expected,new PromotionPublicService(mapper,evaluator,null).get(17L,"test").get("eligibility"));
    }
    @ParameterizedTest
    @CsvSource({"ENDED,-3600,3600,ENDED", "PAUSED,-3600,3600,PAUSED",
            "PAUSED,-3600,-1,ENDED", "PAUSED,-3600,0,ENDED",
            "ACTIVE,-7200,-3600,ENDED", "SCHEDULED,3600,7200,SCHEDULED",
            "SCHEDULED,-3600,3600,ACTIVE"})
    void publicDetailPreservesExplicitStopAndUsesTimeOnlyForScheduledLifecycle(
            String persisted, long startOffset, long endOffset, String expected) {
        var mapper = mock(PromotionMapper.class);
        Instant now = Instant.now();
        var contract = values("startsAt", now.plusSeconds(startOffset).toString(),
                "endsAt", now.plusSeconds(endOffset).toString(), "rules", List.of());
        when(mapper.requiredRow(anyString(), any())).thenReturn(values("activity_id", "test",
                "active_version", 1, "status", persisted, "contract_json", json(contract)));
        var service = new PromotionPublicService(mapper, null, null);
        assertEquals(expected, service.get(null, "test").get("state"));
    }
}
