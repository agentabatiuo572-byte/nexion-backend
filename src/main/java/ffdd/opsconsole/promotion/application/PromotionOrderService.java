package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.domain.PromotionRules;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

/** Joins the authoritative commerce transaction; never creates or pays an order itself. */
@Service
@RequiredArgsConstructor
public class PromotionOrderService {
    private final PromotionMapper db;
    private final PromotionQuoteService quotes;
    private final PromotionEvaluationService evaluator;
    private final PromotionPolicyResolver policies;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    public record Selection(String productNo,int quantity) {}
    public record CreationPlan(String quoteId,Long buyerId,List<Long> participantIds,List<String> productNos,
                               Instant endsAt,Map<String,Object> snapshot) {}

    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public CreationPlan prepareCreate(Long buyerId,String quoteId,List<Selection> items,String voucherId,BigDecimal expectedAmount) {
        if(quoteId==null||quoteId.isBlank())return null;
        Map<String,Object> quote=db.requiredRow("SELECT * FROM nx_promotion_quote WHERE quote_id=?",quoteId);
        require(buyerId!=null&&number(quote.get("user_id"))==buyerId,"PROMOTION_QUOTE_OWNER_MISMATCH");
        require(instant(quote.get("expires_at")).isAfter(Instant.now()),"PROMOTION_QUOTE_EXPIRED");
        require(db.count("SELECT COUNT(*) FROM nx_promotion_order_receipt WHERE quote_id=?",quoteId)==0,"PROMOTION_QUOTE_ALREADY_USED");
        Map<String,Object> stored=parse(quote.get("quote_json")),pub=map(stored.get("public")),request=map(stored.get("request"));
        Map<String,Object> check=copy(stored);Map<String,Object> checkPublic=map(check.get("public"));checkPublic.put("quoteHash","0".repeat(64));check.put("public",checkPublic);
        require(hash(check).equals(quote.get("quote_hash"))&&quote.get("quote_hash").equals(pub.get("quoteHash")),"PROMOTION_QUOTE_CORRUPT");
        List<PromotionRules.Selection> selections=items.stream().map(i->new PromotionRules.Selection(i.productNo(),i.quantity())).toList();
        PromotionRules.selections(selections);require(items.size()<=8,"BUNDLE_PRODUCT_COUNT_INVALID");
        require(sameItems(selections,PromotionQuoteService.selections(request))&&text(voucherId).equals(text(request.get("voucherId"))),"PROMOTION_QUOTE_INPUT_CHANGED");
        if(expectedAmount!=null)require(expectedAmount.compareTo(amount(pub.get("amountUsdt")))==0,"PROMOTION_QUOTE_AMOUNT_CHANGED");
        TreeSet<Long> accounts=new TreeSet<>();accounts.add(buyerId);
        for(Map<String,Object> award:maps(stored.get("awards")))accounts.add(number(award.get("beneficiaryId")));
        for(Long id:accounts)require("ACTIVE".equals(db.user(id,true).get("status")),"PROMOTION_ACCOUNT_UNAVAILABLE");
        String activity=text(pub.get("activityId"));Map<String,Object> contract=map(stored.get("contract"));
        TreeSet<String> productNos=new TreeSet<>();items.forEach(i->productNos.add(i.productNo()));
        if(!activity.isEmpty()){
            Map<String,Object> root=db.activity(activity,true);
            require(Set.of("ACTIVE","SCHEDULED").contains(text(root.get("status")))&&number(root.get("active_version"))==number(pub.get("activityVersion")),"PROMOTION_QUOTE_VERSION_CHANGED");
            Map<String,Object> version=db.version(activity,number(pub.get("activityVersion")),true);
            require("PUBLISHED".equals(version.get("status"))&&hash(contract).equals(version.get("content_hash")),"PROMOTION_VERSION_INVALID");
            Instant now=Instant.now();require(!now.isBefore(instant(contract.get("startsAt")))&&now.isBefore(instant(contract.get("endsAt"))),"PROMOTION_ACTIVITY_CLOSED");
            policies.resolve(contract,true);
            lockCapacity(activity,accounts);
            require(((List<?>)request.get("clientCapabilities")).containsAll((List<?>)contract.get("minimumClientCapabilities")),"PROMOTION_CLIENT_UPDATE_REQUIRED");
            List<Map<String,Object>> current=evaluator.evaluateCurrent(activity,contract,map(stored.get("policies")),buyerId,selections,now,null).stream().map(PromotionEvaluationService::award).toList();
            require(containsAwards(current,maps(stored.get("awards"))),"PROMOTION_ELIGIBILITY_CHANGED");
            stored.put("qualification",evaluator.qualificationSnapshot(contract,map(stored.get("policies")),buyerId,now));
            for(Map<String,Object> award:maps(stored.get("awards"))){Map<String,Object> r=map(award.get("reward"));if("DEVICE".equals(r.get("type")))productNos.add(text(r.get("giftProductNo")));}
        }
        // One ordered lock set includes hidden gift SKUs and purchased SKUs.
        Map<String,Object> productSnapshots=new LinkedHashMap<>(),lockedProducts=new LinkedHashMap<>();
        for(String product:productNos){
            Map<String,Object> p=db.product(product,true);
            Map<String,Object> sku=db.one("SELECT * FROM nx_admin_device_sku WHERE sku_id=? AND is_deleted=0 FOR UPDATE",product);
            lockedProducts.put(product,values("product",p,"sku",sku));
            Map<String,Object> frozenProduct=new LinkedHashMap<>();
            for(String field:List.of("id","name","status","product_type","tier","generation","gpu_model","vram_total_gb"))frozenProduct.put(field,p.get(field));
            for(String field:List.of("price_usdt","hashrate","estimated_daily_usdt","daily_nex"))frozenProduct.put(field,money(decimal(p.get(field))));
            productSnapshots.put(product,values("product",frozenProduct,"sku",sku));
        }
        if(!activity.isEmpty())policies.verifyLockedProducts(map(stored.get("policies")),lockedProducts);
        Map<String,Object> currentPrice=quotes.currentPrices(buyerId,selections,text(voucherId));
        for(String key:List.of("amountUsdt","subtotalUsdt","discountUsdt","bundlePolicyVersion"))
            require(text(currentPrice.get(key)).equals(text(pub.get(key))),"PROMOTION_QUOTE_PRICE_CHANGED");
        if(!activity.isEmpty())require(quotes.currentlyAvailable(activity,contract,maps(stored.get("awards"))),"PROMOTION_CAPACITY_UNAVAILABLE");
        Map<Long,Long> addedSlots=new HashMap<>();
        for(Selection item:items)if(!"SHARE".equalsIgnoreCase(text(map(map(productSnapshots.get(item.productNo())).get("product")).get("product_type"))))
            addedSlots.merge(buyerId,(long)item.quantity(),Long::sum);
        for(Map<String,Object> award:maps(stored.get("awards"))){Map<String,Object> r=map(award.get("reward"));
            if("DEVICE".equals(r.get("type")))addedSlots.merge(number(award.get("beneficiaryId")),number(r.get("quantity")),Long::sum);}
        for(var additional:addedSlots.entrySet())require(db.occupiedDeviceSlots(additional.getKey())+additional.getValue()<=db.deviceSlotCap(),"PROMOTION_DEVICE_CAPACITY_UNAVAILABLE");
        stored.put("products",productSnapshots);
        return new CreationPlan(quoteId,buyerId,List.copyOf(accounts),List.copyOf(productNos),
            activity.isEmpty()?Instant.MAX:instant(contract.get("endsAt")),stored);
    }
    private boolean sameItems(List<PromotionRules.Selection> a,List<PromotionRules.Selection> b){
        return a.stream().sorted(Comparator.comparing(PromotionRules.Selection::productNo)).toList().equals(b.stream().sorted(Comparator.comparing(PromotionRules.Selection::productNo)).toList());
    }
    private boolean containsAwards(List<Map<String,Object>> qualified,List<Map<String,Object>> promised){
        Set<String> current=new HashSet<>();qualified.forEach(a->current.add(hash(a)));
        return promised.stream().allMatch(a->current.contains(hash(a)));
    }
    private void lockCapacity(String activity,Collection<Long> accounts){
        db.list("SELECT * FROM nx_promotion_budget WHERE activity_id=? ORDER BY asset,product_no FOR UPDATE",activity);
        for(Long account:accounts)db.list("SELECT * FROM nx_promotion_usage WHERE activity_id=? AND account_id=? ORDER BY beneficiary_role,rule_id FOR UPDATE",activity,account);
    }

    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public Map<String,Object> reserveCreatedOrder(CreationPlan plan,String orderNo,Instant orderDeadline) {
        if(plan==null)return Map.of();
        Map<String,Object> order=db.order(orderNo,false),pub=map(plan.snapshot().get("public"));
        require(number(order.get("user_id"))==plan.buyerId()&&"PENDING".equals(order.get("payment_status")),"PROMOTION_ORDER_INVALID");
        require(decimal(order.get("amount_usdt")).compareTo(amount(pub.get("amountUsdt")))==0,"PROMOTION_ORDER_AMOUNT_MISMATCH");
        List<Map<String,Object>> lines=db.list("SELECT * FROM nx_order_item WHERE order_no=? ORDER BY sort_order,id",orderNo);
        List<PromotionRules.Selection> actual=lines.stream().map(r->new PromotionRules.Selection(text(r.get("product_no")),Math.toIntExact(number(r.get("quantity"))))).toList();
        require(sameItems(actual,PromotionQuoteService.selections(map(plan.snapshot().get("request")))),"PROMOTION_ORDER_ITEMS_MISMATCH");
        Map<String,Map<String,Object>> byProduct=new HashMap<>();lines.forEach(r->byProduct.put(text(r.get("product_no")),r));
        for(Map<String,Object> quoted:maps(pub.get("items"))){
            Map<String,Object> line=byProduct.get(text(quoted.get("productNo")));
            require(line!=null&&decimal(line.get("unit_price_usdt")).compareTo(amount(quoted.get("unitPriceUsdt")))==0,"PROMOTION_ORDER_LINE_PRICE_MISMATCH");
        }
        Instant payBy=orderDeadline.isBefore(plan.endsAt())?orderDeadline:plan.endsAt();
        require(payBy.isAfter(Instant.now()),"PROMOTION_PAYMENT_WINDOW_CLOSED");
        String activity=text(pub.get("activityId"));
        List<Map<String,Object>> awards=maps(plan.snapshot().get("awards"));
        if(!activity.isEmpty()){
            require(db.reservations(orderNo,true).isEmpty(),"PROMOTION_ORDER_ALREADY_RESERVED");
            changed(db.write("UPDATE nx_promotion SET reserved_orders=reserved_orders+1,revision=revision+1,updated_at=NOW(6) WHERE activity_id=?",activity));
            Set<String> orderUses=new HashSet<>();
            for(Map<String,Object> award:awards){
                Map<String,Object> reward=map(award.get("reward"));String asset=text(reward.get("type")),gift="DEVICE".equals(asset)?text(reward.get("giftProductNo")):"";
                BigDecimal amount=PromotionQuoteService.rewardAmount(reward);long beneficiary=number(award.get("beneficiaryId"));
                String role=text(award.get("beneficiaryRole")),rule=text(award.get("ruleId"));
                changed(db.write("""
                    UPDATE nx_promotion_budget SET reserved=reserved+?,revision=revision+1
                    WHERE activity_id=? AND asset=? AND product_no=? AND total-reserved-committed-issued+reversed>=?
                    """,amount,activity,asset,gift,amount));
                if(!gift.isEmpty()&&!"UNLIMITED".equals(db.product(gift,false).get("inventory_mode")))
                    changed(db.write("UPDATE nx_product SET stock=stock-?,updated_at=NOW(6) WHERE product_no=? AND is_deleted=0 AND stock>=?",amount.intValueExact(),gift,amount.intValueExact()));
                usage(activity,beneficiary,role,rule,0,1);
                if(orderUses.add(beneficiary+":"+role))usage(activity,beneficiary,role,"",1,0);
                Map<String,Object> snapshot=copy(plan.snapshot());snapshot.put("award",award);snapshot.put("payBy",payBy.toString());
                changed(db.write("""
                    INSERT INTO nx_promotion_reservation(reservation_id,activity_id,version,order_no,order_line_id,buyer_id,beneficiary_id,
                      beneficiary_role,rule_id,reward_rule_id,unit_seq,asset,product_no,amount,snapshot_json,snapshot_hash,pay_by)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id("PR"),activity,number(pub.get("activityVersion")),orderNo,number(byProduct.get(text(award.get("productNo"))).get("id")),
                    plan.buyerId(),beneficiary,role,rule,award.get("rewardRuleId"),number(award.get("unitSeq")),asset,gift,amount,json(snapshot),hash(snapshot),timestamp(payBy)));
            }
            audit("PROMOTION_ORDER_RESERVED",orderNo,values("quoteId",plan.quoteId(),"awardCount",awards.size(),"payBy",payBy.toString()));
        }
        List<Map<String,Object>> expected=new ArrayList<>();
        for(Map<String,Object> reward:maps(pub.get("expectedRewards"))){
            Map<String,Object> value=copy(reward);value.put("lineId",text(byProduct.get(text(reward.get("lineId"))).get("id")));expected.add(value);
        }
        Map<String,Object> projection=values("promotionQuoteId",plan.quoteId(),"items",receiptItems(lines),"itemCount",lines.size(),"quantity",actual.stream().mapToInt(PromotionRules.Selection::quantity).sum(),"amountUsdt",pub.get("amountUsdt"),"payBy",payBy.toString(),"rewards",expected);
        changed(db.write("INSERT INTO nx_promotion_order_receipt(order_no,order_id,quote_id,buyer_id,projection_json,pay_by) VALUES(?,?,?,?,?,?)",
            orderNo,order.get("id"),plan.quoteId(),plan.buyerId(),json(projection),timestamp(payBy)));
        return projection;
    }
    private List<Map<String,Object>> receiptItems(List<Map<String,Object>> lines){
        return lines.stream().map(line->values("lineId",text(line.get("id")),"productNo",line.get("product_no"),"productName",text(line.get("product_name")),"quantity",Math.toIntExact(number(line.get("quantity"))))).toList();
    }
    private void usage(String activity,long user,String role,String rule,int orders,int groups){
        db.write("""
            INSERT INTO nx_promotion_usage(activity_id,account_id,beneficiary_role,rule_id,reserved_orders,reserved_groups)
            VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE reserved_orders=reserved_orders+VALUES(reserved_orders),
              reserved_groups=reserved_groups+VALUES(reserved_groups),revision=revision+1
            """,activity,user,role,rule,orders,groups);
    }
    public List<Long> participantsForOrder(String orderNo){
        Map<String,Object> order=db.order(orderNo,false);TreeSet<Long> ids=new TreeSet<>();ids.add(number(order.get("user_id")));
        db.reservations(orderNo,false).forEach(r->ids.add(number(r.get("beneficiary_id"))));return List.copyOf(ids);
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void lockOrderParticipants(String orderNo){
        List<Long> before=participantsForOrder(orderNo);for(Long id:before)db.user(id,true);
        require(before.equals(participantsForOrder(orderNo)),"PROMOTION_ORDER_PARTICIPANTS_CHANGED");
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void beforePay(Long buyerId,String orderNo){
        Map<String,Object> order=db.order(orderNo,false);require(buyerId!=null&&number(order.get("user_id"))==buyerId,"PROMOTION_ORDER_OWNER_MISMATCH");
        Map<String,Object> receipt=db.one("SELECT pay_by FROM nx_promotion_order_receipt WHERE order_no=? FOR UPDATE",orderNo);
        if(receipt!=null)require(instant(receipt.get("pay_by")).isAfter(Instant.now()),"PROMOTION_PAYMENT_WINDOW_CLOSED");
        List<Map<String,Object>> rows=db.reservations(orderNo,true);if(rows.isEmpty())return;
        require(!db.hasHold(orderNo),"PROMOTION_REFUND_HOLD");
        for(Map<String,Object> r:rows)require("RESERVED".equals(r.get("status"))&&instant(r.get("pay_by")).isAfter(Instant.now()),"PROMOTION_PAYMENT_WINDOW_CLOSED");
        Map<String,Object> snapshot=parse(rows.get(0).get("snapshot_json")),contract=map(snapshot.get("contract"));
        String activity=text(rows.get(0).get("activity_id"));db.activity(activity,true);lockCapacity(activity,participantsForOrder(orderNo));
        for(Long participant:participantsForOrder(orderNo))require("ACTIVE".equals(db.user(participant,true).get("status")),"PROMOTION_ACCOUNT_FROZEN");
        require(evaluator.firstPurchaseStillValid(contract,map(snapshot.get("policies")),buyerId,maps(snapshot.get("awards")),orderNo),"PROMOTION_PAYMENT_ELIGIBILITY_LOST");
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void afterPaid(Long buyerId,String orderNo){
        Map<String,Object> order=db.order(orderNo,false);require(number(order.get("user_id"))==buyerId&&"PAID".equals(order.get("payment_status"))&&order.get("paid_at")!=null,"PROMOTION_PAYMENT_NOT_CONFIRMED");
        List<Map<String,Object>> rows=db.reservations(orderNo,true);if(rows.isEmpty()||rows.stream().allMatch(r->"COMMITTED".equals(r.get("status"))))return;
        for(Map<String,Object> row:rows)require(instant(order.get("paid_at")).isBefore(instant(row.get("pay_by"))),"PROMOTION_PAYMENT_WINDOW_CLOSED");
        Set<String> activities=new TreeSet<>(),orderUses=new HashSet<>();
        for(Map<String,Object> r:rows){
            require("RESERVED".equals(r.get("status")),"PROMOTION_RESERVATION_NOT_PAYABLE");String activity=text(r.get("activity_id"));activities.add(activity);
            changed(db.write("UPDATE nx_promotion_budget SET reserved=reserved-?,committed=committed+?,revision=revision+1 WHERE activity_id=? AND asset=? AND product_no=? AND reserved>=?",
                r.get("amount"),r.get("amount"),activity,r.get("asset"),r.get("product_no"),r.get("amount")));
            moveUsage(r,false,orderUses);
            changed(db.write("UPDATE nx_promotion_reservation SET status='COMMITTED',revision=revision+1,updated_at=NOW(6) WHERE reservation_id=? AND status='RESERVED'",r.get("reservation_id")));
            String obligation=id("PO");
            changed(db.write("""
                INSERT INTO nx_promotion_reward(obligation_id,reservation_id,activity_id,version,order_no,order_line_id,beneficiary_id,
                 beneficiary_role,rule_id,reward_rule_id,unit_seq,snapshot_json,snapshot_hash,ready_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,obligation,r.get("reservation_id"),activity,r.get("version"),orderNo,r.get("order_line_id"),r.get("beneficiary_id"),
                r.get("beneficiary_role"),r.get("rule_id"),r.get("reward_rule_id"),r.get("unit_seq"),r.get("snapshot_json"),r.get("snapshot_hash"),order.get("paid_at")));
            outbox.publish("PROMOTION_REWARD",obligation,"promotion.reward.committed",values("obligationId",obligation,"orderNo",orderNo));
        }
        for(String activity:activities)changed(db.write("UPDATE nx_promotion SET reserved_orders=reserved_orders-1,used_orders=used_orders+1,revision=revision+1,updated_at=NOW(6) WHERE activity_id=? AND reserved_orders>0",activity));
        audit("PROMOTION_ORDER_COMMITTED",orderNo,values("obligations",rows.size()));
    }
    private void moveUsage(Map<String,Object> r,boolean release,Set<String> orderUses){
        changed(db.write("UPDATE nx_promotion_usage SET reserved_groups=reserved_groups-1,used_groups=used_groups+?,revision=revision+1 WHERE activity_id=? AND account_id=? AND beneficiary_role=? AND rule_id=? AND reserved_groups>0",
            release?0:1,r.get("activity_id"),r.get("beneficiary_id"),r.get("beneficiary_role"),r.get("rule_id")));
        String key=text(r.get("activity_id"))+":"+r.get("beneficiary_id")+":"+r.get("beneficiary_role");
        if(orderUses.add(key))changed(db.write("UPDATE nx_promotion_usage SET reserved_orders=reserved_orders-1,used_orders=used_orders+?,revision=revision+1 WHERE activity_id=? AND account_id=? AND beneficiary_role=? AND rule_id='' AND reserved_orders>0",
            release?0:1,r.get("activity_id"),r.get("beneficiary_id"),r.get("beneficiary_role")));
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void releaseUnpaid(String orderNo,String terminalReason){
        Map<String,Object> order=db.order(orderNo,false);require(order.get("paid_at")==null&&Set.of("CANCELLED","EXPIRED","FAILED").contains(text(order.get("order_status"))),"PROMOTION_ORDER_NOT_TERMINAL_UNPAID");
        List<Map<String,Object>> rows=db.reservations(orderNo,true);Set<String> activities=new TreeSet<>(),orderUses=new HashSet<>();
        for(Map<String,Object> r:rows)if("RESERVED".equals(r.get("status"))){
            activities.add(text(r.get("activity_id")));changed(db.write("UPDATE nx_promotion_budget SET reserved=reserved-?,revision=revision+1 WHERE activity_id=? AND asset=? AND product_no=? AND reserved>=?",r.get("amount"),r.get("activity_id"),r.get("asset"),r.get("product_no"),r.get("amount")));
            returnStock(r);moveUsage(r,true,orderUses);changed(db.write("UPDATE nx_promotion_reservation SET status='RELEASED',revision=revision+1,updated_at=NOW(6) WHERE reservation_id=? AND status='RESERVED'",r.get("reservation_id")));
        }
        for(String activity:activities)changed(db.write("UPDATE nx_promotion SET reserved_orders=reserved_orders-1,revision=revision+1,updated_at=NOW(6) WHERE activity_id=? AND reserved_orders>0",activity));
        if(!activities.isEmpty())audit("PROMOTION_ORDER_RELEASED",orderNo,values("reason",terminalReason));
    }
    public void returnStock(Map<String,Object> reservation){
        if("DEVICE".equals(reservation.get("asset"))){String product=text(reservation.get("product_no"));Map<String,Object> p=db.product(product,true);
            if(!"UNLIMITED".equals(p.get("inventory_mode")))changed(db.write("UPDATE nx_product SET stock=stock+?,updated_at=NOW(6) WHERE product_no=? AND is_deleted=0",decimal(reservation.get("amount")).intValueExact(),product));}
    }
    public Map<String,Object> orderProjection(Long userId,String orderNo){
        Map<String,Object> order=db.order(orderNo,false);require(userId!=null&&number(order.get("user_id"))==userId,"PROMOTION_ORDER_OWNER_MISMATCH");
        Map<String,Object> receipt=db.one("SELECT projection_json,buyer_id FROM nx_promotion_order_receipt WHERE order_no=?",orderNo);
        if(receipt!=null){
            require(number(receipt.get("buyer_id"))==userId,"PROMOTION_ORDER_OWNER_MISMATCH");var projection=parse(receipt.get("projection_json"));
            if(maps(projection.get("rewards")).stream().anyMatch(r->r.get("disclosure")==null||!map(r.get("disclosure")).containsKey("deviceName"))){
                var frozen=db.reservations(orderNo,false);require(!frozen.isEmpty(),"PROMOTION_POLICY_SNAPSHOT_MISSING");
                var snapshot=parse(frozen.get(0).get("snapshot_json"));
                var expected=maps(projection.get("rewards"));
                for(var reward:expected)reward.put("disclosure",PromotionPublicService.disclosure(db,map(snapshot.get("contract")),map(snapshot.get("policies")),map(reward.get("reward"))));
                projection.put("rewards",expected);
            }
            if(!projection.containsKey("items")||maps(projection.get("items")).stream().anyMatch(item->!item.containsKey("productName")))projection.put("items",receiptItems(db.list("SELECT * FROM nx_order_item WHERE order_no=? ORDER BY sort_order,id",orderNo)));
            projection.put("amountUsdt",money(decimal(order.get("amount_usdt"))));return projection;
        }
        List<Map<String,Object>> rows=db.reservations(orderNo,false);if(rows.isEmpty())return Map.of();
        Map<String,Object> snapshot=parse(rows.get(0).get("snapshot_json")),pub=map(snapshot.get("public"));
        var items=receiptItems(db.list("SELECT * FROM nx_order_item WHERE order_no=? ORDER BY sort_order,id",orderNo));
        var expected=maps(pub.get("expectedRewards"));
        for(var reward:expected){
            var item=items.stream().filter(line->text(line.get("productNo")).equals(text(reward.get("lineId")))).findFirst();
            require(item.isPresent(),"PROMOTION_ORDER_ITEMS_MISMATCH");reward.put("lineId",item.orElseThrow().get("lineId"));
            reward.put("disclosure",PromotionPublicService.disclosure(db,map(snapshot.get("contract")),map(snapshot.get("policies")),map(reward.get("reward"))));
        }
        return values("promotionQuoteId",pub.get("quoteId"),"amountUsdt",money(decimal(order.get("amount_usdt"))),"payBy",instant(rows.get(0).get("pay_by")).toString(),
            "items",items,"itemCount",pub.get("itemCount"),"quantity",pub.get("quantity"),"rewards",expected);
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void holdA2Refund(String operationId,String orderNo){
        Map<String,Object> ticket=refundTicket(operationId,orderNo);require("pending".equals(ticket.get("status")),"PROMOTION_REFUND_SOURCE_NOT_PENDING");
        if(db.reservations(orderNo,false).isEmpty())return;
        Map<String,Object> existing=db.one("SELECT * FROM nx_promotion_refund_hold WHERE source_type='A2_OPERATION' AND source_id=? FOR UPDATE",operationId);
        if(existing!=null){require(orderNo.equals(existing.get("order_no"))&&"HELD".equals(existing.get("status")),"PROMOTION_REFUND_HOLD_CONFLICT");return;}
        changed(db.write("INSERT INTO nx_promotion_refund_hold(refund_request_id,order_no,source_type,source_id,source_event_id,status,evidence_json,reason) VALUES(?,?,'A2_OPERATION',?,?,'HELD',?,?)",
            operationId,orderNo,operationId,operationId,json(values("operationId",operationId,"commandHash",hash(parse(ticket.get("command_json"))))),required(ticket.get("reason"),"reason")));
        audit("PROMOTION_REFUND_HELD",orderNo,values("operationId",operationId));
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void clearA2Refund(String operationId,String orderNo){
        Map<String,Object> ticket=refundTicket(operationId,orderNo);require(Set.of("rejected","withdrawn").contains(text(ticket.get("status"))),"PROMOTION_REFUND_SOURCE_NOT_RELEASED");
        Map<String,Object> row=db.one("SELECT * FROM nx_promotion_refund_hold WHERE source_type='A2_OPERATION' AND source_id=? FOR UPDATE",operationId);
        if(row==null||"RELEASED".equals(row.get("status")))return;
        require("HELD".equals(row.get("status")),"PROMOTION_REFUND_ALREADY_EXECUTED");
        changed(db.write("UPDATE nx_promotion_refund_hold SET status='RELEASED',revision=revision+1,updated_at=NOW(6) WHERE refund_request_id=? AND status='HELD'",row.get("refund_request_id")));
        audit("PROMOTION_REFUND_RELEASED",orderNo,values("operationId",operationId,"decision",ticket.get("status")));
    }
    private Map<String,Object> refundTicket(String operationId,String orderNo){
        Map<String,Object> ticket=db.requiredRow("SELECT * FROM nx_audit_operation_ticket WHERE operation_id=? AND is_deleted=0",operationId);
        Map<String,Object> command=parse(ticket.get("command_json"));require("e4_order_refund".equals(command.get("op"))&&orderNo.equals(map(command.get("params")).get("orderNo")),"PROMOTION_REFUND_SOURCE_MISMATCH");
        return ticket;
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void confirmE4Refund(String orderNo,String refundLedgerBizNo){
        Map<String,Object> order=db.order(orderNo,false);List<Map<String,Object>> rows=db.reservations(orderNo,true);if(rows.isEmpty())return;
        require("REFUNDED".equals(order.get("payment_status"))||"REFUNDED".equals(order.get("order_status")),"PROMOTION_REFUND_NOT_CONFIRMED");
        Map<String,Object> ledger=db.requiredRow("SELECT * FROM nx_wallet_ledger WHERE biz_no=? AND user_id=? AND biz_type='ORDER_REFUND' AND asset='USDT' AND direction='IN' AND status='SUCCESS'",refundLedgerBizNo,order.get("user_id"));
        require(decimal(ledger.get("amount")).compareTo(decimal(order.get("amount_usdt")))==0,"PROMOTION_REFUND_LEDGER_MISMATCH");
        String refundNo="E4:"+sha256(orderNo);
        List<Map<String,Object>> holds=db.list("SELECT * FROM nx_promotion_refund_hold WHERE order_no=? AND status IN ('HELD','OUTCOME_UNKNOWN','EXECUTED') ORDER BY refund_request_id FOR UPDATE",orderNo);
        if(holds.isEmpty())changed(db.write("INSERT INTO nx_promotion_refund_hold(refund_request_id,order_no,source_type,source_id,source_event_id,status,refund_no,refund_ledger_biz_no,evidence_json,reason) VALUES(?,?,'E4_REFUND',?,?,'EXECUTED',?,?,?,'Canonical E4 wallet refund confirmed')",
            refundNo,orderNo,orderNo,refundNo,refundNo,refundLedgerBizNo,json(values("orderNo",orderNo,"ledgerBizNo",refundLedgerBizNo))));
        else for(Map<String,Object> hold:holds)if(!"EXECUTED".equals(hold.get("status")))
            changed(db.write("UPDATE nx_promotion_refund_hold SET status='EXECUTED',refund_no=?,refund_ledger_biz_no=?,revision=revision+1,updated_at=NOW(6) WHERE refund_request_id=?",refundNo,refundLedgerBizNo,hold.get("refund_request_id")));
        for(Map<String,Object> r:rows){
            Map<String,Object> reward=db.one("SELECT * FROM nx_promotion_reward WHERE reservation_id=? FOR UPDATE",r.get("reservation_id"));if(reward==null)continue;
            String status=text(reward.get("status"));if(Set.of("PENDING","READY","RETRYABLE_FAILED").contains(status)){
                changed(db.write("UPDATE nx_promotion_reward SET status='CANCELLED',revision=revision+1,updated_at=NOW(6) WHERE obligation_id=?",reward.get("obligation_id")));
                changed(db.write("INSERT INTO nx_promotion_reward_attempt(attempt_id,obligation_id,command_id,action,status,evidence_json,started_at,finished_at) VALUES(?,?,?,'CANCEL','CANCELLED',?,NOW(6),NOW(6))",
                    id("PA"),reward.get("obligation_id"),refundNo,json(values("basisType","WHOLE_ORDER_REFUND","refundNo",refundNo,"ledgerBizNo",refundLedgerBizNo))));
                changed(db.write("UPDATE nx_promotion_budget SET committed=committed-?,revision=revision+1 WHERE activity_id=? AND asset=? AND product_no=? AND committed>=?",r.get("amount"),r.get("activity_id"),r.get("asset"),r.get("product_no"),r.get("amount")));returnStock(r);
            }else if("ISSUED".equals(status))changed(db.write("UPDATE nx_promotion_reward SET status='REVERSAL_PENDING',revision=revision+1,updated_at=NOW(6) WHERE obligation_id=? AND status='ISSUED'",reward.get("obligation_id")));
            else if(Set.of("PROCESSING","OUTCOME_UNKNOWN").contains(status))changed(db.write("UPDATE nx_promotion_reward SET status='MANUAL_REVIEW',retry_reason='REFUND_REQUIRES_ISSUANCE_RECONCILIATION',revision=revision+1,updated_at=NOW(6) WHERE obligation_id=?",reward.get("obligation_id")));
        }
        audit("PROMOTION_REFUND_CONFIRMED",orderNo,values("refundNo",refundNo,"ledgerBizNo",refundLedgerBizNo));
        outbox.publish("PROMOTION_ORDER",orderNo,"promotion.refund.confirmed",values("orderNo",orderNo,"refundNo",refundNo));
        restoreUsageAfterRefund(orderNo);
    }
    /** Order/account locks serialize all terminal reward outcomes and this one-time count restoration. */
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void restoreUsageAfterRefund(String orderNo){
        Map<String,Object> order=db.order(orderNo,true);
        if(!"REFUNDED".equals(order.get("payment_status"))||!"REFUNDED".equals(order.get("order_status")))return;
        Map<String,Object> receipt=db.one("SELECT quota_restored_at FROM nx_promotion_order_receipt WHERE order_no=? FOR UPDATE",orderNo);
        if(receipt==null||receipt.get("quota_restored_at")!=null)return;
        List<Map<String,Object>> reservations=db.reservations(orderNo,true);if(reservations.isEmpty())return;
        Map<String,Object> snapshot=parse(reservations.get(0).get("snapshot_json"));
        Map<String,Object> first=PromotionPolicyResolver.content(map(snapshot.get("policies")),"firstPurchase");
        if(first==null||!Boolean.TRUE.equals(first.get("restoreAfterRefund")))return;
        List<Map<String,Object>> rewards=db.list("SELECT * FROM nx_promotion_reward WHERE order_no=? ORDER BY obligation_id FOR UPDATE",orderNo);
        if(rewards.size()!=reservations.size())return;
        for(Map<String,Object> reward:rewards){
            if("CANCELLED".equals(reward.get("status"))){
                if(reward.get("original_ledger_no")!=null||reward.get("original_earnings_entry_no")!=null
                    ||db.count("SELECT COUNT(*) FROM nx_promotion_device_receipt WHERE obligation_id=?",reward.get("obligation_id"))!=0)return;
            }else if("REVERSED".equals(reward.get("status"))){
                if(db.list("""
                    SELECT v.reversal_id FROM nx_promotion_reversal v
                    JOIN nx_promotion_reservation r ON r.reservation_id=? AND v.asset=r.asset
                    WHERE v.obligation_id=? AND v.status='REVERSED' AND v.outstanding=0 AND v.recovered=v.amount AND v.amount=r.amount FOR UPDATE
                    """,reward.get("reservation_id"),reward.get("obligation_id")).size()!=1)return;
            }else return;
        }
        if(db.list("""
            SELECT h.refund_request_id FROM nx_promotion_refund_hold h
            JOIN nx_wallet_ledger l ON l.biz_no=h.refund_ledger_biz_no AND l.user_id=? AND l.biz_type='ORDER_REFUND'
              AND l.asset='USDT' AND l.direction='IN' AND l.status='SUCCESS' AND l.amount=? AND l.is_deleted=0
            WHERE h.order_no=? AND h.status='EXECUTED' FOR UPDATE
            """,order.get("user_id"),order.get("amount_usdt"),orderNo).isEmpty())return;
        String activity=text(reservations.get(0).get("activity_id"));db.activity(activity,true);
        require(reservations.stream().allMatch(r->activity.equals(r.get("activity_id"))&&"COMMITTED".equals(r.get("status"))),"PROMOTION_RESTORE_RESERVATION_INVALID");
        record Group(long account,String role,String rule){}
        Map<Group,Long> groups=new LinkedHashMap<>();Set<Group> people=new LinkedHashSet<>();
        for(Map<String,Object> r:reservations){Group group=new Group(number(r.get("beneficiary_id")),text(r.get("beneficiary_role")),text(r.get("rule_id")));
            groups.merge(group,1L,Long::sum);people.add(new Group(group.account(),group.role(),""));}
        changed(db.write("UPDATE nx_promotion_order_receipt SET quota_restored_at=NOW(6) WHERE order_no=? AND quota_restored_at IS NULL",orderNo));
        changed(db.write("UPDATE nx_promotion SET used_orders=used_orders-1,revision=revision+1,updated_at=NOW(6) WHERE activity_id=? AND used_orders>=1",activity));
        for(Group p:people)changed(db.write("UPDATE nx_promotion_usage SET used_orders=used_orders-1,revision=revision+1 WHERE activity_id=? AND account_id=? AND beneficiary_role=? AND rule_id='' AND used_orders>=1",activity,p.account(),p.role()));
        for(var entry:groups.entrySet()){Group g=entry.getKey();changed(db.write("UPDATE nx_promotion_usage SET used_groups=used_groups-?,revision=revision+1 WHERE activity_id=? AND account_id=? AND beneficiary_role=? AND rule_id=? AND used_groups>=?",entry.getValue(),activity,g.account(),g.role(),g.rule(),entry.getValue()));}
        audit("PROMOTION_REFUND_QUOTA_RESTORED",orderNo,values("activityId",activity,"beneficiaryRoles",people.size(),"rewardGroups",reservations.size(),"snapshotHash",hash(snapshot)));
    }
    private void audit(String action,String order,Map<String,Object> detail){
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action(action).resourceType("PROMOTION_ORDER").resourceId(order)
            .bizNo(order).actorUsername("PROMOTION_ENGINE").actorType("SYSTEM").result("SUCCESS").riskLevel("HIGH").detail(detail).build());
    }
}
