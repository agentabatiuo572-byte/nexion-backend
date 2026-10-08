package ffdd.opsconsole.promotion;

import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

class PromotionOrderServiceTest {
    PromotionMapper db=mock(PromotionMapper.class);
    PromotionQuoteService quotes=mock(PromotionQuoteService.class);
    PromotionEvaluationService evaluator=mock(PromotionEvaluationService.class);
    PromotionPolicyResolver policies=mock(PromotionPolicyResolver.class);
    AuditLogService audit=mock(AuditLogService.class);
    EventOutboxService outbox=mock(EventOutboxService.class);
    PromotionOrderService service=new PromotionOrderService(db,quotes,evaluator,policies,audit,outbox);
    @BeforeEach void writesSucceed(){when(db.write(anyString(),any(Object[].class))).thenReturn(1);}
    @Test void oldOrdersStillLockTheRealBuyerBeforeOrderLock(){
        when(db.order("O1",false)).thenReturn(values("user_id",17L));
        when(db.reservations("O1",false)).thenReturn(List.of());
        service.lockOrderParticipants("O1");
        verify(db).user(17,true);verify(db,never()).order("O1",true);
    }
    @Test void participantsLockInAscendingAccountOrder(){
        when(db.order("O1",false)).thenReturn(values("user_id",17L));
        when(db.reservations("O1",false)).thenReturn(List.of(values("beneficiary_id",3),values("beneficiary_id",17)));
        service.lockOrderParticipants("O1");
        var order=inOrder(db);order.verify(db).user(3,true);order.verify(db).user(17,true);
    }
    @Test void unknownOrderCannotSkipAccountLocks(){
        when(db.order("bad",false)).thenThrow(new BizException(404,"PROMOTION_RESOURCE_NOT_FOUND"));
        assertThrows(BizException.class,()->service.lockOrderParticipants("bad"));verify(db,never()).user(anyLong(),anyBoolean());
    }
    @Test void expiredReceiptRejectsEvenWhenNoRewardsWereReserved(){
        when(db.order("O1",false)).thenReturn(values("user_id",17L));
        when(db.one("SELECT pay_by FROM nx_promotion_order_receipt WHERE order_no=? FOR UPDATE","O1")).thenReturn(values("pay_by",Instant.EPOCH));
        assertEquals("PROMOTION_PAYMENT_WINDOW_CLOSED",assertThrows(BizException.class,()->service.beforePay(17L,"O1")).getMessage());
        verify(db,never()).reservations(anyString(),anyBoolean());
    }
    @Test void originalOrdersWithoutReceiptsRemainPayable(){
        when(db.order("O1",false)).thenReturn(values("user_id",17L));
        when(db.one("SELECT pay_by FROM nx_promotion_order_receipt WHERE order_no=? FOR UPDATE","O1")).thenReturn(null);
        when(db.reservations("O1",true)).thenReturn(List.of());
        assertDoesNotThrow(()->service.beforePay(17L,"O1"));
    }
    @Test void wrongOwnerCannotLockAnotherOrdersReceipt(){
        when(db.order("O1",false)).thenReturn(values("user_id",17L));
        assertEquals("PROMOTION_ORDER_OWNER_MISMATCH",assertThrows(BizException.class,()->service.beforePay(18L,"O1")).getMessage());
        verify(db,never()).one(anyString(),any(Object[].class));
    }
    @Test void zeroActivityQuoteKeepsOriginalDeadlineAndDurableReceipt(){
        Instant deadline=Instant.now().plusSeconds(1800);
        when(db.order("O1",false)).thenReturn(values("id",5L,"user_id",17L,"payment_status","PENDING","amount_usdt",new BigDecimal("12")));
        when(db.list(startsWith("SELECT * FROM nx_order_item"),any(Object[].class))).thenReturn(List.of(values("id",101L,"product_no","P1","quantity",3,"unit_price_usdt",new BigDecimal("4"))));
        Map<String,Object> pub=values("activityId",null,"items",List.of(values("productNo","P1","unitPriceUsdt","4.000000")),"amountUsdt","12.000000","expectedRewards",List.of());
        Map<String,Object> snapshot=values("public",pub,"request",values("items",List.of(values("productNo","P1","quantity",3))),"awards",List.of());
        var plan=new PromotionOrderService.CreationPlan("Q1",17L,List.of(17L),List.of("P1"),Instant.MAX,snapshot);
        var result=service.reserveCreatedOrder(plan,"O1",deadline);
        assertEquals(deadline.toString(),result.get("payBy"));assertEquals(1,result.get("itemCount"));assertEquals(3,result.get("quantity"));
        assertEquals(List.of(values("lineId","101","productNo","P1","productName","","quantity",3)),result.get("items"));
        verify(db).write(startsWith("INSERT INTO nx_promotion_order_receipt"),any(Object[].class));
        verify(db,never()).write(startsWith("INSERT INTO nx_promotion_reservation"),any(Object[].class));
    }
    @Test void eachRewardGroupGetsOneActualOrderLineAndIndependentUniqueUnit(){
        Instant deadline=Instant.now().plusSeconds(1800);
        when(db.order("O1",false)).thenReturn(values("id",5L,"user_id",17L,"payment_status","PENDING","amount_usdt",new BigDecimal("16")));
        when(db.list(startsWith("SELECT * FROM nx_order_item"),any(Object[].class))).thenReturn(List.of(values("id",101L,"product_no","P1","quantity",4,"unit_price_usdt",new BigDecimal("4"))));
        when(db.reservations("O1",true)).thenReturn(List.of());
        Map<String,Object> reward=values("type","USDT","amount","2.000000");
        List<Map<String,Object>> awards=List.of(values("productNo","P1","beneficiaryId",17,"beneficiaryRole","BUYER","ruleId","R1","rewardRuleId","B1","unitSeq",0,"reward",reward),values("productNo","P1","beneficiaryId",17,"beneficiaryRole","BUYER","ruleId","R1","rewardRuleId","B1","unitSeq",1,"reward",reward));
        Map<String,Object> pub=values("activityId","A1","activityVersion",1,"items",List.of(values("productNo","P1","unitPriceUsdt","4.000000")),"amountUsdt","16.000000","expectedRewards",List.of(values("lineId","P1","groups",2)));
        Map<String,Object> snapshot=values("public",pub,"request",values("items",List.of(values("productNo","P1","quantity",4))),"awards",awards);
        var plan=new PromotionOrderService.CreationPlan("Q1",17L,List.of(17L),List.of("P1"),deadline,snapshot);
        var result=service.reserveCreatedOrder(plan,"O1",deadline);
        assertEquals("101",maps(result.get("rewards")).get(0).get("lineId"));
        assertEquals(List.of(values("lineId","101","productNo","P1","productName","","quantity",4)),result.get("items"));
        ArgumentCaptor<Object[]> args=ArgumentCaptor.forClass(Object[].class);
        verify(db,times(2)).write(startsWith("INSERT INTO nx_promotion_reservation"),args.capture());
        assertEquals(List.of(0L,1L),args.getAllValues().stream().map(a->number(a[10])).toList());
        assertTrue(args.getAllValues().stream().allMatch(a->number(a[4])==101L));
        verify(db,times(2)).write(startsWith("UPDATE nx_promotion_budget"),any(Object[].class));
    }
    @Test void historicalReceiptMapsMultipleProductsWithoutChangingFrozenReceiptOrReadingCatalog(){
        when(db.order("O1",false)).thenReturn(values("user_id",17L,"amount_usdt",new BigDecimal("20")));
        var frozen=values("promotionQuoteId","Q1","rewards",List.of(values("lineId","101","disclosure",values("title","Original A","deviceName",null)),values("lineId","102","disclosure",values("title","Original D","deviceName",null))));
        when(db.one(startsWith("SELECT projection_json,buyer_id"),any(Object[].class))).thenReturn(values("buyer_id",17L,"projection_json",json(frozen)));
        when(db.list(startsWith("SELECT * FROM nx_order_item"),any(Object[].class))).thenReturn(List.of(values("id",101L,"product_no","A","quantity",2),values("id",102L,"product_no","D","quantity",1)));
        var result=service.orderProjection(17L,"O1");
        assertEquals(List.of(values("lineId","101","productNo","A","productName","","quantity",2),values("lineId","102","productNo","D","productName","","quantity",1)),result.get("items"));
        assertEquals(frozen.get("rewards"),result.get("rewards"));
        for(var reward:maps(result.get("rewards")))assertTrue(maps(result.get("items")).stream().anyMatch(item->item.get("lineId").equals(reward.get("lineId"))));
        assertEquals(result,service.orderProjection(17L,"O1"));
        verify(db,never()).product(anyString(),anyBoolean());verify(db,never()).write(anyString(),any(Object[].class));
        verifyNoInteractions(policies);
        assertThrows(BizException.class,()->service.orderProjection(18L,"O1"));
    }
    @Test void newReceiptRetainsFrozenItemsOnEveryRead(){
        when(db.order("O1",false)).thenReturn(values("user_id",17L,"amount_usdt",new BigDecimal("20")));
        var frozen=values("items",List.of(values("lineId","101","productNo","A","productName","Original A","quantity",2)),"rewards",List.of());
        when(db.one(startsWith("SELECT projection_json,buyer_id"),any(Object[].class))).thenReturn(values("buyer_id",17L,"projection_json",json(frozen)));
        assertEquals(frozen.get("items"),service.orderProjection(17L,"O1").get("items"));
        verify(db,never()).list(startsWith("SELECT * FROM nx_order_item"),any(Object[].class));
    }
    @Test void expiredA2DoesNotClearPotentiallyExecutedHold(){
        when(db.requiredRow(startsWith("SELECT * FROM nx_audit_operation_ticket"),any(Object[].class))).thenReturn(values("status","expired","command_json",json(values("op","e4_order_refund","params",values("orderNo","O1")))));
        assertThrows(BizException.class,()->service.clearA2Refund("A2-1","O1"));
        verify(db,never()).write(contains("refund_hold"),any(Object[].class));
    }
    @Test void paidOrderCannotReleaseBudgetThroughCancelPath(){
        when(db.order("O1",false)).thenReturn(values("paid_at",Instant.now(),"order_status","CANCELLED"));
        assertThrows(BizException.class,()->service.releaseUnpaid("O1","cancel"));verifyNoInteractions(outbox);
    }
}
