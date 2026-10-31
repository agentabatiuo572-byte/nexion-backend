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
    @Test void everyExistingOrderLifecycleLocksBudgetBeforePurchasedAndGiftSkus(){
        when(db.order("O1",false)).thenReturn(values("user_id",17L));
        when(db.reservations("O1",false)).thenReturn(List.of(values("beneficiary_id",3L,"activity_id","A1")));
        when(db.list(startsWith("SELECT p.product_no FROM nx_product"),any(Object[].class)))
            .thenReturn(List.of(values("product_no","P2"),values("product_no","P1")));
        service.lockOrderParticipants("O1");
        var sequence=inOrder(db);sequence.verify(db).user(3,true);sequence.verify(db).user(17,true);
        sequence.verify(db).activity("A1",true);
        sequence.verify(db).list(eq("SELECT * FROM nx_promotion_budget WHERE activity_id=? ORDER BY asset,product_no FOR UPDATE"),eq("A1"));
        sequence.verify(db).one("SELECT id FROM nx_product WHERE product_no=? FOR UPDATE","P1");
        sequence.verify(db).one("SELECT id FROM nx_admin_device_sku WHERE sku_id=? FOR UPDATE","P1");
        sequence.verify(db).one("SELECT id FROM nx_product WHERE product_no=? FOR UPDATE","P2");
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs={6,7})
    void giftBeneficiaryCapacityIsRecheckedBeforeBuyerPayment(long occupied){
        when(db.one("SELECT pay_by FROM nx_promotion_order_receipt WHERE order_no=? FOR UPDATE","O1")).thenReturn(null);
        var row=values("beneficiary_id",3L,"asset","DEVICE","status","RESERVED","pay_by",Instant.now().plusSeconds(300),
                "activity_id","A1","snapshot_json",json(values("contract",Map.of(),"policies",Map.of(),"awards",List.of())));
        when(db.order("O1",false)).thenReturn(values("user_id",17L));
        when(db.reservations("O1",true)).thenReturn(List.of(row));
        when(db.reservations("O1",false)).thenReturn(List.of(row));
        when(db.user(anyLong(),eq(true))).thenReturn(values("status","ACTIVE"));
        when(db.deviceSlotCap()).thenReturn(6L);when(db.occupiedDeviceSlots(3L)).thenReturn(occupied);
        when(evaluator.firstPurchaseStillValid(anyMap(),anyMap(),eq(17L),anyList(),eq("O1"))).thenReturn(true);
        if(occupied==6)assertDoesNotThrow(()->service.beforePay(17L,"O1"));
        else assertEquals("PROMOTION_DEVICE_CAPACITY_UNAVAILABLE",assertThrows(BizException.class,()->service.beforePay(17L,"O1")).getMessage());
        verify(db).occupiedDeviceSlots(3L);
        verify(db,never()).write(anyString(),any(Object[].class));
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
    @Test void e4PaymentFailureReleasesUnpaidReservations(){
        // E4 terminal writes order_status=PAYMENT_FAILED for payment_failed.
        var reservation=values("reservation_id","R1","activity_id","A1","beneficiary_id",17L,"beneficiary_role","BUYER","rule_id","RULE1","asset","DISCOUNT_USDT","product_no","","amount",new BigDecimal("500"),"status","RESERVED");
        when(db.order("O1",false)).thenReturn(values("paid_at",null,"order_status","PAYMENT_FAILED"));when(db.reservations("O1",true)).thenReturn(List.of(reservation));
        when(db.write(startsWith("UPDATE nx_promotion_reservation SET status='RELEASED'"),any(Object[].class))).thenAnswer(i->{reservation.put("status","RELEASED");return 1;});
        service.releaseUnpaid("O1","FAILED");
        verify(db,times(1)).write(startsWith("UPDATE nx_promotion_budget SET reserved=reserved-?,revision"),any(Object[].class));
    }
    @Test void paidOrderCannotReleaseBudgetThroughCancelPath(){
        when(db.order("O1",false)).thenReturn(values("paid_at",Instant.now(),"order_status","CANCELLED"));
        assertThrows(BizException.class,()->service.releaseUnpaid("O1","cancel"));verifyNoInteractions(outbox);
    }
    @Test void discountPaymentCommitsBudgetOnceAndNeverCreatesWalletReward(){
        var reservation=values("reservation_id","R1","activity_id","A1","version",1,"order_no","O1","order_line_id",101,
                "beneficiary_id",17L,"beneficiary_role","BUYER","rule_id","RULE1","reward_rule_id","D1","unit_seq",0,
                "asset","DISCOUNT_USDT","product_no","","amount",new BigDecimal("500"),"status","RESERVED","pay_by",Instant.now().plusSeconds(600));
        when(db.order("O1",false)).thenReturn(values("user_id",17L,"payment_status","PAID","paid_at",Instant.now()));
        when(db.reservations("O1",true)).thenReturn(List.of(reservation));
        when(db.write(startsWith("UPDATE nx_promotion_reservation SET status='COMMITTED'"),any(Object[].class))).thenAnswer(i->{reservation.put("status","COMMITTED");return 1;});
        service.afterPaid(17L,"O1");service.afterPaid(17L,"O1");
        verify(db,times(1)).write(startsWith("UPDATE nx_promotion_budget SET reserved=reserved-?,committed=committed+?"),eq(new BigDecimal("500")),eq(new BigDecimal("500")),eq("A1"),eq("DISCOUNT_USDT"),eq(""),eq(new BigDecimal("500")));
        verify(db,never()).write(startsWith("INSERT INTO nx_promotion_reward"),any(Object[].class));verifyNoInteractions(outbox);
    }
    @Test void discountReceiptFreezesNetLinePriceAndReservesBudgetWithoutGiftStock(){
        Instant deadline=Instant.now().plusSeconds(600);
        when(db.order("O1",false)).thenReturn(values("id",5L,"user_id",17L,"payment_status","PENDING","amount_usdt",new BigDecimal("1000")));
        when(db.list(startsWith("SELECT * FROM nx_order_item"),any(Object[].class))).thenReturn(List.of(values("id",101L,"product_no","PRO","product_name","Pro","quantity",1,"unit_price_usdt",new BigDecimal("1500"))));
        when(db.reservations("O1",true)).thenReturn(List.of());
        var award=values("productNo","PRO","beneficiaryId",17L,"beneficiaryRole","BUYER","ruleId","R1","rewardRuleId","D1","unitSeq",0,"reward",PromotionOrderDiscountTest.spec("500"));
        var expected=PromotionQuoteService.expected(List.of(award),"A1",1L);
        var localized=values("zh","下单立减","en","Order discount","vi","Giảm giá đơn");
        expected.get(0).put("disclosure",values("title",localized,"terms",localized,"refundTerms",localized,"benefitDescription",localized,"deviceName",null,"deviceRights",null));
        var price=PromotionQuoteService.applyDiscounts(PromotionOrderDiscountTest.price(List.of(PromotionOrderDiscountTest.line("PRO","1500.000000","0.000000"))),List.of(award));
        price.putAll(values("activityId","A1","activityVersion",1,"expectedRewards",expected));
        var snapshot=values("public",price,"request",values("items",List.of(values("productNo","PRO","quantity",1))),"awards",List.of(award));
        var plan=new PromotionOrderService.CreationPlan("Q1",17L,List.of(17L),List.of("PRO"),deadline,snapshot);
        var receipt=service.reserveCreatedOrder(plan,"O1",deadline);
        receipt.putAll(values("orderNo","O1","paymentStatus","PENDING","orderStatus","PENDING_PAYMENT"));
        new PromotionContractValidator(new com.fasterxml.jackson.databind.ObjectMapper()).validate("OrderReceipt",receipt);
        assertEquals("500.000000",receipt.get("promotionDiscountUsdt"));
        assertEquals("1000.000000",maps(receipt.get("items")).get(0).get("payableUsdt"));
        assertEquals("101",maps(receipt.get("rewards")).get(0).get("lineId"));
        verify(db).write(startsWith("UPDATE nx_promotion_budget SET reserved=reserved+?"),eq(new BigDecimal("500.000000")),eq("A1"),eq("DISCOUNT_USDT"),eq(""),eq(new BigDecimal("500.000000")));
        verify(db,never()).write(startsWith("UPDATE nx_product"),any(Object[].class));
        verify(db,never()).write(startsWith("INSERT INTO nx_promotion_reward"),any(Object[].class));
    }
    @Test void discountCancelReleasesOnlyItsReservationAndCanBeRetried(){
        var reservation=values("reservation_id","R1","activity_id","A1","beneficiary_id",17L,"beneficiary_role","BUYER","rule_id","RULE1","asset","DISCOUNT_USDT","product_no","","amount",new BigDecimal("500"),"status","RESERVED");
        when(db.order("O1",false)).thenReturn(values("paid_at",null,"order_status","CANCELLED"));when(db.reservations("O1",true)).thenReturn(List.of(reservation));
        when(db.write(startsWith("UPDATE nx_promotion_reservation SET status='RELEASED'"),any(Object[].class))).thenAnswer(i->{reservation.put("status","RELEASED");return 1;});
        service.releaseUnpaid("O1","CANCELLED");service.releaseUnpaid("O1","CANCELLED");
        verify(db,times(1)).write(startsWith("UPDATE nx_promotion_budget SET reserved=reserved-?,revision"),any(Object[].class));
        verify(db,never()).write(startsWith("UPDATE nx_product"),any(Object[].class));verify(db,never()).write(startsWith("INSERT INTO nx_promotion_reward"),any(Object[].class));
    }
    @Test void refundRequiresRealNetLedgerAndRestoresDiscountBudgetOnce(){
        var reservation=values("reservation_id","R1","activity_id","A1","asset","DISCOUNT_USDT","product_no","","amount",new BigDecimal("500"),"status","COMMITTED","snapshot_json",json(values("policies",Map.of())));
        var order=values("user_id",17L,"amount_usdt",new BigDecimal("1000"),"payment_status","REFUNDED","order_status","REFUNDED");
        when(db.order("O1",false)).thenReturn(order);when(db.order("O1",true)).thenReturn(order);when(db.reservations("O1",true)).thenReturn(List.of(reservation));
        when(db.requiredRow(startsWith("SELECT * FROM nx_wallet_ledger"),any(Object[].class))).thenReturn(values("amount",new BigDecimal("1500")));
        assertEquals("PROMOTION_REFUND_LEDGER_MISMATCH",assertThrows(BizException.class,()->service.confirmE4Refund("O1","REFUND-O1")).getMessage());
        verify(db,never()).write(startsWith("UPDATE nx_promotion_budget"),any(Object[].class));
        when(db.requiredRow(startsWith("SELECT * FROM nx_wallet_ledger"),any(Object[].class))).thenReturn(values("amount",new BigDecimal("1000")));
        when(db.list(contains("nx_promotion_refund_hold"),any(Object[].class))).thenReturn(List.of(values("status","EXECUTED")));
        when(db.write(startsWith("UPDATE nx_promotion_reservation SET status='REVERSED'"),any(Object[].class))).thenAnswer(i->{reservation.put("status","REVERSED");return 1;});
        service.confirmE4Refund("O1","REFUND-O1");service.confirmE4Refund("O1","REFUND-O1");
        verify(db,times(1)).write(startsWith("UPDATE nx_promotion_budget SET committed=committed-?"),eq(new BigDecimal("500")),eq("A1"),eq(new BigDecimal("500")));
        verify(db,never()).write(contains("nx_wallet_ledger"),any(Object[].class));verify(db,never()).write(startsWith("UPDATE nx_product"),any(Object[].class));
    }
}
