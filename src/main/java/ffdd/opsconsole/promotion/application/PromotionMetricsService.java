package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.audit.*;
import java.io.ByteArrayInputStream;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Service
@RequiredArgsConstructor
public class PromotionMetricsService {
    private final PromotionMapper db;
    private final PromotionContractValidator validator;
    private final ObjectStorageService storage;
    private final AuditLogService audit;

    /** Operational obligations, including fixtures: never conflated with the business metrics cohort. */
    public static Map<String,Object> readImpact(PromotionMapper db,String activity,Instant asOf){
        var reservations=db.requiredRow("SELECT COUNT(*) reserved,COUNT(DISTINCT order_no) unpaid_orders FROM nx_promotion_reservation WHERE activity_id=? AND status='RESERVED'",activity);
        var rewards=db.requiredRow("""
            SELECT COUNT(*) obligations,
              COALESCE(SUM(status IN ('PENDING','READY','PROCESSING')),0) pending,
              COALESCE(SUM(status='RETRYABLE_FAILED'),0) failed,
              COALESCE(SUM(status='OUTCOME_UNKNOWN'),0) unknown_count,
              COALESCE(SUM(status='REVERSAL_PENDING'),0) reversing,
              COALESCE(SUM(status='MANUAL_REVIEW'),0) manual_count,
              COALESCE(SUM(status NOT IN ('ISSUED','CANCELLED','REVERSED')),0) unfulfilled
            FROM nx_promotion_reward WHERE activity_id=?
            """,activity);
        long issued=db.count("""
            SELECT COUNT(*) FROM nx_promotion_reward r JOIN nx_promotion_reservation s ON s.reservation_id=r.reservation_id
            WHERE r.activity_id=? AND ((s.asset IN ('USDT','NEX') AND EXISTS(
              SELECT 1 FROM nx_wallet_ledger l WHERE l.biz_no=r.original_ledger_no AND l.user_id=r.beneficiary_id
                AND l.asset=s.asset AND l.amount=s.amount AND l.direction='IN' AND l.status='SUCCESS'))
              OR (s.asset='DEVICE' AND s.amount=(SELECT COUNT(*) FROM nx_promotion_device_receipt x
                JOIN nx_user_device d ON d.id=x.device_id WHERE x.obligation_id=r.obligation_id
                  AND d.user_id=r.beneficiary_id AND d.source_order_no=r.order_no AND d.source_channel='PROMOTION_GIFT')))
            """,activity);
        long failed=number(rewards.get("failed")),unknown=number(rewards.get("unknown_count")),reversing=number(rewards.get("reversing")),manual=number(rewards.get("manual_count"));
        var orders=db.list("SELECT order_no,version,MIN(pay_by) pay_by,COUNT(*) reserved_rewards FROM nx_promotion_reservation WHERE activity_id=? AND status='RESERVED' GROUP BY order_no,version ORDER BY order_no,version",activity).stream().map(r->values("orderNo",r.get("order_no"),"version",number(r.get("version")),"payBy",instant(r.get("pay_by")).toString(),"reservedRewards",number(r.get("reserved_rewards")))).toList();
        return values("reserved",number(reservations.get("reserved")),"issued",issued,"unresolved",failed+unknown+reversing+manual,"unpaidOrders",number(reservations.get("unpaid_orders")),"pendingRewards",number(rewards.get("pending")),"failedRewards",failed,"unknownRewards",unknown,"reversingRewards",reversing,"manualReviewRewards",manual,"unfulfilledRewards",number(rewards.get("unfulfilled")),"obligations",number(rewards.get("obligations")),"reservedOrders",orders,"budgets",readBudgets(db,activity),"asOf",asOf.toString(),"completeness","COMPLETE","source",List.of("nx_promotion_reservation","nx_promotion_reward","nx_wallet_ledger","nx_promotion_device_receipt","nx_promotion_budget"));
    }
    public static Map<String,Object> summarizeActivities(List<Map<String,Object>> activities,Instant asOf){
        long reserved=0,issued=0,unresolved=0,unpaid=0,unfulfilled=0,active=0;var aggregate=new TreeMap<String,Map<String,Object>>();
        for(var activity:activities){var impact=map(activity.get("impact"));reserved+=number(impact.get("reserved"));issued+=number(impact.get("issued"));unresolved+=number(impact.get("unresolved"));unpaid+=number(impact.get("unpaidOrders"));unfulfilled+=number(impact.get("unfulfilledRewards"));if("ACTIVE".equals(activity.get("state")))active++;
            for(var budget:maps(impact.get("budgets"))){String key=text(budget.get("asset"))+":"+text(budget.get("productNo"));var sum=aggregate.computeIfAbsent(key,k->values("asset",budget.get("asset"),"productNo",budget.get("productNo")));
                for(String field:List.of("total","available","reserved","committed","issued","reversed","unrecoverable"))sum.put(field,money(decimal(sum.get(field)).add(decimal(budget.get(field)))));
            }
        }
        return values("activeActivities",active,"reserved",reserved,"issued",issued,"unresolved",unresolved,"unpaidOrders",unpaid,"unfulfilledRewards",unfulfilled,"budgets",new ArrayList<>(aggregate.values()),"asOf",asOf.toString(),"completeness","COMPLETE","source",List.of("nx_promotion_reservation","nx_promotion_reward","nx_wallet_ledger","nx_promotion_device_receipt","nx_promotion_budget"));
    }
    private static List<Map<String,Object>> readBudgets(PromotionMapper db,String activity){
        return db.list("SELECT * FROM nx_promotion_budget WHERE activity_id=? ORDER BY asset,product_no",activity).stream().map(b->values("asset",b.get("asset"),"productNo",text(b.get("product_no")).isEmpty()?null:b.get("product_no"),"total",money(decimal(b.get("total"))),"available",money(decimal(b.get("total")).subtract(decimal(b.get("reserved"))).subtract(decimal(b.get("committed"))).subtract(decimal(b.get("issued"))).add(decimal(b.get("reversed")))),"reserved",money(decimal(b.get("reserved"))),"committed",money(decimal(b.get("committed"))),"issued",money(decimal(b.get("issued"))),"reversed",money(decimal(b.get("reversed"))),"unrecoverable",money(decimal(b.get("unrecoverable"))))).toList();
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ,rollbackFor=Exception.class)
    public Map<String,Object> metrics(String activity,Long version,String from,String to,String timezone){
        return metrics(activity,version,from,to,timezone,"SKU",null,null,20);
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ,rollbackFor=Exception.class)
    public Map<String,Object> metrics(String activity,Long version,String from,String to,String timezone,String groupBy,String querySnapshot,String cursor,int limit){
        long actor=PromotionAdminService.actor();if(!PromotionAdminService.has("growth_promotion_metrics_read"))throw new BizException(403,"PROMOTION_PERMISSION_DENIED");
        if(!Set.of("SKU","TEMPLATE","BENEFICIARY").contains(text(groupBy))||limit<1||limit>100)throw new BizException(422,"PROMOTION_REPORT_GROUP_INVALID");
        if(cursor!=null&&!cursor.matches("0|[1-9][0-9]*"))throw new BizException(422,"PROMOTION_REPORT_CURSOR_INVALID");
        long offset=cursor==null?0:number(cursor);if(offset<0)throw new BizException(422,"PROMOTION_REPORT_CURSOR_INVALID");Instant start,end;
        try{start=Instant.parse(from);end=Instant.parse(to);ZoneId.of(timezone);}catch(RuntimeException e){throw new BizException(422,"PROMOTION_REPORT_WINDOW_INVALID");}
        if(!end.isAfter(start))throw new BizException(422,"PROMOTION_REPORT_WINDOW_INVALID");
        var query=values("activityId",activity,"version",version,"from",start.toString(),"to",end.toString(),"timezone",timezone);
        if(querySnapshot!=null){
            validator.validate("Id",querySnapshot);var saved=db.requiredRow("SELECT * FROM nx_promotion_report_snapshot WHERE snapshot_id=? AND activity_id=? AND actor_id=?",querySnapshot,activity,actor);
            require(instant(saved.get("expires_at")).isAfter(Instant.now()),"PROMOTION_REPORT_SNAPSHOT_EXPIRED");
            require(hash(query).equals(hash(parse(saved.get("query_json")))),"PROMOTION_REPORT_QUERY_CHANGED");
            var manifest=parse(saved.get("row_manifest_json"));require(manifest.containsKey("groupings"),"PROMOTION_REPORT_SNAPSHOT_VERSION_UNSUPPORTED");
            require(parse(saved.get("metrics_json")).containsKey("salesBySku"),"PROMOTION_REPORT_SNAPSHOT_VERSION_UNSUPPORTED");
            return breakdownPage(parse(saved.get("metrics_json")),map(manifest.get("groupings")),groupBy,offset,limit);
        }
        if(cursor!=null)throw new BizException(422,"PROMOTION_REPORT_SNAPSHOT_REQUIRED");
        db.activity(activity,false);if(version!=null)db.version(activity,version,false);Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);String snapshot=id("PMS");
        var orders=db.list("""
            SELECT DISTINCT o.order_no,o.user_id,o.amount_usdt,o.payment_status,o.order_status,o.paid_at
              FROM nx_order o JOIN nx_user u ON u.id=o.user_id
             WHERE o.is_deleted=0 AND u.is_deleted=0 AND u.sandbox=0 AND o.paid_at>=? AND o.paid_at<? AND o.paid_at<=?
               AND EXISTS(SELECT 1 FROM nx_promotion_reservation r JOIN nx_promotion p ON p.activity_id=r.activity_id
                           WHERE r.order_no=o.order_no AND r.activity_id=? AND (? IS NULL OR r.version=?) AND p.fixture_run_id='')
             ORDER BY o.order_no
            """,timestamp(start),timestamp(end),timestamp(now),activity,version,version);
        var rewards=db.list("""
            SELECT r.*,s.asset,s.amount,s.product_no FROM nx_promotion_reward r
              JOIN nx_promotion_reservation s ON s.reservation_id=r.reservation_id JOIN nx_promotion p ON p.activity_id=r.activity_id
              JOIN nx_user u ON u.id=r.beneficiary_id
             WHERE r.activity_id=? AND (? IS NULL OR r.version=?) AND r.created_at>=? AND r.created_at<? AND r.created_at<=?
               AND p.fixture_run_id='' AND u.sandbox=0 AND u.is_deleted=0 ORDER BY r.obligation_id
            """,activity,version,version,timestamp(start),timestamp(end),timestamp(now));
        var createdOrders=db.list("""
            SELECT DISTINCT o.order_no,o.created_at FROM nx_order o JOIN nx_user u ON u.id=o.user_id
            WHERE o.is_deleted=0 AND u.is_deleted=0 AND u.sandbox=0 AND o.created_at>=? AND o.created_at<? AND o.created_at<=?
              AND EXISTS(SELECT 1 FROM nx_promotion_reservation r JOIN nx_promotion p ON p.activity_id=r.activity_id
                         WHERE r.order_no=o.order_no AND r.activity_id=? AND (? IS NULL OR r.version=?) AND p.fixture_run_id='')
            ORDER BY o.order_no
            """,timestamp(start),timestamp(end),timestamp(now),activity,version,version);
        long refundedOrders=0;BigDecimal gross=BigDecimal.ZERO,refund=BigDecimal.ZERO;Set<Long> first=new HashSet<>(),invited=new HashSet<>();
        List<Map<String,Object>> manifestOrders=new ArrayList<>();var salesBySku=new TreeMap<String,Map<String,Object>>();
        for(var order:orders){BigDecimal amount=decimal(order.get("amount_usdt"));gross=gross.add(amount);
            var repayments=db.list("SELECT DISTINCT l.biz_no,l.amount FROM nx_promotion_refund_hold h JOIN nx_wallet_ledger l ON l.biz_no=h.refund_ledger_biz_no AND l.user_id=? AND l.biz_type='ORDER_REFUND' AND l.direction='IN' AND l.status='SUCCESS' WHERE h.order_no=? AND h.status='EXECUTED' AND l.created_at<=?",order.get("user_id"),order.get("order_no"),timestamp(now));
            BigDecimal returned=repayments.stream().map(r->decimal(r.get("amount"))).reduce(BigDecimal.ZERO,BigDecimal::add);refund=refund.add(returned);
            if(returned.signum()>0)refundedOrders++;
            var sold=orderSales(order,amount,returned);
            for(var item:sold){var total=salesBySku.computeIfAbsent(text(item.get("purchaseProductNo")),key->values("purchaseProductNo",key,"productName",item.get("productName"),"paidOrders",0L));
                total.put("paidOrders",number(total.get("paidOrders"))+1);
                for(String measure:List.of("grossPaidUsdt","refundUsdt","netReceivedUsdt"))total.put(measure,money(decimal(total.get(measure)).add(decimal(item.get(measure)))));
            }
            manifestOrders.add(values("orderNo",order.get("order_no"),"paidAt",instant(order.get("paid_at")).toString(),"grossPaidUsdt",money(amount),"refundUsdt",money(returned),"netReceivedUsdt",money(amount.subtract(returned)),"items",sold));
            if(!Set.of("REFUNDED","CANCELLED").contains(text(order.get("order_status")))&&"PAID".equals(order.get("payment_status"))){
                for(var reservation:db.list("SELECT snapshot_json FROM nx_promotion_reservation WHERE order_no=? AND activity_id=? AND status='COMMITTED' AND (? IS NULL OR version=?)",order.get("order_no"),activity,version,version)){
                    var promise=parse(reservation.get("snapshot_json"));var contract=map(promise.get("contract"));String template=text(contract.get("template"));
                    if(Set.of("FIRST_PURCHASE","DIRECT_REFERRAL").contains(template))first.add(number(order.get("user_id")));
                    if("DIRECT_REFERRAL".equals(template))invited.add(number(order.get("user_id")));
                }
            }
        }
        var manifestRewards=rewardFacts(rewards,now);var groupings=groupRewards(manifestRewards);
        List<Map<String,Object>> assets=new ArrayList<>();long gifts=0;
        for(String asset:List.of("USDT","NEX")){
            var total=values("asset",asset);for(String measure:List.of("issued","pending","reversed","unrecoverable")){
                BigDecimal amount=BigDecimal.ZERO;for(var fact:manifestRewards)if(asset.equals(fact.get("asset")))amount=amount.add(decimal(fact.get(measure)));total.put(measure,money(amount));
            }assets.add(total);
        }
        for(var fact:manifestRewards)if("DEVICE".equals(fact.get("asset")))gifts+=decimal(fact.get("issued")).longValueExact();
        var attempts=db.list("""
            SELECT a.*,s.asset FROM nx_promotion_reward_attempt a JOIN nx_promotion_reward r ON r.obligation_id=a.obligation_id
              JOIN nx_promotion_reservation s ON s.reservation_id=r.reservation_id JOIN nx_promotion p ON p.activity_id=r.activity_id
              JOIN nx_user u ON u.id=r.beneficiary_id
             WHERE r.activity_id=? AND (? IS NULL OR r.version=?) AND a.started_at>=? AND a.started_at<? AND a.started_at<=?
               AND p.fixture_run_id='' AND u.sandbox=0 AND u.is_deleted=0 ORDER BY a.started_at,a.attempt_id
            """,activity,version,version,timestamp(start),timestamp(end),timestamp(now));
        var failure=failureMetrics(attempts,now);var recovery=recoveryMetrics(attempts,now);
        long quoteCount=db.count("SELECT COUNT(*) FROM nx_promotion_quote q JOIN nx_user u ON u.id=q.user_id JOIN nx_promotion p ON p.activity_id=JSON_UNQUOTE(JSON_EXTRACT(q.quote_json,'$.public.activityId')) WHERE p.activity_id=? AND (? IS NULL OR JSON_EXTRACT(q.quote_json,'$.public.activityVersion')=?) AND q.created_at>=? AND q.created_at<? AND q.created_at<=? AND p.fixture_run_id='' AND u.sandbox=0 AND u.is_deleted=0",activity,version,version,timestamp(start),timestamp(end),timestamp(now));
        var result=values("activityId",activity,"version",version,"from",from,"to",to,"timezone",timezone,"querySnapshot",snapshot,"asOf",now.toString(),
            "source",List.of("nx_order","nx_order_item","nx_promotion_reservation","nx_wallet_ledger","nx_promotion_device_receipt","nx_promotion_reward_attempt"),"completeness","UNAVAILABLE","impressions",null,"quotes",quoteCount,"orders",createdOrders.size(),"paidOrders",orders.size(),"refundedOrders",refundedOrders,
            "grossPaidUsdt",money(gross),"refundUsdt",money(refund),"netReceivedUsdt",money(gross.subtract(refund)),"externalInflowUsdt",null,"externalOutflowUsdt",null,"assets",assets,"giftDevices",gifts,"giftCostUsdt",null,
            "budgets",budgets(activity),"testDataExcluded",true,"effectiveFirstPurchasePeople",people(first.size(),now),"directInvitedQualifiedPurchasers",people(invited.size(),now),"rewardFailureMetrics",failure,"recoveryDuration",recovery,
            "acquisitionCostUsdt",null,"conversionRate",null,"roi",null,"orderWindowBasis","CREATED_AT","paidWindowBasis","PAID_AT_REFUNDS_AS_OF");
        result.put("breakdown",breakdown(groupings,groupBy,0,Integer.MAX_VALUE));
        result.put("salesBySku",new ArrayList<>(salesBySku.values()));
        validator.validate("Metrics",result);
        var manifest=values("orders",manifestOrders,"createdOrders",createdOrders.stream().map(o->values("orderNo",o.get("order_no"),"createdAt",instant(o.get("created_at")).toString())).toList(),"rewards",manifestRewards,"groupings",groupings);
        var watermark=values("asOf",now.toString(),"orderCount",orders.size(),"rewardCount",rewards.size(),"attemptCount",attempts.size(),"manifestHash",hash(manifest));
        changed(db.write("INSERT INTO nx_promotion_report_snapshot(snapshot_id,activity_id,actor_id,query_json,metrics_json,source_watermarks_json,row_manifest_json,expires_at) VALUES(?,?,?,?,?,?,?,?)",snapshot,activity,actor,json(query),json(result),json(watermark),json(manifest),timestamp(now.plus(Duration.ofHours(24)))));
        return breakdownPage(result,groupings,groupBy,offset,limit);
    }
    private List<Map<String,Object>> orderSales(Map<String,Object> order,BigDecimal paid,BigDecimal refunded){
        require(refunded.signum()==0||refunded.compareTo(paid)==0,"PROMOTION_REPORT_REFUND_AMOUNT_MISMATCH");
        var reservation=db.requiredRow("SELECT snapshot_json FROM nx_promotion_reservation WHERE order_no=? ORDER BY reservation_id LIMIT 1",order.get("order_no"));
        var quoted=maps(map(parse(reservation.get("snapshot_json")).get("public")).get("items"));
        var lines=db.list("SELECT id,product_no,product_name,quantity FROM nx_order_item WHERE order_no=? ORDER BY sort_order,id",order.get("order_no"));
        require(lines.size()==quoted.size()&&!lines.isEmpty(),"PROMOTION_REPORT_ORDER_ITEMS_MISMATCH");
        var result=new ArrayList<Map<String,Object>>();var products=new HashSet<String>();BigDecimal sum=BigDecimal.ZERO;
        for(var line:lines){String product=text(line.get("product_no"));var matches=quoted.stream().filter(item->product.equals(item.get("productNo"))).toList();
            require(products.add(product)&&matches.size()==1&&number(line.get("quantity"))==number(matches.get(0).get("quantity")),"PROMOTION_REPORT_ORDER_ITEMS_MISMATCH");
            // Canonical line_amount_usdt is pre-discount; use the frozen quote's exact allocation.
            BigDecimal amount=amount(matches.get(0).get("payableUsdt"));sum=sum.add(amount);BigDecimal returned=refunded.signum()==0?BigDecimal.ZERO:amount;
            result.add(values("lineId",text(line.get("id")),"purchaseProductNo",product,"productName",text(line.get("product_name")),"grossPaidUsdt",money(amount),"refundUsdt",money(returned),"netReceivedUsdt",money(amount.subtract(returned))));
        }
        require(sum.compareTo(paid)==0,"PROMOTION_REPORT_ORDER_AMOUNT_MISMATCH");return result;
    }
    private List<Map<String,Object>> rewardFacts(List<Map<String,Object>> rewards,Instant asOf){
        var result=new ArrayList<Map<String,Object>>();
        for(var reward:rewards){
            var promise=parse(reward.get("snapshot_json"));var award=map(promise.get("award"));String asset=text(reward.get("asset"));
            BigDecimal issued=BigDecimal.ZERO,reversed=BigDecimal.ZERO,loss=BigDecimal.ZERO;
            if("DEVICE".equals(asset))issued=BigDecimal.valueOf(db.count("SELECT COUNT(*) FROM nx_promotion_device_receipt x JOIN nx_user_device d ON d.id=x.device_id WHERE x.obligation_id=? AND d.source_channel='PROMOTION_GIFT' AND d.source_order_no=? AND d.user_id=? AND x.created_at<=?",reward.get("obligation_id"),reward.get("order_no"),reward.get("beneficiary_id"),timestamp(asOf)));
            else{var receipt=db.one("SELECT amount FROM nx_wallet_ledger WHERE biz_no=? AND user_id=? AND asset=? AND direction='IN' AND status='SUCCESS' AND created_at<=?",reward.get("original_ledger_no"),reward.get("beneficiary_id"),asset,timestamp(asOf));if(receipt!=null)issued=decimal(receipt.get("amount"));}
            for(var reversal:db.list("SELECT recovered,outstanding FROM nx_promotion_reversal WHERE obligation_id=? AND created_at<=?",reward.get("obligation_id"),timestamp(asOf))){reversed=reversed.add(decimal(reversal.get("recovered")));loss=loss.add(decimal(reversal.get("outstanding")));}
            BigDecimal amount=decimal(reward.get("amount"));String state=text(reward.get("status"));
            BigDecimal pending=Set.of("ISSUED","CANCELLED","REVERSED").contains(state)?BigDecimal.ZERO:amount.subtract(issued).max(BigDecimal.ZERO);
            result.add(values("obligationId",reward.get("obligation_id"),"orderNo",reward.get("order_no"),"version",number(reward.get("version")),
                "purchaseProductNo",award.get("productNo"),"template",map(promise.get("contract")).get("template"),"beneficiaryId",text(reward.get("beneficiary_id")),"beneficiaryRole",reward.get("beneficiary_role"),
                "asset",asset,"giftProductNo","DEVICE".equals(asset)?reward.get("product_no"):null,"amount",money(amount),"state",state,"revision",reward.get("revision"),
                "issued",money(issued),"pending",money(pending),"reversed",money(reversed),"unrecoverable",money(loss),"cancelled",money("CANCELLED".equals(state)?amount:BigDecimal.ZERO)));
        }
        return result;
    }
    static Map<String,Object> groupRewards(List<Map<String,Object>> facts){
        var groupings=new LinkedHashMap<String,Object>();
        for(String groupBy:List.of("SKU","TEMPLATE","BENEFICIARY")){
            var groups=new TreeMap<String,List<Map<String,Object>>>();
            for(var fact:facts){String dimension=switch(groupBy){case "SKU"->text(fact.get("purchaseProductNo"));case "TEMPLATE"->text(fact.get("template"));default->text(fact.get("beneficiaryRole"))+":"+text(fact.get("beneficiaryId"));};
                String key=json(List.of(dimension,text(fact.get("asset")),text(fact.get("giftProductNo"))));groups.computeIfAbsent(key,k->new ArrayList<>()).add(fact);
            }
            var rows=new ArrayList<Map<String,Object>>();
            for(var entry:groups.entrySet()){
                var members=entry.getValue();var first=members.get(0);var row=values("groupKey",sha256(entry.getKey()),"purchaseProductNo","SKU".equals(groupBy)?first.get("purchaseProductNo"):null,"template","TEMPLATE".equals(groupBy)?first.get("template"):null,
                    "beneficiaryId","BENEFICIARY".equals(groupBy)?first.get("beneficiaryId"):null,"beneficiaryRole","BENEFICIARY".equals(groupBy)?first.get("beneficiaryRole"):null,"asset",first.get("asset"),"giftProductNo",first.get("giftProductNo"),
                    "obligations",members.size(),"orders",members.stream().map(r->r.get("orderNo")).distinct().count(),"beneficiaries",members.stream().map(r->r.get("beneficiaryId")).distinct().count());
                for(String measure:List.of("amount","issued","pending","reversed","unrecoverable","cancelled")){BigDecimal sum=BigDecimal.ZERO;for(var member:members)sum=sum.add(decimal(member.get(measure)));row.put(measure,money(sum));}
                rows.add(row);
            }groupings.put(groupBy,rows);
        }return groupings;
    }
    private Map<String,Object> breakdown(Map<String,Object> groupings,String groupBy,long offset,int limit){
        var rows=maps(groupings.get(groupBy));if(offset>rows.size())throw new BizException(422,"PROMOTION_REPORT_CURSOR_INVALID");int end=(int)Math.min(rows.size(),offset+(long)limit);boolean more=end<rows.size();
        return values("groupBy",groupBy,"rows",rows.subList((int)offset,end),"total",rows.size(),"nextCursor",more?String.valueOf(end):null,"hasMore",more,"rewardWindowBasis","OBLIGATION_CREATED_AT_OUTCOMES_AS_OF");
    }
    private Map<String,Object> breakdownPage(Map<String,Object> metrics,Map<String,Object> groupings,String groupBy,long offset,int limit){
        var page=copy(metrics);page.put("breakdown",breakdown(groupings,groupBy,offset,limit));validator.validate("Metrics",page);return page;
    }
    private Map<String,Object> people(long count,Instant now){return values("value",count,"unit","PEOPLE","completeness","COMPLETE","source",List.of("nx_order","nx_promotion_reservation.snapshot_json"),"asOf",now.toString());}
    private static boolean failed(Map<String,Object> a){return Set.of("RETRYABLE_FAILED","OUTCOME_UNKNOWN","MANUAL_REVIEW").contains(text(a.get("status")));}
    private Map<String,Object> failureMetrics(List<Map<String,Object>> attempts,Instant now){
        Set<String> unique=new HashSet<>();Map<String,Set<String>> groups=new TreeMap<>();
        for(var a:attempts)if(failed(a)){String obligation=text(a.get("obligation_id"));unique.add(obligation);String action=text(a.get("action"));String stage=action.contains("REVERS")?"REVERSAL":action.contains("RECON")||"RESOLVE".equals(action)?"RECONCILIATION":"ISSUANCE";
            var e=parse(a.get("evidence_json"));String reason=text(e.get("failure"));if(reason.isEmpty())reason=text(a.get("status"));if(reason.length()>96)reason=reason.substring(0,96);
            groups.computeIfAbsent(stage+"|"+a.get("asset")+"|"+reason,k->new HashSet<>()).add(obligation);}
        var breakdown=new ArrayList<Map<String,Object>>();groups.forEach((key,ids)->{String[] p=key.split("\\|",3);breakdown.add(values("stage",p[0],"asset",p[1],"reasonCode",p[2],"obligations",ids.size()));});
        return values("value",unique.size(),"unit","DISTINCT_OBLIGATIONS","breakdown",breakdown,"completeness","COMPLETE","source",List.of("nx_promotion_reward_attempt"),"asOf",now.toString());
    }
    private Map<String,Object> recoveryMetrics(List<Map<String,Object>> attempts,Instant now){
        Map<String,Instant> starts=new LinkedHashMap<>();for(var a:attempts)if(failed(a))starts.putIfAbsent(text(a.get("obligation_id")),instant(a.get("started_at")));
        long count=0;BigDecimal seconds=BigDecimal.ZERO;
        for(var e:starts.entrySet()){
            var original=db.one("SELECT MIN(started_at) first_failure FROM nx_promotion_reward_attempt WHERE obligation_id=? AND status IN ('RETRYABLE_FAILED','OUTCOME_UNKNOWN','MANUAL_REVIEW')",e.getKey());
            if(original==null||original.get("first_failure")==null||!instant(original.get("first_failure")).equals(e.getValue()))continue;
            var terminal=db.one("SELECT MIN(finished_at) resolved_at FROM nx_promotion_reward_attempt WHERE obligation_id=? AND status IN ('ISSUED','CANCELLED','REVERSED') AND finished_at>=? AND finished_at<=?",e.getKey(),timestamp(e.getValue()),timestamp(now));
            if(terminal!=null&&terminal.get("resolved_at")!=null){seconds=seconds.add(BigDecimal.valueOf(Duration.between(e.getValue(),instant(terminal.get("resolved_at"))).toMillis(),3));count++;}
        }
        return values("value",count==0?null:seconds.divide(BigDecimal.valueOf(count),3,RoundingMode.HALF_UP),"unit","SECONDS","statistic","MEAN","sampleCount",count,"completeness","COMPLETE","source",List.of("nx_promotion_reward_attempt"),"asOf",now.toString());
    }
    public List<Map<String,Object>> budgets(String activity){return db.list("SELECT * FROM nx_promotion_budget WHERE activity_id=? ORDER BY asset,product_no",activity).stream().map(b->values("asset",b.get("asset"),"productNo",text(b.get("product_no")).isEmpty()?null:b.get("product_no"),"total",money(decimal(b.get("total"))),"available",money(decimal(b.get("total")).subtract(decimal(b.get("reserved"))).subtract(decimal(b.get("committed"))).subtract(decimal(b.get("issued"))).add(decimal(b.get("reversed")))),"reserved",money(decimal(b.get("reserved"))),"committed",money(decimal(b.get("committed"))),"issued",money(decimal(b.get("issued"))),"reversed",money(decimal(b.get("reversed"))),"unrecoverable",money(decimal(b.get("unrecoverable"))))).toList();}
    public Map<String,Object> createExport(String activity,Map<String,Object> request){
        validator.validate("ExportInput",request);long actor=PromotionAdminService.actor();var snapshot=db.requiredRow("SELECT * FROM nx_promotion_report_snapshot WHERE snapshot_id=? AND activity_id=? AND actor_id=? FOR UPDATE",request.get("querySnapshot"),activity,actor);
        require(instant(snapshot.get("expires_at")).isAfter(Instant.now()),"PROMOTION_REPORT_SNAPSHOT_EXPIRED");String id=id("PX");
        changed(db.write("INSERT INTO nx_promotion_export_job(export_id,snapshot_id,actor_id,status) VALUES(?,?,?,'PROCESSING')",id,snapshot.get("snapshot_id"),actor));return PromotionAdminService.resource("EXPORT",id,null);
    }
    public Map<String,Object> export(String id,boolean download){
        long actor=PromotionAdminService.actor();var row=db.requiredRow("SELECT j.*,s.expires_at snapshot_expires FROM nx_promotion_export_job j JOIN nx_promotion_report_snapshot s ON s.snapshot_id=j.snapshot_id WHERE j.export_id=? AND j.actor_id=? AND s.actor_id=?",id,actor,actor);
        String state=text(row.get("status"));if(!instant(row.get("snapshot_expires")).isAfter(Instant.now())||row.get("expires_at")!=null&&!instant(row.get("expires_at")).isAfter(Instant.now()))state="EXPIRED";
        String url=null;if(download){require("READY".equals(state),"PROMOTION_EXPORT_NOT_READY");if(!storage.exists(text(row.get("storage_ref"))))throw new BizException(503,"PROMOTION_EXPORT_STORAGE_UNAVAILABLE");url=storage.presignGet(text(row.get("storage_ref")),Duration.ofMinutes(5));
            audit.recordRequired(AuditLogWriteRequest.builder().action("promotionReportDownloaded").resourceType("PROMOTION_EXPORT").resourceId(id).actorId(actor).actorType("ADMIN").result("SUCCESS").detail(values("snapshot",row.get("snapshot_id"),"rowCount",row.get("row_count"))).build());}
        return values("exportId",id,"querySnapshot",row.get("snapshot_id"),"state",state,"rowCount",row.get("row_count"),"expiresAt",row.get("expires_at")==null?null:instant(row.get("expires_at")).toString(),"downloadUrl",url,"error","FAILED".equals(state)?values("code",503,"message",row.get("failure_reason"),"data",null):null);
    }
    @Scheduled(fixedDelayString="${nexion.promotion.export-delay-ms:5000}")
    public void generateExports(){
        for(var job:db.list("SELECT j.*,s.metrics_json,s.row_manifest_json,s.source_watermarks_json,s.expires_at snapshot_expires FROM nx_promotion_export_job j JOIN nx_promotion_report_snapshot s ON s.snapshot_id=j.snapshot_id WHERE j.status='PROCESSING' ORDER BY j.created_at LIMIT 5")){
            String id=text(job.get("export_id"));
            if(!instant(job.get("snapshot_expires")).isAfter(Instant.now())){db.write("UPDATE nx_promotion_export_job SET status='EXPIRED' WHERE export_id=? AND status='PROCESSING'",id);continue;}
            try{
                var manifest=parse(job.get("row_manifest_json"));long rows=maps(manifest.get("orders")).size()+maps(manifest.get("rewards")).size()+maps(manifest.getOrDefault("createdOrders",List.of())).size();
                if(manifest.containsKey("groupings"))for(Object grouping:map(manifest.get("groupings")).values())rows+=maps(grouping).size();
                String payload=json(values("metrics",parse(job.get("metrics_json")),"watermarks",parse(job.get("source_watermarks_json")),"rows",manifest));byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);String key="promotion-reports/"+id+".json";
                storage.put(key,"application/json",new ByteArrayInputStream(bytes),bytes.length);if(!storage.exists(key))throw new BizException(503,"PROMOTION_EXPORT_STORAGE_UNAVAILABLE");
                changed(db.write("UPDATE nx_promotion_export_job SET status='READY',storage_ref=?,row_count=?,content_hash=?,expires_at=? WHERE export_id=? AND status='PROCESSING'",key,rows,sha256(payload),job.get("snapshot_expires"),id));
            }catch(RuntimeException failure){db.write("UPDATE nx_promotion_export_job SET status='FAILED',failure_reason='PROMOTION_EXPORT_STORAGE_UNAVAILABLE' WHERE export_id=? AND status='PROCESSING'",id);}
        }
    }
}
