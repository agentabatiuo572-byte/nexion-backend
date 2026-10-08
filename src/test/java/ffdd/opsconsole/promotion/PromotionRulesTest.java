package ffdd.opsconsole.promotion;

import ffdd.opsconsole.promotion.domain.PromotionRules;
import ffdd.opsconsole.promotion.domain.PromotionRules.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

class PromotionRulesTest {
    private final Instant now=Instant.parse("2026-10-07T12:00:00Z");
    private Account buyer(long ever,long valid,Long sponsor){return new Account(1,now.minus(Duration.ofDays(20)),"V0","JP",sponsor,true,ever,valid,valid>0?now.minus(Duration.ofDays(3)):null,0);}
    private Map<String,Object> audience(){return values("registrationAge",values("minDays",null,"maxDays",null),"purchaseHistory","ANY","devicePresence","ANY","deviceAudiencePolicy",null,"rankIds",List.of(),"lastValidPurchaseAge",values("minDays",null,"maxDays",null),"neverPurchased",false,"markets",List.of(),"sponsor","ANY");}
    private Map<String,Object> unbounded(){return values("mode","UNLIMITED");}
    private Map<String,Object> reward(String id,String role,String type){return values("rewardRuleId",id,"beneficiaryRole",role,"type",type,"calculation","FIXED","amount","1.250000");}
    private Map<String,Object> contract(String template){
        Map<String,Object> rule=values("ruleId","R1","productNo","P1","priority",1,"minBuyQty",2,"repeatMode","PER_GROUP","maxGroups",unbounded(),"maxGroupsPerPerson",unbounded(),"buyerReward",reward("B1","BUYER","USDT"),"inviterReward",null);
        return values("template",template,"buyerAudience",audience(),"inviterAudience","DIRECT_REFERRAL".equals(template)?audience():null,
            "combinationMatch","ANY","rules",List.of(rule),"perPersonLimit",values("buyer",unbounded(),"directInviter",unbounded()),"maxRewardUnitsPerOrder",unbounded());
    }
    private List<Award> evaluate(Map<String,Object> c,List<Selection> items,Account buyer,Account inviter,Map<String,Object> first){
        return PromotionRules.evaluate(c,items,buyer,inviter,first,now,Map.of(),Map.of());
    }
    @Test void groupsAreIntegerFloorWithStableUnitSequence(){
        List<Award> got=evaluate(contract("SKU_GIFT"),List.of(new Selection("P1",7)),buyer(0,0,null),null,null);
        assertEquals(List.of(0,1,2),got.stream().map(Award::unitSeq).toList());
        assertTrue(got.stream().allMatch(a->a.rewardRuleId().equals("B1")));
    }
    @Test void oncePerOrderDoesNotMultiplyByQuantity(){
        Map<String,Object> c=contract("SKU_GIFT"),r=map(maps(c.get("rules")).get(0));r.put("repeatMode","ONCE_PER_ORDER");c.put("rules",List.of(r));
        assertEquals(1,evaluate(c,List.of(new Selection("P1",100)),buyer(0,0,null),null,null).size());
    }
    @Test void firstPurchaseRefundRestorationIsExplicit(){
        Map<String,Object> c=contract("FIRST_PURCHASE");
        assertTrue(evaluate(c,List.of(new Selection("P1",4)),buyer(1,0,null),null,values("mode","FIRST_ELIGIBLE_ORDER","restoreAfterRefund",false)).isEmpty());
        assertEquals(2,evaluate(c,List.of(new Selection("P1",4)),buyer(1,0,null),null,values("mode","FIRST_ELIGIBLE_ORDER","restoreAfterRefund",true)).size());
        assertTrue(evaluate(c,List.of(new Selection("P1",4)),buyer(0,0,null),null,null).isEmpty());
    }
    @Test void firstEligibleUnitUsesRulePriorityNotInputSequence(){
        Map<String,Object> c=contract("FIRST_PURCHASE"),r=map(maps(c.get("rules")).get(0)),second=copy(r);
        second.put("ruleId","R2");second.put("productNo","P2");second.put("priority",0);second.put("buyerReward",reward("B2","BUYER","NEX"));c.put("rules",List.of(r,second));
        var got=evaluate(c,List.of(new Selection("P1",4),new Selection("P2",6)),buyer(0,0,null),null,values("mode","FIRST_ELIGIBLE_UNIT","restoreAfterRefund",false));
        assertEquals(1,got.size());assertEquals("P2",got.get(0).productNo());
    }
    @Test void repurchaseRequiresActualValidPaidOrder(){
        assertTrue(evaluate(contract("REPURCHASE"),List.of(new Selection("P1",2)),buyer(1,0,null),null,null).isEmpty());
        assertEquals(1,evaluate(contract("REPURCHASE"),List.of(new Selection("P1",2)),buyer(1,1,null),null,null).size());
    }
    @Test void allCombinationRequiresEveryThreshold(){
        Map<String,Object> c=contract("MULTI_PRODUCT"),r=copy(maps(c.get("rules")).get(0));r.put("ruleId","R2");r.put("productNo","P2");r.put("buyerReward",reward("B2","BUYER","NEX"));
        c.put("rules",List.of(maps(c.get("rules")).get(0),r));c.put("combinationMatch","ALL");
        assertTrue(evaluate(c,List.of(new Selection("P1",4),new Selection("P2",1)),buyer(0,0,null),null,null).isEmpty());
        assertEquals(3,evaluate(c,List.of(new Selection("P1",4),new Selection("P2",2)),buyer(0,0,null),null,null).size());
    }
    @Test void referralOnlyUsesActualDirectSponsorAndSeparatesBeneficiaries(){
        Map<String,Object> c=contract("DIRECT_REFERRAL"),r=copy(maps(c.get("rules")).get(0));r.put("inviterReward",reward("I1","DIRECT_INVITER","NEX"));c.put("rules",List.of(r));
        Account sponsor=new Account(2,now.minus(Duration.ofDays(40)),"V0","JP",null,true,1,1,now,0);
        var first=values("mode","FIRST_ELIGIBLE_ORDER","restoreAfterRefund",false);
        assertTrue(evaluate(c,List.of(new Selection("P1",2)),buyer(0,0,3L),sponsor,first).isEmpty());
        var got=evaluate(c,List.of(new Selection("P1",2)),buyer(0,0,2L),sponsor,first);
        assertEquals(List.of(1L,2L),got.stream().map(Award::beneficiaryId).toList());
    }
    @Test void persistedCrossVersionUsagePreventsQuotaReset(){
        Map<String,Object> c=contract("SKU_GIFT"),r=copy(maps(c.get("rules")).get(0));r.put("maxGroupsPerPerson",values("mode","LIMITED","value",3));c.put("rules",List.of(r));
        var got=PromotionRules.evaluate(c,List.of(new Selection("P1",8)),buyer(0,0,null),null,null,now,Map.of("1:BUYER:R1",2L),Map.of());
        assertEquals(1,got.size());
    }
    @Test void inputRejectsDuplicatesAndTotalOverflow(){
        assertThrows(BizException.class,()->PromotionRules.selections(List.of(new Selection("P1",1),new Selection("P1",1))));
        assertThrows(BizException.class,()->PromotionRules.selections(List.of(new Selection("P1",60),new Selection("P2",41))));
        assertThrows(BizException.class,()->PromotionRules.selections(List.of(new Selection("P1",0))));
    }
    @Test void unknownMarketDoesNotPretendMatch(){
        var a=audience();a.put("markets",List.of("US"));assertFalse(PromotionRules.eligible(a,buyer(0,0,null),now,false));
        a.put("markets",List.of("JP"));assertTrue(PromotionRules.eligible(a,buyer(0,0,null),now,false));
    }
}
