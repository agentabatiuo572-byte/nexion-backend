package ffdd.opsconsole.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.finance.application.*;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher;
import ffdd.opsconsole.growth.mapper.AppGrowthLifecycleMapper;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.platform.infrastructure.MybatisPlatformConfigRepository;
import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.canonical.mapper.AppBundleOrderMapper;
import ffdd.opsconsole.treasury.application.TreasuryLedgerPostingFacadeAdapter;
import ffdd.opsconsole.treasury.infrastructure.MybatisTreasuryLedgerRepository;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real policy approval and promotion transactions. Canonical order/payment source facts are
 * fixture transactions here; the separate HTTP acceptance proves the actual commerce entrypoints.
 */
@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_RUNTIME",matches="1")
class PromotionLifecycleMySqlTest {
    PromotionRuntimeHarness h;
    PromotionQuoteService quotes;
    PromotionOrderService orders;
    PromotionRewardService rewards;
    TreasuryLedgerPostingFacadeAdapter ledger;
    TransactionTemplate tx;
    AppTradeinMapper tradein;
    CanonicalStateMapper canonical;
    AppBundleOrderMapper bundle;
    String buy,gift;
    long nextUser=880000000000L+Math.floorMod(UUID.randomUUID().getMostSignificantBits(),10000000000L);
    final List<Map<String,Object>> evidence=new ArrayList<>();

    @Test void concurrencyAndVersionBoundariesHaveRealMySqlEvidence() throws Exception {
        var checks=new ArrayList<Map<String,Object>>();
        issueAndRefundSerializeBothTransactionOrdersForEveryAsset(checks);
        twoAccountsCompeteForOneGiftWithActivityAndBudgetStillAvailable(checks);
        reservedVersionOnePaysBeforeItsDeadlineAfterVersionTwoPublicationAndPause(checks);
        versionOneQuoteCannotCreateAfterRealVersionTwoPublicationAndWritesNothing(checks);
        assertEquals(13,checks.size());
        String suffix=Objects.toString(System.getenv("GROWTH_HTTP_EVIDENCE_SUFFIX"),"");assertTrue(suffix.matches("[A-Za-z0-9_-]*"));
        Files.writeString(Path.of("D:/CodexData/test-environments/workflow-runs/growth-promotions-20261007/promotion-concurrency-runtime"+suffix+".json"),
            json(values("completed",true,"scope","Isolated MySQL promotion transactions; payment/refund source facts use the existing fixture once","scenarioCount",checks.size(),"checks",checks)));
    }
    void issueAndRefundSerializeBothTransactionOrdersForEveryAsset(List<Map<String,Object>> checks) throws Exception {
        setup();var common=h.commonPolicies();
        for(String asset:List.of("DEVICE","USDT","NEX")){
            String activity=h.publish(h.contract(buy,runtimeReward(asset),common));
            for(boolean issueFirst:List.of(true,false)){
                long buyer=user();var walletBefore=h.db.requiredRow("SELECT usdt_available,nex_available FROM nx_user_wallet WHERE user_id=?",buyer);
                BigDecimal stockBefore=decimal(h.db.product(gift,false).get("stock"));
                String order=create(buyer,quote(buyer,activity,1),1);pay(buyer,order);String obligation=obligations(order).get(0);
                var firstConnection=new CompletableFuture<Long>();var secondConnection=new CompletableFuture<Long>();
                var firstReturned=new CountDownLatch(1);var releaseFirst=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
                Map<String,Object> wait;
                try{
                    var first=executor.submit(()->tx.execute(s->{
                        firstConnection.complete(h.db.count("SELECT CONNECTION_ID()"));
                        String state;
                        if(issueFirst)state=text(rewards.issue(obligation,id("RACEISSUE")).get("state"));
                        else{refund(buyer,order);state=text(rewards.get(buyer,obligation,false).get("state"));}
                        firstReturned.countDown();awaitRelease(releaseFirst);return state;
                    }));
                    assertTrue(firstReturned.await(10,TimeUnit.SECONDS),"First domain action must return before its transaction is committed");
                    var second=executor.submit(()->{
                        try{return tx.execute(s->{
                            secondConnection.complete(h.db.count("SELECT CONNECTION_ID()"));
                            if(issueFirst){refund(buyer,order);return text(rewards.get(buyer,obligation,false).get("state"));}
                            return text(rewards.issue(obligation,id("RACEISSUE")).get("state"));
                        });}catch(ffdd.opsconsole.shared.exception.BizException rejected){
                            assertFalse(issueFirst);assertEquals(409,rejected.getCode());
                            assertEquals("PROMOTION_REWARD_NOT_RETRYABLE",rejected.getMessage());return rejected.getMessage();
                        }
                    });
                    wait=observedLockWait(secondConnection.get(5,TimeUnit.SECONDS),firstConnection.get(5,TimeUnit.SECONDS),"nx_user",String.valueOf(buyer));
                    assertFalse(second.isDone());releaseFirst.countDown();
                    assertEquals(issueFirst?"ISSUED":"CANCELLED",first.get(10,TimeUnit.SECONDS));
                    assertEquals(issueFirst?"REVERSAL_PENDING":"PROMOTION_REWARD_NOT_RETRYABLE",second.get(10,TimeUnit.SECONDS));
                }finally{releaseFirst.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(15,TimeUnit.SECONDS));}
                Object originalReceipt=rewards.get(buyer,obligation,false).get("assetReceipt");
                if(issueFirst){
                    assertNotNull(originalReceipt);
                    assertEquals("REVERSED",rewards.reverseRefund(obligation,id("RACEREVERSE")).get("state"));
                    assertEquals("REVERSED",rewards.reverseRefund(obligation,id("RACEREPLAY")).get("state"));
                    assertEquals(originalReceipt,rewards.get(buyer,obligation,false).get("assetReceipt"));
                    var reversal=h.db.requiredRow("SELECT amount,recovered,outstanding FROM nx_promotion_reversal WHERE obligation_id=?",obligation);
                    assertEquals(0,decimal(reversal.get("amount")).compareTo(decimal(reversal.get("recovered"))));
                    assertEquals(0,decimal(reversal.get("outstanding")).signum());
                }else assertNull(rewards.get(buyer,obligation,false).get("assetReceipt"));
                String finalState=issueFirst?"REVERSED":"CANCELLED";assertEquals(finalState,rewards.get(buyer,obligation,false).get("state"));
                assertEquals("REFUNDED",h.db.order(order,false).get("payment_status"));assertEquals(1,obligations(order).size());
                assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_promotion_order_receipt WHERE order_no=?",order));
                assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE order_no=?",order));
                assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_promotion_refund_hold WHERE order_no=? AND status='EXECUTED'",order));
                assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=? AND biz_type='ORDER_REFUND'", "E4-REFUND-"+order));
                assertEquals(issueFirst?1:0,h.db.count("SELECT COUNT(*) FROM nx_promotion_reward_attempt WHERE obligation_id=? AND action='ISSUE'",obligation));
                assertEquals(issueFirst?1:0,h.db.count("SELECT COUNT(*) FROM nx_promotion_reversal WHERE obligation_id=?",obligation));
                var walletAfter=h.db.requiredRow("SELECT usdt_available,nex_available FROM nx_user_wallet WHERE user_id=?",buyer);
                for(String field:List.of("usdt_available","nex_available"))assertEquals(0,decimal(walletBefore.get(field)).compareTo(decimal(walletAfter.get(field))),field);
                if("DEVICE".equals(asset)){
                    assertEquals(issueFirst?1:0,h.db.count("SELECT COUNT(*) FROM nx_promotion_device_receipt WHERE obligation_id=?",obligation));
                    assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_user_device WHERE user_id=? AND source_order_no=? AND source_channel='PROMOTION_GIFT' AND ownership_status='OWNED'",buyer,order));
                    assertEquals(issueFirst?1:0,h.db.count("SELECT COUNT(*) FROM nx_user_device WHERE user_id=? AND source_order_no=? AND source_channel='PROMOTION_GIFT' AND ownership_status='REVOKED'",buyer,order));
                    assertEquals(0,stockBefore.compareTo(decimal(h.db.product(gift,false).get("stock"))));
                }else{
                    assertEquals(issueFirst?1:0,h.db.count("SELECT COUNT(*) FROM nx_earnings_release_entry WHERE user_id=? AND source_type='PROMOTION_REWARD' AND source_ref=? AND asset=?",buyer,obligation,asset));
                    assertEquals(issueFirst?1:0,h.db.count("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=? AND biz_type='PROMOTION_REWARD' AND asset=? AND direction='IN'",buyer,asset));
                    assertEquals(issueFirst?1:0,h.db.count("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=? AND biz_type='PROMOTION_REWARD_REVERSAL' AND asset=? AND direction='OUT'",buyer,asset));
                    assertEquals(0,decimal(h.db.requiredRow("SELECT COALESCE(SUM(amount-recovered_amount),0) net FROM nx_earnings_release_entry WHERE user_id=? AND source_type='PROMOTION_REWARD' AND source_ref=? AND asset=?",buyer,obligation,asset).get("net")).signum());
                    assertEquals(0,decimal(h.db.requiredRow("SELECT COALESCE(SUM(CASE direction WHEN 'IN' THEN amount ELSE -amount END),0) net FROM nx_wallet_ledger WHERE user_id=? AND asset=? AND biz_type IN ('PROMOTION_REWARD','PROMOTION_REWARD_REVERSAL')",buyer,asset).get("net")).signum());
                }
                budget(activity,asset,"reserved",BigDecimal.ZERO);budget(activity,asset,"committed",BigDecimal.ZERO);budget(activity,asset,"unrecoverable",BigDecimal.ZERO);
                var totals=h.db.requiredRow("SELECT total,reserved,committed,issued,reversed,unrecoverable FROM nx_promotion_budget WHERE activity_id=? AND asset=?",activity,asset);
                assertEquals(0,decimal(totals.get("issued")).compareTo(decimal(totals.get("reversed"))));
                checks.add(values("scenario","issue-refund-lock-order","run",h.run,"asset",asset,"firstAction",issueFirst?"ISSUE":"REFUND","activity",activity,"buyer",buyer,"order",order,"obligation",obligation,"observedLockWait",wait,
                    "orderState",h.db.order(order,false).get("payment_status"),"reward",h.db.requiredRow("SELECT status,version,original_ledger_no,original_earnings_entry_no FROM nx_promotion_reward WHERE obligation_id=?",obligation),
                    "walletBefore",walletBefore,"walletAfter",walletAfter,"orderReceiptCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_order_receipt WHERE order_no=?",order),
                    "obligationCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_reward WHERE order_no=?",order),"refundLedgerCount",h.db.count("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=? AND biz_type='ORDER_REFUND'","E4-REFUND-"+order),
                    "reservationCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE order_no=?",order),"issueAttemptCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_reward_attempt WHERE obligation_id=? AND action='ISSUE'",obligation),
                    "refundHoldCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_refund_hold WHERE order_no=?",order),"refundHoldState",h.db.requiredRow("SELECT status FROM nx_promotion_refund_hold WHERE order_no=?",order).get("status"),
                    "assetReceipt",rewards.get(buyer,obligation,false).get("assetReceipt"),"deviceReceiptCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_device_receipt WHERE obligation_id=?",obligation),
                    "ownedGiftCount",h.db.count("SELECT COUNT(*) FROM nx_user_device WHERE source_order_no=? AND source_channel='PROMOTION_GIFT' AND ownership_status='OWNED'",order),
                    "rewardLedgerCount",h.db.count("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=? AND asset=? AND biz_type='PROMOTION_REWARD'",buyer,asset),
                    "reverseLedgerCount",h.db.count("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=? AND asset=? AND biz_type='PROMOTION_REWARD_REVERSAL'",buyer,asset),
                    "rewardLedgerNet",h.db.requiredRow("SELECT COALESCE(SUM(CASE direction WHEN 'IN' THEN amount ELSE -amount END),0) net FROM nx_wallet_ledger WHERE user_id=? AND asset=? AND biz_type IN ('PROMOTION_REWARD','PROMOTION_REWARD_REVERSAL')",buyer,asset).get("net"),
                    "earningsNet",h.db.requiredRow("SELECT COALESCE(SUM(amount-recovered_amount),0) net FROM nx_earnings_release_entry WHERE user_id=? AND source_type='PROMOTION_REWARD' AND source_ref=? AND asset=?",buyer,obligation,asset).get("net"),
                    "reversal",h.db.one("SELECT status,amount,recovered,outstanding FROM nx_promotion_reversal WHERE obligation_id=?",obligation),"budget",totals));
            }
        }
    }

    void twoAccountsCompeteForOneGiftWithActivityAndBudgetStillAvailable(List<Map<String,Object>> checks) throws Exception {
        setup();changed(h.db.write("UPDATE nx_product SET stock=1,inventory_mode='FINITE' WHERE product_no=?",gift));
        var contract=h.contract(buy,runtimeReward("DEVICE"),h.commonPolicies());String activity=h.publish(contract);
        assertEquals(100,number(map(contract.get("activityLimit")).get("value")));budget(activity,"DEVICE","total",new BigDecimal("100"));
        long winner=user(),loser=user();var winnerQuote=quote(winner,activity,1);var loserQuote=quote(loser,activity,1);
        var loserQuoteBefore=h.db.requiredRow("SELECT * FROM nx_promotion_quote WHERE quote_id=?",loserQuote.get("quoteId"));
        BigDecimal giftStock=decimal(h.db.product(gift,false).get("stock"));assertEquals(0,BigDecimal.ONE.compareTo(giftStock));
        BigDecimal buyStock=decimal(h.db.product(buy,false).get("stock"));
        var firstConnection=new CompletableFuture<Long>();var secondConnection=new CompletableFuture<Long>();
        var firstReturned=new CountDownLatch(1);var releaseFirst=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        String order,rejection;Map<String,Object> wait,loserOutcome;
        try{
            var first=executor.submit(()->tx.execute(s->{
                firstConnection.complete(h.db.count("SELECT CONNECTION_ID()"));String created=create(winner,winnerQuote,1);
                firstReturned.countDown();awaitRelease(releaseFirst);return created;
            }));
            assertTrue(firstReturned.await(10,TimeUnit.SECONDS));
            var second=executor.submit(()->{
                try{return tx.execute(s->{secondConnection.complete(h.db.count("SELECT CONNECTION_ID()"));return values("order",create(loser,loserQuote,1));});}
                catch(ffdd.opsconsole.shared.exception.BizException rejected){assertEquals(409,rejected.getCode());assertEquals("PROMOTION_CAPACITY_UNAVAILABLE",rejected.getMessage());return values("error",rejected.getMessage(),"code",rejected.getCode());}
            });
            // The real create lock order serializes this shared inventory through the activity root first.
            wait=observedLockWait(secondConnection.get(5,TimeUnit.SECONDS),firstConnection.get(5,TimeUnit.SECONDS),"nx_promotion","'"+activity+"'");
            assertFalse(second.isDone());releaseFirst.countDown();order=first.get(10,TimeUnit.SECONDS);
            loserOutcome=second.get(10,TimeUnit.SECONDS);rejection=text(loserOutcome.get("error"));assertEquals("PROMOTION_CAPACITY_UNAVAILABLE",rejection);
        }finally{releaseFirst.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(15,TimeUnit.SECONDS));}
        assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_order WHERE user_id IN (?,?)",winner,loser));
        assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_order WHERE user_id=?",loser));
        assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_order_item WHERE order_no=?",order));
        assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_promotion_order_receipt WHERE buyer_id IN (?,?)",winner,loser));
        assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_order_receipt WHERE quote_id=?",loserQuote.get("quoteId")));
        assertEquals(loserQuoteBefore,h.db.requiredRow("SELECT * FROM nx_promotion_quote WHERE quote_id=?",loserQuote.get("quoteId")));
        assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE activity_id=? AND asset='DEVICE' AND product_no=? AND status='RESERVED'",activity,gift));
        assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE buyer_id=?",loser));
        assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_usage WHERE activity_id=? AND account_id=?",activity,loser));
        assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_reward WHERE activity_id=?",activity));
        assertEquals(1,number(h.db.activity(activity,false).get("reserved_orders")));
        assertEquals(0,number(h.db.product(gift,false).get("stock")));
        assertEquals(0,buyStock.subtract(BigDecimal.ONE).compareTo(decimal(h.db.product(buy,false).get("stock"))));
        budget(activity,"DEVICE","reserved",BigDecimal.ONE);budget(activity,"DEVICE","committed",BigDecimal.ZERO);budget(activity,"DEVICE","issued",BigDecimal.ZERO);
        var remaining=h.db.requiredRow("SELECT total-reserved-committed-issued+reversed available FROM nx_promotion_budget WHERE activity_id=? AND asset='DEVICE' AND product_no=?",activity,gift);
        assertEquals(0,new BigDecimal("99").compareTo(decimal(remaining.get("available"))));
        var conserved=h.db.requiredRow("SELECT COALESCE(SUM(amount),0) reserved FROM nx_promotion_reservation WHERE activity_id=? AND asset='DEVICE' AND product_no=? AND status='RESERVED'",activity,gift);
        assertEquals(0,BigDecimal.ONE.compareTo(decimal(conserved.get("reserved")).add(decimal(h.db.product(gift,false).get("stock")))));
        var root=h.db.activity(activity,false);long capacity=number(map(parse(h.db.version(activity,1,false).get("contract_json")).get("activityLimit")).get("value"));
        long remainingOrders=capacity-number(root.get("reserved_orders"))-number(root.get("used_orders"));assertEquals(99,remainingOrders);
        checks.add(values("scenario","one-gift-two-accounts","run",h.run,"activity",activity,"winner",winner,"loser",loser,"order",order,"giftProduct",gift,
            "giftInitialStock",giftStock,"giftFinalStock",h.db.product(gift,false).get("stock"),"reservedGiftQuantity",conserved.get("reserved"),"activityRemainingOrders",remainingOrders,"deviceBudgetRemaining",remaining.get("available"),
            "orderCount",h.db.count("SELECT COUNT(*) FROM nx_order WHERE user_id IN (?,?)",winner,loser),"receiptCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_order_receipt WHERE buyer_id IN (?,?)",winner,loser),
            "reservationCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE activity_id=?",activity),"loserOrderCount",h.db.count("SELECT COUNT(*) FROM nx_order WHERE user_id=?",loser),
            "loserReservationCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE buyer_id=?",loser),"loserConsumedQuoteCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_order_receipt WHERE quote_id=?",loserQuote.get("quoteId")),
            "observedLockWait",wait,"loserError",rejection,"loserErrorCode",loserOutcome.get("code"),"loserQuoteBefore",loserQuoteBefore,"loserQuoteAfter",h.db.requiredRow("SELECT * FROM nx_promotion_quote WHERE quote_id=?",loserQuote.get("quoteId"))));
    }

    void reservedVersionOnePaysBeforeItsDeadlineAfterVersionTwoPublicationAndPause(List<Map<String,Object>> checks) throws Exception {
        setup();var common=h.commonPolicies();
        for(String asset:List.of("DEVICE","USDT","NEX")){
            var contract=h.contract(buy,runtimeReward(asset),common);String activity=h.publish(contract);long buyer=user();
            var quoted=quote(buyer,activity,1);String order=create(buyer,quoted,1);var projection=orders.orderProjection(buyer,order);
            var reserved=h.db.requiredRow("SELECT * FROM nx_promotion_reservation WHERE order_no=?",order);
            assertEquals(1,number(reserved.get("version")));assertEquals("RESERVED",reserved.get("status"));assertTrue(obligations(order).isEmpty());
            publishVersionTwo(activity,contract);
            h.authenticate(h.publisher);var pause=values("expectedRevision",h.db.activity(activity,false).get("revision"),"reason","新版暂停后验证原预留支付合同","evidenceRefs",List.of(h.evidence));
            h.admin.command("pausePromotion",activity,id("PAUSE"),pause,()->h.admin.state(activity,"pause",pause));
            assertEquals("PAUSED",h.admin.get(activity).get("state"));assertEquals(2,number(h.admin.get(activity).get("activeVersion")));
            assertEquals(1,h.db.count("SELECT NOW(6)<pay_by FROM nx_promotion_order_receipt WHERE order_no=?",order));pay(buyer,order);
            assertEquals("PAID",h.db.order(order,false).get("payment_status"));assertEquals(1,obligations(order).size());
            assertEquals(projection,orders.orderProjection(buyer,order));assertEquals(quoted.get("amountUsdt"),orders.orderProjection(buyer,order).get("amountUsdt"));
            var committed=h.db.requiredRow("SELECT * FROM nx_promotion_reservation WHERE order_no=?",order);
            assertEquals("COMMITTED",committed.get("status"));assertEquals(1,number(committed.get("version")));
            assertEquals(reserved.get("snapshot_json"),committed.get("snapshot_json"));assertEquals(reserved.get("snapshot_hash"),committed.get("snapshot_hash"));
            String obligation=obligations(order).get(0);var reward=h.db.requiredRow("SELECT version,snapshot_json,snapshot_hash FROM nx_promotion_reward WHERE obligation_id=?",obligation);
            assertEquals(1,number(reward.get("version")));assertEquals(reserved.get("snapshot_json"),reward.get("snapshot_json"));assertEquals(reserved.get("snapshot_hash"),reward.get("snapshot_hash"));
            BigDecimal expected="DEVICE".equals(asset)?BigDecimal.ONE:new BigDecimal("1.000001");budget(activity,asset,"reserved",BigDecimal.ZERO);budget(activity,asset,"committed",expected);
            assertEquals("ISSUED",rewards.issue(obligation,id("OLDVERSIONISSUE")).get("state"));budget(activity,asset,"issued",expected);
            if("DEVICE".equals(asset))assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_promotion_device_receipt WHERE obligation_id=?",obligation));
            else assertEquals(0,expected.compareTo(decimal(h.db.requiredRow("SELECT amount FROM nx_wallet_ledger WHERE biz_no=? AND asset=? AND direction='IN'", "PROMOTION-ISSUE-"+obligation,asset).get("amount"))));
            checks.add(values("scenario","reserved-v1-pay-after-v2-pause","run",h.run,"asset",asset,"activity",activity,"currentVersion",h.db.activity(activity,false).get("active_version"),"currentState",h.db.activity(activity,false).get("status"),"order",order,
                "orderState",h.db.order(order,false).get("payment_status"),"paidBeforePayBy",h.db.count("SELECT o.paid_at<r.pay_by FROM nx_order o JOIN nx_promotion_order_receipt r ON r.order_no=o.order_no WHERE o.order_no=?",order),
                "obligation",obligation,"reservedVersion",committed.get("version"),"obligationVersion",reward.get("version"),"oldRewardAmount",committed.get("amount"),
                "oldSnapshotHash",reserved.get("snapshot_hash"),"obligationSnapshotHash",reward.get("snapshot_hash"),"projectionBefore",projection,"projectionAfter",orders.orderProjection(buyer,order),
                "assetReceipt",rewards.get(buyer,obligation,false).get("assetReceipt"),"budget",h.db.requiredRow("SELECT total,reserved,committed,issued,reversed FROM nx_promotion_budget WHERE activity_id=? AND asset=?",activity,asset)));
        }
    }

    void versionOneQuoteCannotCreateAfterRealVersionTwoPublicationAndWritesNothing(List<Map<String,Object>> checks) throws Exception {
        setup();var common=h.commonPolicies();
        for(String asset:List.of("DEVICE","USDT","NEX")){
            var contract=h.contract(buy,runtimeReward(asset),common);String activity=h.publish(contract);long buyer=user();var quoted=quote(buyer,activity,1);
            assertEquals(1,number(quoted.get("activityVersion")));publishVersionTwo(activity,contract);
            assertEquals("ACTIVE",h.admin.get(activity).get("state"));assertEquals(2,number(h.admin.get(activity).get("activeVersion")));
            var quoteBefore=h.db.requiredRow("SELECT * FROM nx_promotion_quote WHERE quote_id=?",quoted.get("quoteId"));
            var rootBefore=h.db.activity(activity,false);var budgetsBefore=h.db.list("SELECT * FROM nx_promotion_budget WHERE activity_id=? ORDER BY asset,product_no",activity);
            var buyBefore=h.db.product(buy,false);var giftBefore=h.db.product(gift,false);
            var walletBefore=h.db.requiredRow("SELECT * FROM nx_user_wallet WHERE user_id=?",buyer);
            var walletLedgerBefore=h.db.list("SELECT * FROM nx_wallet_ledger WHERE user_id=? ORDER BY id",buyer);
            var rejected=assertThrows(ffdd.opsconsole.shared.exception.BizException.class,()->create(buyer,quoted,1));
            assertEquals(409,rejected.getCode());assertEquals("PROMOTION_QUOTE_VERSION_CHANGED",rejected.getMessage());
            assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_order WHERE user_id=?",buyer));
            assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_order_item i JOIN nx_order o ON o.order_no=i.order_no WHERE o.user_id=?",buyer));
            assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_order_receipt WHERE buyer_id=? OR quote_id=?",buyer,quoted.get("quoteId")));
            assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE activity_id=?",activity));
            assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_reward WHERE activity_id=?",activity));
            assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_usage WHERE activity_id=?",activity));
            assertEquals(quoteBefore,h.db.requiredRow("SELECT * FROM nx_promotion_quote WHERE quote_id=?",quoted.get("quoteId")));
            assertEquals(rootBefore,h.db.activity(activity,false));assertEquals(budgetsBefore,h.db.list("SELECT * FROM nx_promotion_budget WHERE activity_id=? ORDER BY asset,product_no",activity));
            assertEquals(buyBefore,h.db.product(buy,false));assertEquals(giftBefore,h.db.product(gift,false));
            var walletAfter=h.db.requiredRow("SELECT * FROM nx_user_wallet WHERE user_id=?",buyer);
            var walletLedgerAfter=h.db.list("SELECT * FROM nx_wallet_ledger WHERE user_id=? ORDER BY id",buyer);
            assertEquals(walletBefore,walletAfter);assertEquals(walletLedgerBefore,walletLedgerAfter);
            checks.add(values("scenario","v1-quote-rejected-after-v2","run",h.run,"asset",asset,"activity",activity,"quote",quoted.get("quoteId"),"quotedVersion",quoted.get("activityVersion"),"currentVersion",h.db.activity(activity,false).get("active_version"),"error",rejected.getMessage(),"errorCode",rejected.getCode(),
                "orders",h.db.count("SELECT COUNT(*) FROM nx_order WHERE user_id=?",buyer),"receipts",h.db.count("SELECT COUNT(*) FROM nx_promotion_order_receipt WHERE buyer_id=? OR quote_id=?",buyer,quoted.get("quoteId")),
                "reservations",h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE activity_id=?",activity),"rewardObligations",h.db.count("SELECT COUNT(*) FROM nx_promotion_reward WHERE activity_id=?",activity),
                "budgetBefore",budgetsBefore,"budgetAfter",h.db.list("SELECT * FROM nx_promotion_budget WHERE activity_id=? ORDER BY asset,product_no",activity),
                "walletBefore",walletBefore,"walletAfter",walletAfter,"walletLedgerBefore",walletLedgerBefore,"walletLedgerAfter",walletLedgerAfter,
                "quoteBefore",quoteBefore,"quoteAfter",h.db.requiredRow("SELECT * FROM nx_promotion_quote WHERE quote_id=?",quoted.get("quoteId"))));
        }
    }

    Map<String,Object> runtimeReward(String asset){
        return "DEVICE".equals(asset)?values("rewardRuleId","reward-buyer","beneficiaryRole","BUYER","type",asset,"giftProductNo",gift,"quantity",1,"deviceRightsProfile",h.devicePolicy(gift))
            :values("rewardRuleId","reward-buyer","beneficiaryRole","BUYER","type",asset,"calculation","FIXED","amount","1.000001","assetPolicy",h.assetPolicy(asset));
    }
    void publishVersionTwo(String activity,Map<String,Object> contract){
        h.authenticate(h.maker);var action=values("expectedRevision",h.db.activity(activity,false).get("revision"),"reason","建立新版奖励验证原预留和报价边界","evidenceRefs",List.of(h.evidence));
        h.admin.command("createDraftVersion",activity,id("VERSION"),action,()->h.admin.newVersion(activity,action,false));
        var rules=maps(copy(contract).get("rules"));var spec=map(rules.get(0).get("buyerReward"));
        spec.put("DEVICE".equals(spec.get("type"))?"quantity":"amount","DEVICE".equals(spec.get("type"))?2:"2.000002");rules.get(0).put("buyerReward",spec);
        var edit=values("expectedRevision",1,"reason","新版使用不同奖励和条款验证快照","draft",values("rules",rules,"title",PromotionRuntimeHarness.localized("Version two reward"),"terms",PromotionRuntimeHarness.localized("Version two terms")));
        h.admin.command("saveDraft",activity,id("EDIT"),edit,()->h.admin.save(activity,edit));
        for(String stage:List.of("submit","approve","publish")){
            h.authenticate("submit".equals(stage)?h.maker:"approve".equals(stage)?h.checker:h.publisher);
            var transition=values("expectedRevision",h.db.version(activity,2,false).get("revision"),"version",2,"reason","提交审批并发布真实新版奖励合同","evidenceRefs",List.of(h.evidence));
            h.admin.command(stage+"Promotion",activity,id("STAGE"),transition,()->h.admin.versionAction(activity,stage,transition));
        }
        assertEquals("PUBLISHED",h.admin.version(activity,2).get("state"));assertEquals(2,number(h.admin.get(activity).get("activeVersion")));
    }
    Map<String,Object> observedLockWait(long requesting,long blocking,String table,String record){
        assertNotEquals(requesting,blocking);var observed=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
        String sql="""
            SELECT rt.PROCESSLIST_ID requestConnection,bt.PROCESSLIST_ID blockConnection,
              w.REQUESTING_ENGINE_TRANSACTION_ID requestTransaction,w.BLOCKING_ENGINE_TRANSACTION_ID blockTransaction,
              r.OBJECT_SCHEMA objectSchema,r.OBJECT_NAME objectName,r.INDEX_NAME indexName,r.LOCK_DATA lockedRecord,
              r.LOCK_MODE requestMode,b.LOCK_MODE blockMode
            FROM performance_schema.data_lock_waits w
            JOIN performance_schema.data_locks r ON r.ENGINE=w.ENGINE AND r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
            JOIN performance_schema.data_locks b ON b.ENGINE=w.ENGINE AND b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
            JOIN performance_schema.threads rt ON rt.THREAD_ID=w.REQUESTING_THREAD_ID
            JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
            WHERE r.OBJECT_SCHEMA=DATABASE() AND b.OBJECT_SCHEMA=r.OBJECT_SCHEMA AND r.OBJECT_NAME=? AND b.OBJECT_NAME=r.OBJECT_NAME
              AND r.INDEX_NAME='PRIMARY' AND b.INDEX_NAME=r.INDEX_NAME AND r.LOCK_TYPE='RECORD' AND b.LOCK_TYPE='RECORD'
              AND r.LOCK_DATA=? AND b.LOCK_DATA=r.LOCK_DATA AND rt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
            """;
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->{
            var waits=h.db.list(sql,table,record,requesting,blocking);if(waits.isEmpty())return false;observed.set(waits.get(0));return true;
        });return observed.get();
    }
    void awaitRelease(CountDownLatch release){
        try{assertTrue(release.await(20,TimeUnit.SECONDS),"Held transaction was not released");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
    }
    @Test void zeroRewardReceiptLockWaitCannotCommitPaymentAfterItsDeadline() throws Exception {
        setup();
        h.session.getConfiguration().addMapper(ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper.class);
        var paymentMapper=h.session.getMapper(ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper.class);
        long buyer=user();var quoted=quote(buyer,null,1);
        assertNull(quoted.get("activityId"));assertTrue(maps(quoted.get("expectedRewards")).isEmpty());
        String order=create(buyer,quoted,1);
        assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_promotion_order_receipt WHERE order_no=?",order));
        assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE order_no=?",order));
        h.db.write("UPDATE nx_promotion_order_receipt SET pay_by=TIMESTAMPADD(SECOND,5,NOW(6)) WHERE order_no=?",order);
        BigDecimal balance=decimal(h.db.requiredRow("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",buyer).get("usdt_available"));
        var receiptLocked=new CountDownLatch(1);var releaseReceipt=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var blocker=executor.submit(()->tx.executeWithoutResult(status->{
                h.db.requiredRow("SELECT pay_by FROM nx_promotion_order_receipt WHERE order_no=? FOR UPDATE",order);receiptLocked.countDown();
                try {assertTrue(releaseReceipt.await(15,TimeUnit.SECONDS));}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
            }));
            assertTrue(receiptLocked.await(5,TimeUnit.SECONDS));
            var payment=executor.submit(()->{
                try {return tx.execute(status->{
                    orders.lockOrderParticipants(order);var pending=h.db.order(order,true);orders.beforePay(buyer,order);
                    var wallet=paymentMapper.lockDevelopmentWallet(buyer);
                    BigDecimal amount=decimal(pending.get("amount_usdt")),after=wallet.usdtAvailable().subtract(amount);
                    changed(paymentMapper.debitDevelopmentWallet(buyer,amount,wallet.version()));
                    changed(paymentMapper.insertDevelopmentPurchaseLedger(order,buyer,amount,after));
                    if(paymentMapper.markDevelopmentOrderActivated(order,buyer,"WALLET-PAY-"+order)!=1)
                        throw new ffdd.opsconsole.shared.exception.BizException(409,"ORDER_STATE_CONFLICT");
                    orders.afterPaid(buyer,order);return true;
                });}catch(ffdd.opsconsole.shared.exception.BizException failure){assertEquals(409,failure.getCode());return false;}
            });
            String waiting="SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='nx_promotion_order_receipt'";
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->h.db.count(waiting)>0);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(()->h.db.count("SELECT NOW(6)>=pay_by FROM nx_promotion_order_receipt WHERE order_no=?",order)==1);
            assertFalse(payment.isDone());releaseReceipt.countDown();blocker.get(5,TimeUnit.SECONDS);
            boolean paid=payment.get(10,TimeUnit.SECONDS);
            BigDecimal after=decimal(h.db.requiredRow("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",buyer).get("usdt_available"));
            var facts=values("orderNo",order,"observedReceiptLockWait",true,"lateRejected",!paid,"paymentState",h.db.order(order,false).get("payment_status"),
                    "walletBefore",money(balance),"walletAfter",money(after),
                    "purchaseLedgerCount",h.db.count("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=? AND biz_type='ORDER_PURCHASE'",order),
                    "rewardCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_reward WHERE order_no=?",order),
                    "reservationCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE order_no=?",order));
            Files.writeString(Path.of("D:/CodexData/test-environments/workflow-runs/growth-promotions-20261007/promotion-zero-reward-receipt-deadline-runtime.json"),json(facts));
            assertFalse(paid,"Expired zero-reward payment committed after receipt lock wait");
            assertEquals(0,balance.compareTo(after));assertEquals("PENDING",facts.get("paymentState"));
            assertEquals(0L,facts.get("purchaseLedgerCount"));assertEquals(0L,facts.get("rewardCount"));assertEquals(0L,facts.get("reservationCount"));
        } finally {releaseReceipt.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(15,TimeUnit.SECONDS));}
    }

    @Test void walletLockWaitCannotCommitPaymentAfterItsReservedDeadline() throws Exception {
        setup();
        h.session.getConfiguration().addMapper(ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper.class);
        var paymentMapper=h.session.getMapper(ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper.class);
        var reward=values("rewardRuleId","deadline-reward","beneficiaryRole","BUYER","type","USDT","calculation","FIXED","amount","0.000001","assetPolicy",h.assetPolicy("USDT"));
        String activity=h.publish(h.contract(buy,reward,h.commonPolicies()));
        long buyer=user();String order=create(buyer,quote(buyer,activity,1),1);
        h.db.write("UPDATE nx_promotion_order_receipt SET pay_by=TIMESTAMPADD(SECOND,5,NOW(6)) WHERE order_no=?",order);
        h.db.write("UPDATE nx_promotion_reservation r JOIN nx_promotion_order_receipt p ON p.order_no=r.order_no SET r.pay_by=p.pay_by WHERE r.order_no=?",order);
        BigDecimal balance=decimal(h.db.requiredRow("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",buyer).get("usdt_available"));
        var walletLocked=new CountDownLatch(1);var paymentReachedWallet=new CountDownLatch(1);var releaseWallet=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var blocker=executor.submit(()->tx.executeWithoutResult(status->{
                h.db.requiredRow("SELECT user_id FROM nx_user_wallet WHERE user_id=? FOR UPDATE",buyer);walletLocked.countDown();
                try {assertTrue(releaseWallet.await(15,TimeUnit.SECONDS));}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
            }));
            assertTrue(walletLocked.await(5,TimeUnit.SECONDS));
            var payment=executor.submit(()->{
                try {return tx.execute(status->{
                    orders.lockOrderParticipants(order);var pending=h.db.order(order,true);orders.beforePay(buyer,order);
                    paymentReachedWallet.countDown();var wallet=paymentMapper.lockDevelopmentWallet(buyer);
                    BigDecimal amount=decimal(pending.get("amount_usdt")),after=wallet.usdtAvailable().subtract(amount);
                    changed(paymentMapper.debitDevelopmentWallet(buyer,amount,wallet.version()));
                    changed(paymentMapper.insertDevelopmentPurchaseLedger(order,buyer,amount,after));
                    if(paymentMapper.markDevelopmentOrderActivated(order,buyer,"WALLET-PAY-"+order)!=1)
                        throw new ffdd.opsconsole.shared.exception.BizException(409,"ORDER_STATE_CONFLICT");
                    orders.afterPaid(buyer,order);return true;
                });}catch(ffdd.opsconsole.shared.exception.BizException failure){assertEquals(409,failure.getCode());return false;}
            });
            assertTrue(paymentReachedWallet.await(5,TimeUnit.SECONDS));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(()->h.db.count("SELECT NOW(6)>=pay_by FROM nx_promotion_order_receipt WHERE order_no=?",order)==1);
            releaseWallet.countDown();blocker.get(5,TimeUnit.SECONDS);
            boolean paid=payment.get(10,TimeUnit.SECONDS);
            BigDecimal after=decimal(h.db.requiredRow("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",buyer).get("usdt_available"));
            var facts=values("orderNo",order,"lateRejected",!paid,"paymentState",h.db.order(order,false).get("payment_status"),
                    "walletBefore",money(balance),"walletAfter",money(after),
                    "purchaseLedgerCount",h.db.count("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no=? AND biz_type='ORDER_PURCHASE'",order),
                    "rewardCount",h.db.count("SELECT COUNT(*) FROM nx_promotion_reward WHERE order_no=?",order));
            Files.writeString(Path.of("D:/CodexData/test-environments/workflow-runs/growth-promotions-20261007/promotion-late-payment-runtime.json"),json(facts));
            assertFalse(paid,"Expired payment committed after wallet lock wait");
            assertEquals(0,balance.compareTo(after));assertEquals("PENDING",facts.get("paymentState"));
            assertEquals(0L,facts.get("purchaseLedgerCount"));assertEquals(0L,facts.get("rewardCount"));
            assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE order_no=? AND status='RESERVED'",order));
        } finally {releaseWallet.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(15,TimeUnit.SECONDS));}
    }

    @Test void realApprovalReservationsWalletsDevicesRefundsAndReplay() throws Exception {
        setup();
        Map<String,Object> common=h.commonPolicies();
        for(String asset:List.of("USDT","NEX","DEVICE")){
            Map<String,Object> spec="DEVICE".equals(asset)
                ?values("rewardRuleId","reward-buyer","beneficiaryRole","BUYER","type","DEVICE","giftProductNo",gift,"quantity",2,"deviceRightsProfile",h.devicePolicy(gift))
                :values("rewardRuleId","reward-buyer","beneficiaryRole","BUYER","type",asset,"calculation","FIXED","amount","2.125001","assetPolicy",h.assetPolicy(asset));
            Map<String,Object> contract=h.contract(buy,spec,common);
            int units="DEVICE".equals(asset)?1:2,groups=units;
            String activity=h.publish(contract);long buyer=user();
            BigDecimal initialStock=decimal(h.db.product(gift,false).get("stock"));
            Map<String,Object> quoted=quote(buyer,activity,units);
            assertEquals(1,number(quoted.get("itemCount")));assertEquals(units,number(quoted.get("quantity")));
            assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE activity_id=?",activity));
            String order=create(buyer,quoted,units);
            assertEquals(groups,h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE order_no=?",order));
            assertEquals(groups,h.db.count("SELECT COUNT(DISTINCT unit_seq) FROM nx_promotion_reservation WHERE order_no=?",order));
            assertEquals(units+("DEVICE".equals(asset)?2:0),canonical.reservedDeviceOrderCount(buyer));
            assertEquals(units+("DEVICE".equals(asset)?2:0),bundle.reservedDeviceOrderCount(buyer));
            assertEquals("DEVICE".equals(asset)?2:0,canonical.developmentReservedDeviceOrderCount(buyer));
            assertEquals(1,h.db.count("SELECT COUNT(DISTINCT order_line_id) FROM nx_promotion_reservation WHERE order_no=?",order));
            assertEquals(1,number(orders.orderProjection(buyer,order).get("itemCount")));
            BigDecimal reserved="DEVICE".equals(asset)?new BigDecimal("2"):new BigDecimal("4.250002");
            budget(activity,asset,"reserved",reserved);
            assertThrows(RuntimeException.class,()->tx.execute(s->orders.prepareCreate(buyer,text(quoted.get("quoteId")),List.of(new PromotionOrderService.Selection(buy,units)),null,null)));
            pay(buyer,order);
            budget(activity,asset,"reserved",BigDecimal.ZERO);budget(activity,asset,"committed",reserved);
            List<String> ids=obligations(order);
            assertEquals(groups,ids.size());
            String faultCommand=id("FAULT"),first=ids.get(0);
            assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{rewards.issue(first,faultCommand);throw new IllegalStateException("ISOLATED_AFTER_POSTING_ROLLBACK");}));
            assertEquals("PENDING",rewards.get(buyer,first,false).get("state"));
            assertNull(rewards.get(buyer,first,false).get("assetReceipt"));budget(activity,asset,"committed",reserved);
            rewards.recordFailure(first,faultCommand,"ISOLATED_AFTER_POSTING_ROLLBACK");
            h.db.write("UPDATE nx_promotion_reward SET status='OUTCOME_UNKNOWN' WHERE obligation_id=?",first);
            assertThrows(ffdd.opsconsole.shared.exception.BizException.class,()->rewards.issue(first,id("UNKNOWN-MUST-NOT-ISSUE")));
            assertEquals("READY",rewards.reconcile(first,id("RECONCILE")).get("state"));
            for(String id:ids){assertEquals("ISSUED",rewards.issue(id,h.run+id+"issue").get("state"));assertEquals("ISSUED",rewards.issue(id,h.run+id+"replay").get("state"));}
            budget(activity,asset,"committed",BigDecimal.ZERO);budget(activity,asset,"issued",reserved);
            assertEquals(groups,h.db.count("SELECT COUNT(*) FROM nx_promotion_reward_attempt a JOIN nx_promotion_reward r ON r.obligation_id=a.obligation_id WHERE r.order_no=? AND a.status='ISSUED'",order));
            if("DEVICE".equals(asset)){
                assertEquals(initialStock.subtract(new BigDecimal("2")),decimal(h.db.product(gift,false).get("stock")));
                assertEquals(2,h.db.count("SELECT COUNT(*) FROM nx_user_device WHERE source_order_no=? AND source_channel='PROMOTION_GIFT' AND user_id=?",order,buyer));
                assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_order WHERE user_id=?",buyer));
                assertEquals("V0",h.db.user(buyer,false).get("v_rank"));
                assertEquals(0,h.db.count("SELECT COUNT(*) FROM nx_team_member WHERE member_user_id=?",buyer));
                assertEquals(0,canonical.reservedDeviceOrderCount(buyer));assertEquals(0,bundle.reservedDeviceOrderCount(buyer));assertEquals(0,canonical.developmentReservedDeviceOrderCount(buyer));
                Map<String,Object> overCapacity=quote(buyer,activity,1);
                var rejected=assertThrows(ffdd.opsconsole.shared.exception.BizException.class,()->create(buyer,overCapacity,1));
                assertEquals("PROMOTION_DEVICE_CAPACITY_UNAVAILABLE",rejected.getMessage());
                assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_order WHERE user_id=?",buyer));
                budget(activity,asset,"reserved",BigDecimal.ZERO);
                for(Map<String,Object> d:h.db.list("SELECT id FROM nx_user_device WHERE source_order_no=? AND source_channel='PROMOTION_GIFT'",order)){
                    long device=number(d.get("id"));
                    assertNull(tradein.findSourceDevice(buyer,device));
                    tx.executeWithoutResult(s->assertNull(tradein.lockSourceDevice(buyer,device)));
                }
                assertTrue(tradein.listTradeinSourceCandidates(buyer).isEmpty());
                assertNull(tradein.findCapacityReplacementSource(buyer));
                tx.executeWithoutResult(s->assertNull(tradein.lockCapacityReplacementSource(buyer)));
            }else{
                assertEquals(2,h.db.count("SELECT COUNT(*) FROM nx_earnings_release_entry WHERE user_id=? AND source_type='PROMOTION_REWARD' AND asset=?",buyer,asset));
                assertEquals(2,h.db.count("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=? AND biz_type='PROMOTION_REWARD' AND asset=? AND direction='IN'",buyer,asset));
            }
            refund(buyer,order);
            String reverseFault=id("REVERSEFAULT");
            assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{rewards.reverseRefund(first,reverseFault);throw new IllegalStateException("ISOLATED_AFTER_RECOVERY_ROLLBACK");}));
            assertEquals("REVERSAL_PENDING",rewards.get(buyer,first,false).get("state"));
            budget(activity,asset,"reversed",BigDecimal.ZERO);
            rewards.recordActionFailure(first,reverseFault,"REVERSE","ISOLATED_AFTER_RECOVERY_ROLLBACK");
            for(String id:ids){assertEquals("REVERSAL_PENDING",rewards.get(buyer,id,false).get("state"));assertEquals("REVERSED",rewards.reverseRefund(id,h.run+id+"reverse").get("state"));}
            budget(activity,asset,"reversed",reserved);
            assertEquals("E4_REFUND",h.db.requiredRow("SELECT source_type FROM nx_promotion_refund_hold WHERE order_no=?",order).get("source_type"));
            assertEquals(order,h.db.requiredRow("SELECT source_id FROM nx_promotion_refund_hold WHERE order_no=?",order).get("source_id"));
            for(String id:ids)assertEquals("REVERSED",rewards.reverseRefund(id,h.run+id+"reverse-replay").get("state"));
            assertEquals(groups,h.db.count("SELECT COUNT(*) FROM nx_promotion_reversal v JOIN nx_promotion_reward r ON r.obligation_id=v.obligation_id WHERE r.order_no=?",order));
            assertEquals(groups,h.db.count("SELECT COUNT(*) FROM nx_promotion_reward_attempt a JOIN nx_promotion_reward r ON r.obligation_id=a.obligation_id WHERE r.order_no=? AND a.action='REVERSE' AND a.status='REVERSED' AND a.finished_at IS NOT NULL",order));
            if("DEVICE".equals(asset))assertEquals(initialStock,decimal(h.db.product(gift,false).get("stock")));
            record("issue-replay-whole-refund",values("asset",asset,"activity",activity,"order",order,"obligations",ids,"amount",money(reserved)));
            assertEquals(2,h.db.count("SELECT COUNT(*) FROM nx_promotion_reward_attempt WHERE obligation_id=? AND status='RETRYABLE_FAILED' AND finished_at IS NOT NULL",first));
            record("post-write-rollback-reconcile-retry",values("asset",asset,"obligation",first,"issuanceRollback",true,"recoveryRollback",true));
            for(String terminal:List.of("CANCELLED","EXPIRED")){
                long other=user();String unpaid=create(other,quote(other,activity,units),units);
                tx.executeWithoutResult(s->{orders.lockOrderParticipants(unpaid);h.db.order(unpaid,true);h.db.write("UPDATE nx_order SET payment_status=?,order_status=? WHERE order_no=?",terminal,terminal,unpaid);orders.releaseUnpaid(unpaid,terminal);orders.releaseUnpaid(unpaid,terminal);});
                assertEquals(groups,h.db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE order_no=? AND status='RELEASED'",unpaid));
                assertTrue(obligations(unpaid).isEmpty());budget(activity,asset,"reserved",BigDecimal.ZERO);
                record("terminal-release",values("asset",asset,"terminal",terminal,"order",unpaid));
            }
            holdAndUnissuedRefund(activity,asset);
        }
        firstPurchaseCompetition(common);
        activityCapacityCompetition(common);
        multiSkuQuantities(common);
        referralAndRepurchase(common);
        Path output=Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/promotion-lifecycle-runtime.json");
        Files.writeString(output,json(values("completed",true,"scope","Isolated promotion service transactions; order facts are explicit fixtures","run",h.run,"scenarioCount",evidence.size(),"evidence",evidence)));
    }
    void setup() throws Exception{
        h=new PromotionRuntimeHarness();tx=new TransactionTemplate(new DataSourceTransactionManager(h.dataSource));
        h.session.getConfiguration().addMapper(AppTradeinMapper.class);tradein=h.session.getMapper(AppTradeinMapper.class);
        h.session.getConfiguration().addMapper(CanonicalStateMapper.class);canonical=h.session.getMapper(CanonicalStateMapper.class);
        h.session.getConfiguration().addMapper(AppBundleOrderMapper.class);bundle=h.session.getMapper(AppBundleOrderMapper.class);
        var outbox=new EventOutboxService(h.session.getMapper(EventOutboxMapper.class),new ObjectMapper().findAndRegisterModules(),new OutboxProperties(),
            new A4RuntimePolicyService(new MybatisPlatformConfigRepository(h.session.getMapper(PlatformConfigItemMapper.class))));
        var repo=h.proxy(new MybatisTreasuryLedgerRepository(h.session.getMapper(TreasuryLedgerMapper.class),outbox));ledger=new TreasuryLedgerPostingFacadeAdapter(repo,null);
        var env=new MockEnvironment();env.setActiveProfiles("dev");
        var guard=new FundsSandboxProfileGuard(new FundsSandboxProperties(),env);
        var earnings=h.proxy(new EarningsReleaseService(h.session.getMapper(EarningsReleaseMapper.class),h.earningsPolicy,h.idempotency,h.audit,guard));
        var evaluator=new PromotionEvaluationService(h.db);
        var vouchers=new AppGrowthLifecyclePublisher(h.session.getMapper(AppGrowthLifecycleMapper.class),h.audit,outbox);
        quotes=h.proxy(new PromotionQuoteService(h.db,h.validator,h.resolver,evaluator,h.config,vouchers));
        orders=h.proxy(new PromotionOrderService(h.db,quotes,evaluator,h.resolver,h.audit,outbox));
        rewards=h.proxy(new PromotionRewardService(h.db,orders,earnings,ledger,h.audit,outbox));
        var product=h.db.requiredRow("""
            SELECT p.product_no FROM nx_product p JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0
            WHERE p.is_deleted=0 AND p.status='ACTIVE' AND p.store_visible=1 AND p.price_usdt>0 AND p.stock>=20
              AND UPPER(p.product_type) IN ('DEVICE','SERVER') AND p.vram_total_gb>0 AND s.datacenter<>''
              AND s.power_text REGEXP '^[0-9]+([.][0-9]+)?[[:space:]]*[Ww]?$'
            ORDER BY p.product_no LIMIT 1
            """);String source=text(product.get("product_no"));buy=h.run+"-lifecycle-sku";gift=h.run+"-lifecycle-gift";
        // A run owns its catalogue inventory. Expiry/cancellation must never consume another test or HTTP run's stock.
        cloneCatalogRow("nx_product","product_no",source,buy);
        cloneCatalogRow("nx_admin_device_sku","sku_id",source,buy);
        changed(h.db.write("UPDATE nx_product SET stock=10000,sold_count=0,name=? WHERE product_no=?",h.run+" isolated lifecycle inventory",buy));
        cloneCatalogRow("nx_product","product_no",buy,gift);cloneCatalogRow("nx_admin_device_sku","sku_id",buy,gift);
    }
    void cloneCatalogRow(String table,String identity,String source,String target){
        List<String> columns=h.db.list("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND EXTRA NOT LIKE '%auto_increment%' AND EXTRA NOT LIKE '%GENERATED%' ORDER BY ORDINAL_POSITION",table).stream().map(r->text(r.get("COLUMN_NAME"))).toList();
        String names=columns.stream().map(c->"`"+c+"`").collect(java.util.stream.Collectors.joining(","));
        String projection=columns.stream().map(c->c.equals(identity)?"?":"`"+c+"`").collect(java.util.stream.Collectors.joining(","));
        changed(h.db.write("INSERT INTO "+table+" ("+names+") SELECT "+projection+" FROM "+table+" WHERE "+identity+"=?",target,source));
    }
    long user(){
        long id=++nextUser;
        h.db.write("INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,sandbox,status,v_rank) VALUES(?,'00',?,'127.0.0.1','fixture',?,?,0,'ACTIVE','V0')",id,String.valueOf(id),h.run,String.valueOf(id));
        h.db.write("INSERT INTO nx_user_wallet(user_id,sandbox,usdt_available) VALUES(?,0,1000000)",id);
        tx.executeWithoutResult(s->ledger.postLedgerEntry("PTEST-INITIAL-"+id,id,"ISOLATED_FIXTURE_FUNDING","USDT","IN",new BigDecimal("1000000"),"SUCCESS","Isolated wallet opening balance"));
        return id;
    }
    Map<String,Object> quote(long user,String activity,int quantity){return quotes.quote(user,values("items",List.of(values("productNo",buy,"quantity",quantity)),"activityId",activity,"clientCapabilities",List.of("PROMOTION_QUOTE_V1","ORDER_ITEM_QUANTITIES_V1","PROMOTION_REWARDS_V1")));}
    String create(long user,Map<String,Object> quote,int quantity){
        return tx.execute(s->{
            List<Map<String,Object>> items=maps(quote.get("items"));
            String order=id("PTEST");var plan=orders.prepareCreate(user,text(quote.get("quoteId")),items.stream().map(i->new PromotionOrderService.Selection(text(i.get("productNo")),Math.toIntExact(number(i.get("quantity"))))).toList(),null,amount(quote.get("amountUsdt")));
            var product=h.db.product(text(items.get(0).get("productNo")),false);BigDecimal amount=amount(quote.get("amountUsdt"));
            h.db.write("INSERT INTO nx_order(user_id,order_no,product_id,quantity,order_type,item_count,subtotal_usdt,discount_usdt,amount_usdt,payment_status,order_status) VALUES(?,?,?,?,?,?,?,?,?,'PENDING','PENDING_PAYMENT')",user,order,product.get("id"),quantity,items.size()>1?"BUNDLE":"SINGLE",items.size(),amount(quote.get("subtotalUsdt")),amount(quote.get("discountUsdt")),amount);
            for(Map<String,Object> item:items){var p=h.db.product(text(item.get("productNo")),false);
                changed(canonical.decrementProductStock(number(p.get("id")),Math.toIntExact(number(item.get("quantity")))));
                h.db.write("INSERT INTO nx_order_item(order_no,product_id,product_no,product_name,quantity,unit_price_usdt,line_amount_usdt) VALUES(?,?,?,?,?,?,?)",order,p.get("id"),item.get("productNo"),p.get("name"),number(item.get("quantity")),amount(item.get("unitPriceUsdt")),amount(item.get("payableUsdt")));}
            orders.reserveCreatedOrder(plan,order,Instant.now().plusSeconds(1800));return order;
        });
    }
    void pay(long user,String order){
        tx.executeWithoutResult(s->{
            orders.lockOrderParticipants(order);var o=h.db.order(order,true);orders.beforePay(user,order);BigDecimal amount=decimal(o.get("amount_usdt"));
            changed(h.db.write("UPDATE nx_user_wallet SET usdt_available=usdt_available-?,version=version+1 WHERE user_id=? AND usdt_available>=?",amount,user,amount));
            ledger.postLedgerEntry("PTEST-PAY-"+order,user,"ORDER_PAYMENT","USDT","OUT",amount,"SUCCESS","Isolated canonical source fixture");
            changed(h.db.write("UPDATE nx_order SET payment_status='PAID',order_status='COMPLETED',activation_status='ACTIVATED',paid_at=NOW(6) WHERE order_no=?",order));
            orders.afterPaid(user,order);orders.afterPaid(user,order);
        });
    }
    void refund(long user,String order){
        tx.executeWithoutResult(s->{
            orders.lockOrderParticipants(order);var o=h.db.order(order,true);BigDecimal amount=decimal(o.get("amount_usdt"));
            changed(h.db.write("UPDATE nx_user_wallet SET usdt_available=usdt_available+?,version=version+1 WHERE user_id=?",amount,user));
            ledger.postLedgerEntry("E4-REFUND-"+order,user,"ORDER_REFUND","USDT","IN",amount,"SUCCESS","Isolated E4 source fixture");
            changed(h.db.write("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",order));orders.confirmE4Refund(order,"E4-REFUND-"+order);
        });
    }
    void holdAndUnissuedRefund(String activity,String asset){
        long buyer=user();String order=create(buyer,quote(buyer,activity,1),1);pay(buyer,order);String id=obligations(order).get(0);
        String operation=id("A2T");
        h.db.write("""
            INSERT INTO nx_audit_operation_ticket(operation_id,action,object_text,before_value,after_value,operator_name,operator_role,
              operation_type,amplifies,sos,time_label,mine,role_gate,reason,status,command_json,source_domain)
            VALUES(?,'refund',?,'PAID','REFUNDED','fixture','fixture','e4_order_refund',0,0,'fixture',0,'fixture','Isolated actual refund proposal','pending',?,'E')
            """,operation,order,json(values("domain","E","op","e4_order_refund","params",values("orderNo",order))));
        tx.executeWithoutResult(s->{orders.lockOrderParticipants(order);h.db.order(order,true);orders.holdA2Refund(operation,order);});
        assertThrows(RuntimeException.class,()->rewards.issue(id,id("blocked")));
        assertEquals("PENDING",rewards.get(buyer,id,false).get("state"));
        tx.executeWithoutResult(s->{orders.lockOrderParticipants(order);h.db.order(order,true);h.db.write("UPDATE nx_audit_operation_ticket SET status='rejected' WHERE operation_id=?",operation);orders.clearA2Refund(operation,order);});
        assertFalse((Boolean)rewards.get(buyer,id,false).get("refundHold"));
        refund(buyer,order);assertEquals("CANCELLED",rewards.get(buyer,id,false).get("state"));
        assertNull(rewards.get(buyer,id,false).get("assetReceipt"));budget(activity,asset,"committed",BigDecimal.ZERO);
        record("hold-release-unissued-refund",values("asset",asset,"order",order,"obligation",id));
    }
    Map<String,Object> coinSpec(){return values("rewardRuleId","reward-buyer","beneficiaryRole","BUYER","type","NEX","calculation","FIXED","amount","1.000001","assetPolicy",h.assetPolicy("NEX"));}
    void firstPurchaseCompetition(Map<String,Object> common) throws Exception{
        Map<String,Object> policy=copy(common);policy.put("firstPurchase",h.approvePolicy(values("kind","FIRST_PURCHASE","executorCode","PROMOTION_FIRST_PURCHASE_V1","mode","FIRST_ELIGIBLE_ORDER","restoreAfterRefund",false)));
        Map<String,Object> contract=h.contract(buy,coinSpec(),policy);contract.put("template","FIRST_PURCHASE");
        Map<String,Object> audience=copy(map(contract.get("buyerAudience")));audience.put("purchaseHistory","NEVER_PAID");contract.put("buyerAudience",audience);
        String activity=h.publish(contract);long buyer=user();
        String a=create(buyer,quote(buyer,activity,1),1),b=create(buyer,quote(buyer,activity,1),1);
        CountDownLatch snapshots=new CountDownLatch(2);var executor=Executors.newFixedThreadPool(2);
        try{
            List<Future<Boolean>> results=new ArrayList<>();
            for(String order:List.of(a,b))results.add(executor.submit(()->{
                try{return tx.execute(s->{
                    h.db.count("SELECT COUNT(*) FROM nx_order WHERE user_id=? AND payment_status='PAID'",buyer);
                    snapshots.countDown();try{assertTrue(snapshots.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
                    pay(buyer,order);return true;
                });}catch(ffdd.opsconsole.shared.exception.BizException rejected){assertEquals("PROMOTION_PAYMENT_ELIGIBILITY_LOST",rejected.getMessage());return false;}
            }));
            int paid=0;for(Future<Boolean> result:results)if(result.get(20,TimeUnit.SECONDS))paid++;
            assertEquals(1,paid);assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_order WHERE user_id=? AND payment_status='PAID'",buyer));
            assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_promotion_reward WHERE activity_id=?",activity));
            record("first-purchase-two-stale-snapshots",values("activity",activity,"buyer",buyer,"paidOrders",paid));
        }finally{executor.shutdownNow();}
    }
    void activityCapacityCompetition(Map<String,Object> common) throws Exception{
        Map<String,Object> contract=h.contract(buy,coinSpec(),common);contract.put("activityLimit",values("mode","LIMITED","value",1));
        String activity=h.publish(contract);long a=user(),b=user();Map<String,Object> qa=quote(a,activity,1),qb=quote(b,activity,1);
        CountDownLatch snapshots=new CountDownLatch(2);var executor=Executors.newFixedThreadPool(2);
        try{
            List<Future<Boolean>> results=new ArrayList<>();
            for(long buyer:List.of(a,b))results.add(executor.submit(()->{
                try{return tx.execute(s->{h.db.count("SELECT reserved_orders FROM nx_promotion WHERE activity_id=?",activity);snapshots.countDown();
                    try{assertTrue(snapshots.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
                    create(buyer,buyer==a?qa:qb,1);return true;
                });}catch(ffdd.opsconsole.shared.exception.BizException rejected){assertEquals("PROMOTION_CAPACITY_UNAVAILABLE",rejected.getMessage());return false;}
            }));
            int created=0;for(Future<Boolean> result:results)if(result.get(20,TimeUnit.SECONDS))created++;
            assertEquals(1,created);assertEquals(1,h.db.count("SELECT reserved_orders FROM nx_promotion WHERE activity_id=?",activity));
            record("activity-last-capacity-race",values("activity",activity,"created",created));
        }finally{executor.shutdownNow();}
    }
    void multiSkuQuantities(Map<String,Object> common){
        String second=h.run+"-lifecycle-second";cloneCatalogRow("nx_product","product_no",buy,second);cloneCatalogRow("nx_admin_device_sku","sku_id",buy,second);
        Map<String,Object> contract=h.contract(buy,coinSpec(),common);contract.put("template","MULTI_PRODUCT");contract.put("combinationMatch","ALL");
        Map<String,Object> rule=copy(maps(contract.get("rules")).get(0)),secondRule=copy(rule);secondRule.put("ruleId","r2");secondRule.put("productNo",second);secondRule.put("priority",2);
        Map<String,Object> reward=copy(map(secondRule.get("buyerReward")));reward.put("rewardRuleId","reward-buyer-2");secondRule.put("buyerReward",reward);contract.put("rules",List.of(rule,secondRule));
        String activity=h.publish(contract);long buyer=user();
        Map<String,Object> quote=quotes.quote(buyer,values("items",List.of(values("productNo",buy,"quantity",2),values("productNo",second,"quantity",1)),"activityId",activity,"clientCapabilities",List.of("PROMOTION_QUOTE_V1","ORDER_ITEM_QUANTITIES_V1","PROMOTION_REWARDS_V1")));
        String order=create(buyer,quote,3);assertEquals(2,number(orders.orderProjection(buyer,order).get("itemCount")));assertEquals(3,number(orders.orderProjection(buyer,order).get("quantity")));
        var receipt=orders.orderProjection(buyer,order);var items=maps(receipt.get("items"));assertEquals(2,items.size());
        assertEquals(Set.of(buy,second),new HashSet<>(items.stream().map(item->text(item.get("productNo"))).toList()));
        assertEquals(3,items.stream().mapToLong(item->number(item.get("quantity"))).sum());
        for(var expected:maps(receipt.get("rewards")))assertTrue(items.stream().anyMatch(item->item.get("lineId").equals(expected.get("lineId"))));
        assertEquals(2,h.db.count("SELECT COUNT(DISTINCT order_line_id) FROM nx_promotion_reservation WHERE order_no=?",order));pay(buyer,order);
        assertEquals(items,maps(orders.orderProjection(buyer,order).get("items")));
        assertEquals(3,obligations(order).size());record("multi-sku-quantities-and-lines",values("activity",activity,"order",order,"quantity",3,"lines",2));
    }
    void referralAndRepurchase(Map<String,Object> common){
        Map<String,Object> policy=copy(common);policy.put("firstPurchase",h.approvePolicy(values("kind","FIRST_PURCHASE","executorCode","PROMOTION_FIRST_PURCHASE_V1","mode","FIRST_ELIGIBLE_ORDER","restoreAfterRefund",false)));
        Map<String,Object> contract=h.contract(buy,coinSpec(),policy);contract.put("category","REFERRAL");contract.put("template","DIRECT_REFERRAL");
        Map<String,Object> audience=copy(map(contract.get("buyerAudience")));contract.put("inviterAudience",copy(audience));audience.put("purchaseHistory","NEVER_PAID");contract.put("buyerAudience",audience);
        Map<String,Object> rule=copy(maps(contract.get("rules")).get(0)),inviterReward=copy(map(rule.get("buyerReward")));inviterReward.put("rewardRuleId","reward-inviter");inviterReward.put("beneficiaryRole","DIRECT_INVITER");rule.put("inviterReward",inviterReward);contract.put("rules",List.of(rule));
        String activity=h.publish(contract);long inviter=user(),buyer=user();h.db.write("UPDATE nx_user SET sponsor_user_id=? WHERE id=?",inviter,buyer);
        String order=create(buyer,quote(buyer,activity,1),1);pay(buyer,order);
        assertEquals(2,obligations(order).size());assertEquals(2,h.db.count("SELECT COUNT(DISTINCT beneficiary_id) FROM nx_promotion_reward WHERE order_no=?",order));
        for(String id:obligations(order))assertEquals("ISSUED",rewards.issue(id,id("REFERRAL")).get("state"));
        assertEquals(1,h.db.count("SELECT COUNT(*) FROM nx_earnings_release_entry WHERE user_id=? AND source_type='PROMOTION_REWARD'",inviter));
        record("real-direct-inviter-two-beneficiaries",values("activity",activity,"buyer",buyer,"inviter",inviter,"order",order));
        Map<String,Object> repurchase=h.contract(buy,coinSpec(),common);repurchase.put("template","REPURCHASE");
        Map<String,Object> repeatAudience=copy(map(repurchase.get("buyerAudience")));repeatAudience.put("purchaseHistory","HAS_VALID_PURCHASE");repeatAudience.put("lastValidPurchaseAge",values("minDays",0,"maxDays",365));repurchase.put("buyerAudience",repeatAudience);
        String repeatActivity=h.publish(repurchase);String repeat=create(buyer,quote(buyer,repeatActivity,1),1);pay(buyer,repeat);
        assertEquals(1,obligations(repeat).size());assertEquals("ISSUED",rewards.issue(obligations(repeat).get(0),id("REPURCHASE")).get("state"));
        long neverBought=user();assertThrows(ffdd.opsconsole.shared.exception.BizException.class,()->quote(neverBought,repeatActivity,1));
        record("repurchase-valid-history-required",values("activity",repeatActivity,"buyer",buyer,"order",repeat));
    }
    List<String> obligations(String order){return h.db.list("SELECT obligation_id FROM nx_promotion_reward WHERE order_no=? ORDER BY unit_seq",order).stream().map(r->text(r.get("obligation_id"))).toList();}
    void budget(String activity,String asset,String field,BigDecimal expected){assertEquals(0,decimal(h.db.requiredRow("SELECT * FROM nx_promotion_budget WHERE activity_id=? AND asset=?",activity,asset).get(field)).compareTo(expected),field+" "+asset);}
    void record(String scenario,Map<String,Object> data){
        evidence.add(values("scenario",scenario,"facts",data));
        try{Files.writeString(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/promotion-lifecycle-runtime.json"),
            json(values("completed",false,"scope","Isolated promotion service transactions; order facts are explicit fixtures","run",h.run,"scenarioCount",evidence.size(),"evidence",evidence)));}
        catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
    }
}
