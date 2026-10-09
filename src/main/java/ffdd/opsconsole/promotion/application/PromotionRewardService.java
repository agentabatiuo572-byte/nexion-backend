package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

/** Local wallet, D4 and device receipts commit with the obligation in one database transaction. */
@Service
@RequiredArgsConstructor
public class PromotionRewardService {
    private final PromotionMapper db;
    private final PromotionOrderService orders;
    private final EarningsReleaseService earnings;
    private final TreasuryLedgerPostingFacade ledger;
    private final AuditLogService audit;
    private final EventOutboxService outbox;

    public List<String> pending(int limit){
        return db.list("""
            SELECT r.obligation_id FROM nx_promotion_reward r
            JOIN nx_user u ON u.id=r.beneficiary_id AND u.status='ACTIVE' AND u.sandbox=0 AND u.is_deleted=0
            JOIN nx_order o ON o.order_no=r.order_no AND o.payment_status='PAID' AND o.paid_at IS NOT NULL AND o.is_deleted=0
            WHERE r.status IN ('PENDING','READY') AND r.ready_at<=NOW(6)
              AND NOT EXISTS(SELECT 1 FROM nx_promotion_refund_hold h WHERE h.order_no=r.order_no AND h.status IN ('HELD','OUTCOME_UNKNOWN','EXECUTED'))
            ORDER BY r.ready_at,r.obligation_id LIMIT ?
            """,Math.min(100,Math.max(1,limit)))
            .stream().map(r->text(r.get("obligation_id"))).toList();
    }
    public List<String> reversalsAfter(String cursor,int limit){
        return db.list("""
            SELECT r.obligation_id FROM nx_promotion_reward r
            WHERE r.status='REVERSAL_PENDING' AND r.obligation_id>?
              AND (r.original_earnings_entry_no IS NULL OR NOT EXISTS(
                SELECT 1 FROM nx_admin_idempotency_record i
                WHERE i.scope=CONCAT('EARNINGS_RECOVERY:PRODUCTION:',r.beneficiary_id)
                  AND i.idempotency_key=CONCAT('PROMOTION-RECOVER-',SHA2(CONCAT(r.obligation_id,':WHOLE_ORDER_REFUND:',(
                    SELECT h.refund_no FROM nx_promotion_refund_hold h
                    WHERE h.order_no=r.order_no AND h.status='EXECUTED' ORDER BY h.refund_request_id LIMIT 1)),256))
                  AND (i.status='UNKNOWN' OR (i.status='PROCESSING' AND i.is_deleted=0))))
            ORDER BY r.obligation_id LIMIT ?
            """,text(cursor),Math.min(100,Math.max(1,limit)))
            .stream().map(r->text(r.get("obligation_id"))).toList();
    }
    private Map<String,Object> lock(String id){
        Map<String,Object> initial=db.requiredRow("SELECT * FROM nx_promotion_reward WHERE obligation_id=?",id);
        String order=text(initial.get("order_no"));orders.lockOrderParticipants(order);db.order(order,true);
        db.activity(text(initial.get("activity_id")),true);
        db.list("SELECT * FROM nx_promotion_budget WHERE activity_id=? ORDER BY asset,product_no FOR UPDATE",initial.get("activity_id"));
        return db.requiredRow("SELECT * FROM nx_promotion_reward WHERE obligation_id=? FOR UPDATE",id);
    }
    private Map<String,Object> reservation(Map<String,Object> reward){
        return db.requiredRow("SELECT * FROM nx_promotion_reservation WHERE reservation_id=? FOR UPDATE",reward.get("reservation_id"));
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> issue(String obligationId,String commandId){
        Map<String,Object> reward=lock(obligationId);
        if("ISSUED".equals(reward.get("status")))return view(reward,false,true);
        require(Set.of("PENDING","READY","RETRYABLE_FAILED").contains(text(reward.get("status"))),"PROMOTION_REWARD_NOT_RETRYABLE");
        Map<String,Object> order=db.order(text(reward.get("order_no")),true);
        require("PAID".equals(order.get("payment_status"))&&order.get("paid_at")!=null,"PROMOTION_REWARD_ORDER_NOT_PAID");
        require(!db.hasHold(text(reward.get("order_no"))),"PROMOTION_REFUND_HOLD");
        Map<String,Object> account=db.user(number(reward.get("beneficiary_id")),true);
        require("ACTIVE".equals(account.get("status"))&&number(account.get("sandbox"))==0,"PROMOTION_ACCOUNT_FROZEN");
        require(reward.get("ready_at")!=null&&!instant(reward.get("ready_at")).isAfter(Instant.now()),"PROMOTION_REWARD_NOT_MATURE");
        Map<String,Object> reserved=reservation(reward),snapshot=parse(reward.get("snapshot_json"));
        require(hash(snapshot).equals(reward.get("snapshot_hash"))&&"COMMITTED".equals(reserved.get("status")),"PROMOTION_REWARD_SNAPSHOT_INVALID");
        Map<String,Object> spec=map(map(snapshot.get("award")).get("reward"));String asset=text(spec.get("type"));
        String attempt=id("PA"),posting="PROMOTION-ISSUE-"+obligationId,entry=null;
        changed(db.write("INSERT INTO nx_promotion_reward_attempt(attempt_id,obligation_id,command_id,action,status,evidence_json,started_at) VALUES(?,?,?,'ISSUE','PROCESSING',JSON_OBJECT(),NOW(6))",attempt,obligationId,commandId));
        changed(db.write("UPDATE nx_promotion_reward SET status='PROCESSING',last_command_id=?,revision=revision+1,updated_at=NOW(6) WHERE obligation_id=?",commandId,obligationId));
        if("DEVICE".equals(asset))issueDevices(reward,snapshot,spec);
        else{
            BigDecimal amount=PromotionQuoteService.rewardAmount(spec);
            entry=earnings.creditReward(number(reward.get("beneficiary_id")),"PROMOTION_REWARD",obligationId,asset,amount,"PRODUCTION","PROMOTION:"+obligationId);
            ledger.postLedgerEntry(posting,number(reward.get("beneficiary_id")),"PROMOTION_REWARD",asset,"IN",amount,"SUCCESS","Approved promotion reward "+obligationId);
        }
        changed(db.write("UPDATE nx_promotion_budget SET committed=committed-?,issued=issued+?,revision=revision+1 WHERE activity_id=? AND asset=? AND product_no=? AND committed>=?",
            reserved.get("amount"),reserved.get("amount"),reserved.get("activity_id"),reserved.get("asset"),reserved.get("product_no"),reserved.get("amount")));
        changed(db.write("UPDATE nx_promotion_reward SET status='ISSUED',original_ledger_no=?,original_earnings_entry_no=?,retry_reason=NULL,revision=revision+1,updated_at=NOW(6) WHERE obligation_id=? AND status='PROCESSING'",
            "DEVICE".equals(asset)?null:posting,entry,obligationId));
        changed(db.write("UPDATE nx_promotion_reward_attempt SET status='ISSUED',evidence_json=?,finished_at=NOW(6) WHERE attempt_id=?",json(values("ledgerBizNo","DEVICE".equals(asset)?null:posting,"earningsEntryNo",entry)),attempt));
        audit("PROMOTION_REWARD_ISSUED",obligationId,values("asset",asset,"amount",money(decimal(reserved.get("amount"))),"commandId",commandId));
        outbox.publish("PROMOTION_REWARD",obligationId,"promotion.reward.issued",values("obligationId",obligationId,"beneficiaryId",reward.get("beneficiary_id"),"asset",asset));
        return currentView(obligationId);
    }
    private void issueDevices(Map<String,Object> reward,Map<String,Object> snapshot,Map<String,Object> spec){
        require(db.occupiedDeviceSlots(number(reward.get("beneficiary_id")))<=db.deviceSlotCap(),"PROMOTION_DEVICE_CAPACITY_UNAVAILABLE");
        String productNo=text(spec.get("giftProductNo"));db.product(productNo,true);
        Map<String,Object> profile=PromotionPolicyResolver.resolveReference(map(snapshot.get("policies")),map(spec.get("deviceRightsProfile")));
        Map<String,Object> rights=map(profile.get("resolvedDeviceRights"));
        require(productNo.equals(rights.get("productNo")),"PROMOTION_GIFT_PROFILE_MISMATCH");
        // Only the existing auto-activated, unbounded E1 device contract is executable here.
        require("AUTO".equals(rights.get("activationMode"))&&"ISSUED".equals(rights.get("effectiveOn"))&&rights.get("durationDays")==null,"PROMOTION_DEVICE_RIGHTS_NOT_EXECUTABLE");
        Map<String,Object> frozen=map(map(snapshot.get("products")).get(productNo)),product=map(frozen.get("product")),sku=map(frozen.get("sku"));
        require("ACTIVE".equals(product.get("status")),"PROMOTION_GIFT_PRODUCT_UNAVAILABLE");
        String type=text(product.get("product_type"));require(Set.of("DEVICE","SERVER").contains(type),"PROMOTION_GIFT_PRODUCT_UNSUPPORTED");
        boolean share=false;
        String power=text(sku.get("power_text")).replaceAll("[Ww]","").trim();
        require(share||power.matches("[0-9]+(\\.[0-9]+)?"),"PROMOTION_GIFT_POWER_UNAVAILABLE");
        BigDecimal watts=share?BigDecimal.ZERO:new BigDecimal(power);require(share||watts.signum()>0,"PROMOTION_GIFT_POWER_UNAVAILABLE");
        int count=Math.toIntExact(number(spec.get("quantity")));
        for(int i=0;i<count;i++){
            String instance="PG-"+sha256(text(reward.get("obligation_id"))+":"+i).substring(0,48);
            changed(db.write("""
                INSERT INTO nx_user_device(user_id,source_order_no,product_id,product_code,product_tier,instance_no,name,device_type,
                  generation,gpu_model,vram_total_gb,base_power_w,dc_location,price_usdt_snapshot,ownership_status,source_channel,
                  source_environment,run_id,status,hashrate,daily_usdt,daily_nex,last_seen_at,purchased_at,activated_at,pending_deactivate,row_version,is_deleted)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0,'OWNED','PROMOTION_GIFT','PRODUCTION','','ACTIVE',?,?,?,NOW(6),NOW(6),NOW(6),0,0,0)
                """,reward.get("beneficiary_id"),reward.get("order_no"),product.get("id"),productNo,product.get("tier"),instance,product.get("name"),type,
                product.get("generation"),product.get("gpu_model"),product.get("vram_total_gb"),watts,required(sku.get("datacenter"),"datacenter"),
                decimal(product.get("hashrate")),decimal(product.get("estimated_daily_usdt")),decimal(product.get("daily_nex"))));
            Map<String,Object> device=db.requiredRow("SELECT id FROM nx_user_device WHERE instance_no=?",instance);
            changed(db.write("INSERT INTO nx_promotion_device_receipt(obligation_id,device_unit_seq,device_id,instance_no,profile_id,profile_version,rights_snapshot_json) VALUES(?,?,?,?,?,?,?)",
                reward.get("obligation_id"),i,device.get("id"),instance,profile.get("policyId"),number(profile.get("version")),json(rights)));
        }
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Exception.class)
    public void recordFailure(String id,String commandId,String reason){
        Map<String,Object> row=lock(id);
        if(!Set.of("PENDING","READY","RETRYABLE_FAILED").contains(text(row.get("status"))))return;
        String code=reason==null?"PROMOTION_ISSUE_FAILED":reason.substring(0,Math.min(500,reason.length()));
        if(Set.of("PROMOTION_REFUND_HOLD","PROMOTION_ACCOUNT_FROZEN","PROMOTION_REWARD_NOT_MATURE").contains(code))return;
        changed(db.write("UPDATE nx_promotion_reward SET status='RETRYABLE_FAILED',last_command_id=?,retry_reason=?,revision=revision+1,updated_at=NOW(6) WHERE obligation_id=?",commandId,code,id));
        db.write("INSERT INTO nx_promotion_reward_attempt(attempt_id,obligation_id,command_id,action,status,evidence_json,started_at,finished_at) VALUES(?,?,?,'ISSUE','RETRYABLE_FAILED',?,NOW(6),NOW(6))",
            id("PA"),id,commandId,json(values("failure",code,"transactionRolledBack",true)));
        audit("PROMOTION_REWARD_FAILED",id,values("reason",code,"commandId",commandId));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Exception.class)
    public void recordActionFailure(String id,String commandId,String action,String reason){
        require(Set.of("ISSUE","RECONCILE","CANCEL","REVERSE","RESOLVE").contains(action),"PROMOTION_ACTION_INVALID");
        Map<String,Object> row=lock(id);
        String code=reason==null?"PROMOTION_ACTION_FAILED":reason.substring(0,Math.min(500,reason.length()));
        terminalAttempt(id,commandId,action,"RETRYABLE_FAILED",values("failure",code,"transactionRolledBack",true,"retainedRewardState",row.get("status")));
        audit("PROMOTION_REWARD_ACTION_FAILED",id,values("action",action,"reason",code,"commandId",commandId));
    }
    private void terminalAttempt(String obligation,String command,String action,String state,Map<String,Object> evidence){
        changed(db.write("INSERT INTO nx_promotion_reward_attempt(attempt_id,obligation_id,command_id,action,status,evidence_json,started_at,finished_at) VALUES(?,?,?,?,?,?,NOW(6),NOW(6))",
            id("PA"),obligation,command,action,state,json(evidence)));
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> reconcile(String id,String commandId){
        Map<String,Object> row=lock(id),snapshot=parse(row.get("snapshot_json")),spec=map(map(snapshot.get("award")).get("reward"));
        String asset=text(spec.get("type"));boolean issued=false,absent=false;
        if("DEVICE".equals(asset)){
            long count=db.list("SELECT r.device_id FROM nx_promotion_device_receipt r JOIN nx_user_device d ON d.id=r.device_id WHERE r.obligation_id=? AND d.user_id=? AND d.source_channel='PROMOTION_GIFT' AND d.source_order_no=? FOR UPDATE",id,row.get("beneficiary_id"),row.get("order_no")).size();
            issued=count==number(spec.get("quantity"));absent=count==0;
        }else{
            Map<String,Object> entry=db.one("SELECT * FROM nx_earnings_release_entry WHERE source_type='PROMOTION_REWARD' AND source_ref=? AND user_id=? AND asset=? AND source_environment='PRODUCTION' AND is_deleted=0 FOR UPDATE",id,row.get("beneficiary_id"),asset);
            Map<String,Object> posting=db.one("SELECT * FROM nx_wallet_ledger WHERE biz_no=? AND user_id=? AND asset=? AND direction='IN' AND status='SUCCESS' FOR UPDATE","PROMOTION-ISSUE-"+id,row.get("beneficiary_id"),asset);
            issued=entry!=null&&posting!=null&&decimal(entry.get("amount")).compareTo(PromotionQuoteService.rewardAmount(spec))==0&&decimal(posting.get("amount")).compareTo(PromotionQuoteService.rewardAmount(spec))==0;
            absent=entry==null&&posting==null;
        }
        String current=text(row.get("status")),next;
        if(Set.of("REVERSED","CANCELLED","REVERSAL_PENDING").contains(current)){
            terminalAttempt(id,commandId,"RECONCILE",current,values("unchangedTerminal",true));return view(row,false,true);
        }
        if(issued){
            // A committed ISSUED row is required: partial imported evidence cannot manufacture budget movements.
            next="ISSUED".equals(current)?"ISSUED":"MANUAL_REVIEW";
        }else if(absent&&Set.of("OUTCOME_UNKNOWN","PROCESSING","RETRYABLE_FAILED","READY","PENDING").contains(current))next=db.hasHold(text(row.get("order_no")))?"MANUAL_REVIEW":"READY";
        else next="MANUAL_REVIEW";
        changed(db.write("UPDATE nx_promotion_reward SET status=?,last_command_id=?,revision=revision+1,updated_at=NOW(6) WHERE obligation_id=?",next,commandId,id));
        terminalAttempt(id,commandId,"RECONCILE",next,values("before",current,"issuedEvidence",issued,"noAssetEvidence",absent));
        audit("PROMOTION_REWARD_RECONCILED",id,values("before",current,"after",next,"issuedEvidence",issued,"noAssetEvidence",absent));
        return currentView(id);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> reverseRefund(String id,String commandId){
        Map<String,Object> row=lock(id);
        Map<String,Object> hold=db.requiredRow("SELECT * FROM nx_promotion_refund_hold WHERE order_no=? AND status='EXECUTED' ORDER BY refund_request_id LIMIT 1 FOR UPDATE",row.get("order_no"));
        return reverseLocked(row,values("type","WHOLE_ORDER_REFUND","refundNo",hold.get("refund_no")),commandId,"Confirmed whole-order wallet refund");
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public Map<String,Object> reverse(String id,Map<String,Object> request,String commandId){
        Map<String,Object> row=lock(id);require(number(row.get("revision"))==number(request.get("expectedRevision")),"PROMOTION_REVISION_CONFLICT");
        return reverseLocked(row,map(request.get("basis")),commandId,required(request.get("reason"),"reason"));
    }
    private Map<String,Object> reverseLocked(Map<String,Object> row,Map<String,Object> basis,String commandId,String reason){
        String id=text(row.get("obligation_id"));String basisType=text(basis.get("type"));
        String basisRef=verifyBasis(row,basis,"REVERSE");
        Map<String,Object> previous=db.one("SELECT * FROM nx_promotion_reversal WHERE obligation_id=? AND basis_type=? AND basis_ref=? FOR UPDATE",id,basisType,basisRef);
        if(previous!=null){orders.restoreUsageAfterRefund(text(row.get("order_no")));return view(row,false,true);}
        require(Set.of("ISSUED","REVERSAL_PENDING").contains(text(row.get("status"))),"PROMOTION_REWARD_NOT_REVERSIBLE");
        Map<String,Object> reserved=reservation(row),snapshot=parse(row.get("snapshot_json")),spec=map(map(snapshot.get("award")).get("reward"));
        BigDecimal amount=decimal(reserved.get("amount")),recovered=BigDecimal.ZERO;String asset=text(spec.get("type")),posting=null;
        Map<String,Object> evidence=new LinkedHashMap<>();
        if("DEVICE".equals(asset)){
            List<Map<String,Object>> devices=db.list("SELECT r.*,d.user_id,d.source_channel,d.ownership_status,d.is_deleted FROM nx_promotion_device_receipt r JOIN nx_user_device d ON d.id=r.device_id WHERE r.obligation_id=? ORDER BY r.device_id FOR UPDATE",id);
            require(devices.size()==amount.intValueExact(),"PROMOTION_DEVICE_RECEIPT_INCOMPLETE");
            for(Map<String,Object> device:devices){
                long deviceId=number(device.get("device_id"));
                boolean unused=number(device.get("user_id"))==number(row.get("beneficiary_id"))&&"OWNED".equals(device.get("ownership_status"))&&"PROMOTION_GIFT".equals(device.get("source_channel"))&&number(device.get("is_deleted"))==0
                    &&db.list("SELECT id FROM nx_compute_task WHERE user_device_id=? FOR UPDATE",deviceId).isEmpty()
                    &&db.list("SELECT id FROM nx_earning_event WHERE user_device_id=? FOR UPDATE",deviceId).isEmpty()
                    &&db.list("SELECT id FROM nx_compute_share_enrollment WHERE user_device_id=? FOR UPDATE",deviceId).isEmpty();
                if(!unused)continue;
                changed(db.write("UPDATE nx_user_device SET ownership_status='REVOKED',status='DEACTIVATED',deactivated_at=NOW(6),pending_deactivate=0,row_version=row_version+1,updated_at=NOW(6) WHERE id=? AND ownership_status='OWNED'",deviceId));
                recovered=recovered.add(BigDecimal.ONE);
            }
            if(recovered.signum()>0&&!"UNLIMITED".equals(db.product(text(reserved.get("product_no")),true).get("inventory_mode")))
                changed(db.write("UPDATE nx_product SET stock=stock+?,updated_at=NOW(6) WHERE product_no=? AND is_deleted=0",recovered.intValueExact(),reserved.get("product_no")));
            evidence.put("revokedDeviceCount",recovered.intValueExact());
        }else{
            String recoveryKey="PROMOTION-RECOVER-"+sha256(id+":"+basisType+":"+basisRef);
            var receipt=earnings.recoverReward(new EarningsReleaseService.RewardRecoveryRequest(number(row.get("beneficiary_id")),required(row.get("original_earnings_entry_no"),"originalEntry"),"PROMOTION_REWARD",id,asset,"PRODUCTION",amount,reason,"PROMOTION_ENGINE"),recoveryKey);
            recovered=receipt.recovered();evidence.put("sourceRecovery",receipt);
            if(recovered.signum()>0){posting="PROMOTION-REVERSE-"+id;
                ledger.postLedgerEntry(posting,number(row.get("beneficiary_id")),"PROMOTION_REWARD_REVERSAL",asset,"OUT",recovered,"SUCCESS",reason);}
        }
        BigDecimal outstanding=amount.subtract(recovered);String next=outstanding.signum()==0?"REVERSED":"MANUAL_REVIEW";
        changed(db.write("""
            INSERT INTO nx_promotion_reversal(reversal_id,obligation_id,basis_type,basis_ref,refund_no,approval_operation_id,asset,amount,
              recovered,outstanding,reusable,original_posting_no,reversal_posting_no,status,evidence_json,reason)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id("PV"),id,basisType,basisRef,"WHOLE_ORDER_REFUND".equals(basisType)?basisRef:null,"APPROVED_CORRECTION".equals(basisType)?basisRef:null,
            asset,amount,recovered,outstanding,recovered,"DEVICE".equals(asset)?"PROMOTION-DEVICE-"+id:row.get("original_ledger_no"),posting,next,json(evidence),reason));
        changed(db.write("UPDATE nx_promotion_budget SET reversed=reversed+?,unrecoverable=unrecoverable+?,revision=revision+1 WHERE activity_id=? AND asset=? AND product_no=?",recovered,outstanding,row.get("activity_id"),asset,reserved.get("product_no")));
        changed(db.write("UPDATE nx_promotion_reward SET status=?,last_command_id=?,revision=revision+1,updated_at=NOW(6) WHERE obligation_id=?",next,commandId,id));
        terminalAttempt(id,commandId,"REVERSE",next,values("basis",basis,"recovered",money(recovered),"outstanding",money(outstanding),"evidence",evidence));
        audit("PROMOTION_REWARD_REVERSED",id,values("basisType",basisType,"basisRef",basisRef,"recovered",money(recovered),"outstanding",money(outstanding)));
        orders.restoreUsageAfterRefund(text(row.get("order_no")));
        return currentView(id);
    }
    private String verifyBasis(Map<String,Object> row,Map<String,Object> basis,String action){
        if("WHOLE_ORDER_REFUND".equals(basis.get("type"))){
            String no=required(basis.get("refundNo"),"refundNo");
            Map<String,Object> hold=db.requiredRow("SELECT * FROM nx_promotion_refund_hold WHERE order_no=? AND refund_no=? AND status='EXECUTED' ORDER BY refund_request_id LIMIT 1 FOR UPDATE",row.get("order_no"),no);
            Map<String,Object> order=db.order(text(row.get("order_no")),true);
            require("REFUNDED".equals(order.get("payment_status"))||"REFUNDED".equals(order.get("order_status")),"PROMOTION_REFUND_NOT_CONFIRMED");
            require(db.list("SELECT id FROM nx_wallet_ledger WHERE biz_no=? AND user_id=? AND biz_type='ORDER_REFUND' AND direction='IN' AND status='SUCCESS' FOR UPDATE",hold.get("refund_ledger_biz_no"),order.get("user_id")).size()==1,"PROMOTION_REFUND_LEDGER_MISSING");
            return no;
        }
        require("APPROVED_CORRECTION".equals(basis.get("type")),"PROMOTION_DISPOSITION_BASIS_REQUIRED");
        String operation=required(basis.get("approvalOperationId"),"approvalOperationId");
        Map<String,Object> ticket=db.requiredRow("SELECT * FROM nx_audit_operation_ticket WHERE operation_id=? AND is_deleted=0 FOR UPDATE",operation);
        Map<String,Object> command=parse(ticket.get("command_json"));
        Map<String,Object> params=map(command.get("params"));
        Map<String,Object> snapshot=parse(row.get("snapshot_json"));
        require("approved".equals(ticket.get("status"))&&ticket.get("decided_at")!=null&&"H".equals(ticket.get("source_domain"))
            &&"H".equals(command.get("domain"))&&"promotion_reward_correction".equals(command.get("op"))
            &&params.keySet().equals(Set.of("obligationId","expectedRevision","snapshotHash","action","asset"))
            &&text(row.get("obligation_id")).equals(params.get("obligationId"))&&action.equals(params.get("action"))
            &&number(row.get("revision"))==number(params.get("expectedRevision"))
            &&number(basis.get("approvalRevision"))==number(params.get("expectedRevision"))
            &&hash(command).equals(basis.get("approvedPayloadHash"))
            &&row.get("snapshot_hash").equals(params.get("snapshotHash"))&&hash(snapshot).equals(row.get("snapshot_hash"))
            &&map(map(snapshot.get("award")).get("reward")).get("type").equals(params.get("asset")),"PROMOTION_CORRECTION_NOT_APPROVED");
        require(!db.list("SELECT id FROM nx_audit_log WHERE resource_type='PROMOTION_REWARD' AND resource_id=? AND action='PROMOTIONREWARDCORRECTIONAPPROVED' AND result='SUCCESS' AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.request.snapshotHash'))=? AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.request.action'))=? AND JSON_EXTRACT(detail_json,'$.request.expectedRevision')=? FOR UPDATE",row.get("obligation_id"),params.get("snapshotHash"),action,number(params.get("expectedRevision"))).isEmpty(),"PROMOTION_CORRECTION_APPROVAL_FACT_MISSING");
        return operation;
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public Map<String,Object> cancel(String id,Map<String,Object> request,String commandId){
        Map<String,Object> row=lock(id);require(number(row.get("revision"))==number(request.get("expectedRevision")),"PROMOTION_REVISION_CONFLICT");
        verifyBasis(row,map(request.get("basis")),"CANCEL");require(Set.of("PENDING","READY","RETRYABLE_FAILED").contains(text(row.get("status"))),"PROMOTION_REWARD_NOT_CANCELLABLE");
        Map<String,Object> reserved=reservation(row);
        changed(db.write("UPDATE nx_promotion_budget SET committed=committed-?,revision=revision+1 WHERE activity_id=? AND asset=? AND product_no=? AND committed>=?",reserved.get("amount"),row.get("activity_id"),reserved.get("asset"),reserved.get("product_no"),reserved.get("amount")));
        orders.returnStock(reserved);
        changed(db.write("UPDATE nx_promotion_reward SET status='CANCELLED',last_command_id=?,revision=revision+1,updated_at=NOW(6) WHERE obligation_id=?",commandId,id));
        terminalAttempt(id,commandId,"CANCEL","CANCELLED",values("basis",request.get("basis"),"reason",request.get("reason")));
        audit("PROMOTION_REWARD_CANCELLED",id,request);orders.restoreUsageAfterRefund(text(row.get("order_no")));return currentView(id);
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public Map<String,Object> resolve(String id,Map<String,Object> request,String commandId){
        Map<String,Object> row=lock(id);require(number(row.get("revision"))==number(request.get("expectedRevision")),"PROMOTION_REVISION_CONFLICT");
        require(Set.of("MANUAL_REVIEW","OUTCOME_UNKNOWN").contains(text(row.get("status"))),"PROMOTION_REWARD_NOT_MANUAL");
        String decision=text(request.get("decision"));
        if("KEEP_MANUAL_REVIEW".equals(decision)){terminalAttempt(id,commandId,"RESOLVE","MANUAL_REVIEW",request);audit("PROMOTION_REWARD_MANUAL_REVIEW",id,request);return view(row,false,true);}
        if("CONFIRM_REVERSED".equals(decision)){
            require(db.list("SELECT reversal_id FROM nx_promotion_reversal WHERE obligation_id=? AND status='REVERSED' AND outstanding=0 FOR UPDATE",id).size()==1,"PROMOTION_REVERSAL_EVIDENCE_MISSING");
            changed(db.write("UPDATE nx_promotion_reward SET status='REVERSED',last_command_id=?,revision=revision+1,updated_at=NOW(6) WHERE obligation_id=?",commandId,id));
        }else{
            // Reconciliation can only assert the persisted atomic outcome, never an operator's claim.
            Map<String,Object> result=reconcile(id,commandId);
            require(("CONFIRM_ISSUED".equals(decision)&&"ISSUED".equals(result.get("state")))||("CONFIRM_NOT_ISSUED".equals(decision)&&"READY".equals(result.get("state"))),"PROMOTION_RESOLUTION_EVIDENCE_MISMATCH");
        }
        Map<String,Object> result=currentView(id);terminalAttempt(id,commandId,"RESOLVE",text(result.get("state")),request);
        audit("PROMOTION_REWARD_RESOLVED",id,request);orders.restoreUsageAfterRefund(text(row.get("order_no")));return result;
    }
    public Map<String,Object> get(Long owner,String id,boolean admin){
        Map<String,Object> row=db.requiredRow("SELECT * FROM nx_promotion_reward WHERE obligation_id=?",id);
        require(owner==null||number(row.get("beneficiary_id"))==owner,"PROMOTION_REWARD_OWNER_MISMATCH");return view(row,admin);
    }
    private Map<String,Object> currentView(String id){
        return view(db.requiredRow("SELECT * FROM nx_promotion_reward WHERE obligation_id=? FOR UPDATE",id),false,true);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> page(Long owner,boolean admin,String cursor,int limit,String activity,String state,String order){
        return page(owner,admin,cursor,limit,activity,state,order,null,null);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> page(Long owner,boolean admin,String cursor,int limit,String activity,String state,String order,Long beneficiary,String type){
        if(!text(type).isEmpty()&&!Set.of("USDT","NEX","DEVICE").contains(type))throw new ffdd.opsconsole.shared.exception.BizException(422,"PROMOTION_REWARD_TYPE_INVALID");
        if(!text(state).isEmpty()&&!Set.of("PENDING","READY","PROCESSING","ISSUED","RETRYABLE_FAILED","OUTCOME_UNKNOWN","CANCELLED","REVERSAL_PENDING","REVERSED","MANUAL_REVIEW").contains(state))throw new ffdd.opsconsole.shared.exception.BizException(422,"PROMOTION_REWARD_STATE_INVALID");
        if(beneficiary!=null&&beneficiary<1)throw new ffdd.opsconsole.shared.exception.BizException(422,"PROMOTION_BENEFICIARY_INVALID");
        if(!admin&&(beneficiary!=null||!text(type).isEmpty()))throw new ffdd.opsconsole.shared.exception.BizException(403,"PROMOTION_ADMIN_REQUIRED");
        int size=Math.max(1,Math.min(limit,100));Instant asOf=Instant.now();
        String filter=" FROM nx_promotion_reward r JOIN nx_promotion_reservation s ON s.reservation_id=r.reservation_id WHERE (? IS NULL OR r.beneficiary_id=?) AND (? IS NULL OR r.beneficiary_id=?) AND (?='' OR r.activity_id=?) AND (?='' OR r.status=?) AND (?='' OR r.order_no=?) AND (?='' OR s.asset=?)";
        var args=new ArrayList<Object>(Arrays.asList(owner,owner,beneficiary,beneficiary,text(activity),text(activity),text(state),text(state),text(order),text(order),text(type),text(type)));
        long total=admin?db.count("SELECT COUNT(*)"+filter,args.toArray()):0;
        args.add(text(cursor));args.add(text(cursor));args.add(size+1);
        List<Map<String,Object>> rows=db.list("SELECT r.*"+filter+" AND (?='' OR r.obligation_id>?) ORDER BY r.obligation_id LIMIT ?",args.toArray());
        boolean more=rows.size()>size;if(more)rows=rows.subList(0,size);
        var result=values("items",rows.stream().map(r->view(r,admin)).toList(),"nextCursor",more?rows.get(rows.size()-1).get("obligation_id"):null,"hasMore",more);
        if(admin){result.put("total",total);result.put("asOf",asOf.toString());result.put("query",values("activityId",activity,"state",state,"orderNo",order,"beneficiaryId",beneficiary==null?null:String.valueOf(beneficiary),"type",type));}return result;
    }
    private Map<String,Object> view(Map<String,Object> row,boolean admin){
        return view(row,admin,false);
    }
    private Map<String,Object> view(Map<String,Object> row,boolean admin,boolean currentRead){
        String id=text(row.get("obligation_id"));Map<String,Object> snapshot=parse(row.get("snapshot_json")),spec=map(map(snapshot.get("award")).get("reward"));
        String lock=currentRead?" FOR UPDATE":"";
        List<Map<String,Object>> devices=db.list("SELECT device_id,instance_no,created_at FROM nx_promotion_device_receipt WHERE obligation_id=? ORDER BY device_unit_seq"+lock,id);
        List<Map<String,Object>> holds=db.list("SELECT refund_request_id FROM nx_promotion_refund_hold WHERE order_no=? AND status IN ('HELD','OUTCOME_UNKNOWN','EXECUTED') ORDER BY refund_request_id"+lock,row.get("order_no"));
        List<Map<String,Object>> reversalRows=db.list("SELECT recovered,outstanding FROM nx_promotion_reversal WHERE obligation_id=?"+lock,id);
        Map<String,Object> recovery=reversalRows.isEmpty()?null:values("recovered",reversalRows.stream().map(r->decimal(r.get("recovered"))).reduce(BigDecimal.ZERO,BigDecimal::add),"outstanding",reversalRows.stream().map(r->decimal(r.get("outstanding"))).reduce(BigDecimal.ZERO,BigDecimal::add));
        Object issuedAt=devices.isEmpty()?null:devices.get(0).get("created_at");
        if(devices.isEmpty()&&row.get("original_earnings_entry_no")!=null)issuedAt=db.requiredRow("SELECT created_at FROM nx_wallet_ledger WHERE biz_no=? AND user_id=? AND asset=? AND biz_type='PROMOTION_REWARD' AND direction='IN' AND status='SUCCESS'"+lock,row.get("original_ledger_no"),row.get("beneficiary_id"),spec.get("type")).get("created_at");
        Object receipt=row.get("original_earnings_entry_no")!=null||!devices.isEmpty()?values("ledgerBizNo",row.get("original_ledger_no"),"earningsEntryNo",row.get("original_earnings_entry_no"),
            "deviceIds",devices.stream().map(d->number(d.get("device_id"))).toList(),"instanceNos",devices.stream().map(d->text(d.get("instance_no"))).toList(),
            "source","DEVICE".equals(spec.get("type"))?"PROMOTION_GIFT":"PROMOTION_REWARD","issuedAt",instant(issuedAt).toString()):null;
        Map<String,Object> result=values("obligationId",id,"activityId",row.get("activity_id"),"version",number(row.get("version")),"orderNo",row.get("order_no"),"orderLineId",text(row.get("order_line_id")),
            "ruleId",row.get("rule_id"),"rewardRuleId",row.get("reward_rule_id"),"unitSeq",number(row.get("unit_seq")),"beneficiaryRole",row.get("beneficiary_role"),"reward",spec,
            "state",row.get("status"),"revision",number(row.get("revision")),"refundHold",!holds.isEmpty(),"refundRequestIds",holds.stream().map(h->text(h.get("refund_request_id"))).toList(),
            "assetReceipt",receipt,"recoveredAmount",recovery==null||recovery.get("recovered")==null?null:money(decimal(recovery.get("recovered"))),"recoveryOutstanding",recovery==null||recovery.get("outstanding")==null?null:money(decimal(recovery.get("outstanding"))),
            "commandId",row.get("last_command_id"),"updatedAt",instant(row.get("updated_at")).toString());
        result.put("disclosure",PromotionPublicService.disclosure(db,map(snapshot.get("contract")),map(snapshot.get("policies")),spec));
        if(admin){result.put("beneficiaryId",text(row.get("beneficiary_id")));result.put("qualificationSnapshotHash",row.get("snapshot_hash"));
            result.put("dispositionOptions",dispositionOptions(row));
            result.put("attempts",db.list("SELECT * FROM nx_promotion_reward_attempt WHERE obligation_id=? ORDER BY started_at,attempt_id",id).stream().map(a->values("attemptId",a.get("attempt_id"),"commandId",a.get("command_id"),"state",a.get("status"),
                "startedAt",instant(a.get("started_at")).toString(),"finishedAt",a.get("finished_at")==null?null:instant(a.get("finished_at")).toString(),"evidenceRefs",List.of())).toList());}
        return result;
    }
    private List<Map<String,Object>> dispositionOptions(Map<String,Object> row){
        String state=text(row.get("status"));String action=Set.of("ISSUED","REVERSAL_PENDING").contains(state)?"REVERSE":Set.of("PENDING","READY","RETRYABLE_FAILED").contains(state)?"CANCEL":null;
        if(action==null)return List.of();var options=new ArrayList<Map<String,Object>>();
        for(var hold:db.list("""
            SELECT DISTINCT h.refund_no FROM nx_promotion_refund_hold h JOIN nx_order o ON o.order_no=h.order_no
            JOIN nx_wallet_ledger l ON l.biz_no=h.refund_ledger_biz_no AND l.user_id=o.user_id AND l.biz_type='ORDER_REFUND' AND l.direction='IN' AND l.status='SUCCESS'
            WHERE h.order_no=? AND h.status='EXECUTED' AND h.refund_no IS NOT NULL AND (o.payment_status='REFUNDED' OR o.order_status='REFUNDED') ORDER BY h.refund_no
            """,row.get("order_no")))options.add(values("action",action,"basis",values("type","WHOLE_ORDER_REFUND","refundNo",hold.get("refund_no")),"label",values("zh","已执行整单退款","en","Confirmed full-order refund","vi","Đã hoàn tiền toàn bộ đơn hàng")));
        for(var ticket:db.list("""
            SELECT * FROM nx_audit_operation_ticket WHERE status='approved' AND decided_at IS NOT NULL AND source_domain='H' AND is_deleted=0
              AND JSON_UNQUOTE(JSON_EXTRACT(command_json,'$.op'))='promotion_reward_correction'
              AND JSON_UNQUOTE(JSON_EXTRACT(command_json,'$.params.obligationId'))=? ORDER BY operation_id
            """,row.get("obligation_id"))){
            var command=parse(ticket.get("command_json"));var params=map(command.get("params"));
            if(!"H".equals(command.get("domain"))||!params.keySet().equals(Set.of("obligationId","expectedRevision","snapshotHash","action","asset"))
                ||!action.equals(params.get("action"))||number(row.get("revision"))!=number(params.get("expectedRevision"))||!row.get("snapshot_hash").equals(params.get("snapshotHash")))continue;
            var snapshot=parse(row.get("snapshot_json"));if(!hash(snapshot).equals(row.get("snapshot_hash"))||!map(map(snapshot.get("award")).get("reward")).get("type").equals(params.get("asset")))continue;
            if(db.count("SELECT COUNT(*) FROM nx_audit_log WHERE resource_type='PROMOTION_REWARD' AND resource_id=? AND action='PROMOTIONREWARDCORRECTIONAPPROVED' AND result='SUCCESS' AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.request.snapshotHash'))=? AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.request.action'))=? AND JSON_EXTRACT(detail_json,'$.request.expectedRevision')=?",row.get("obligation_id"),params.get("snapshotHash"),action,number(params.get("expectedRevision")))==0)continue;
            options.add(values("action",action,"basis",values("type","APPROVED_CORRECTION","approvalOperationId",ticket.get("operation_id"),"approvalRevision",number(params.get("expectedRevision")),"approvedPayloadHash",hash(command)),"label",values("zh","已批准奖励调整","en","Approved reward correction","vi","Điều chỉnh phần thưởng đã được phê duyệt")));
        }
        return options;
    }
    private void audit(String action,String id,Map<String,Object> details){
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action(action).resourceType("PROMOTION_REWARD").resourceId(id).bizNo(id)
            .actorUsername("PROMOTION_ENGINE").actorType("SYSTEM").riskLevel("HIGH").result("SUCCESS").detail(details).build());
    }
}
