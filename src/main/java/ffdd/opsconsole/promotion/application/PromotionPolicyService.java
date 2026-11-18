package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Service
@RequiredArgsConstructor
public class PromotionPolicyService {
    private final PromotionMapper db;
    private final PromotionContractValidator validator;
    private final PromotionPolicyResolver resolver;
    private final PromotionNativeContractResolver natives;
    private final AdminMapper admins;

    public Map<String,Object> get(String id,long version){return withApproverIdentity(List.of(resolver.view(row(id,version,false)))).get(0);}
    public Map<String,Object> page(String kind,String state,String cursor,int limit){
        int size=Math.min(100,Math.max(1,limit));long offset=cursor==null?0:number(cursor);
        if(offset<0)throw new BizException(422,"PROMOTION_CURSOR_INVALID");
        var rows=db.list("SELECT * FROM nx_promotion_policy WHERE (? IS NULL OR kind=?) AND (? IS NULL OR status=?) ORDER BY policy_id,version LIMIT ? OFFSET ?",kind,kind,state,state,size+1,offset);
        boolean more=rows.size()>size;var items=withApproverIdentity(rows.stream().limit(size).map(resolver::view).toList());
        return values("items",items,"hasMore",more,"nextCursor",more?String.valueOf(offset+size):null);
    }
    /**
     * Admin-only projection: the real approver of these policy rows, resolved from approved_by against
     * current non-deleted admins. approvedBy itself stays exactly as stored; when that account no longer
     * exists, or was never recorded, approvedByAdmin is null instead of a guessed name or the last editor.
     */
    private List<Map<String,Object>> withApproverIdentity(List<Map<String,Object>> views){
        Map<Long,AdminMapper.AdminIdIdentity> identities=identities(views.stream()
                .map(view->approvedById(view.get("approvedBy"))).filter(Objects::nonNull).distinct().toList());
        List<Map<String,Object>> enriched=new ArrayList<>(views.size());
        for(Map<String,Object> view:views){
            Long approvedBy=approvedById(view.get("approvedBy"));
            AdminMapper.AdminIdIdentity identity=approvedBy==null?null:identities.get(approvedBy);
            Map<String,Object> copy=new LinkedHashMap<>(view);
            copy.put("approvedByAdmin",identity==null?null:values("id",identity.id(),
                    "displayName",identity.displayName(),"username",identity.username()));
            Map<String,Object> execution=policyExecution(copy);
            if(execution!=null)copy.put("execution",execution);
            enriched.add(copy);
        }
        return enriched;
    }

    /**
     * Execution facts of the one approved REFUND contract, read-only and only on that exact content.
     * The stored v1 content still says MANUAL_REVIEW because that is the historical contract; what the
     * engine actually does for a confirmed whole-order refund is stated here instead of being edited
     * into the content, so legacy hashes and every other policy are untouched.
     */
    private Map<String,Object> policyExecution(Map<String,Object> view){
        Object raw=view.get("content");
        if(!(raw instanceof Map<?,?> content))return null;
        if(!"REFUND".equals(text(content.get("kind")))
                || !"PROMOTION_WALLET_REFUND_V1".equals(text(content.get("executorCode"))))return null;
        return values("basis","WHOLE_ORDER_REFUND_CONFIRMED",
                // Wallet assets (UVT/USDT) are recovered from the same asset's available balance.
                "walletRecovery","SAME_ASSET_AVAILABLE_BALANCE",
                // Gift devices follow their own path: the device is revoked and its tasks stopped,
                // while earnings already posted stay with the user and are booked as platform loss.
                "deviceRecovery","REVOKE_GIFT_DEVICE_CANCEL_OPEN_TASKS_KEEP_POSTED_EARNINGS",
                "insufficientRecovery","PLATFORM_LOSS_AND_CLOSE",
                "negativeBalance",false,
                "crossAssetDeduction",false,
                "terminalState","REVERSED",
                "manualReviewAppliesTo","APPROVED_CORRECTION",
                "unknownEvidence","ORIGINAL_REFUND_CHECKS");
    }

    private Map<Long,AdminMapper.AdminIdIdentity> identities(List<Long> ids){
        if(ids.isEmpty())return Map.of();
        try{
            Map<Long,AdminMapper.AdminIdIdentity> found=new LinkedHashMap<>();
            for(AdminMapper.AdminIdIdentity identity:admins.findIdentitiesByIds(ids)){
                if(identity!=null&&identity.id()!=null)found.put(identity.id(),identity);
            }
            return found;
        }catch(RuntimeException ignored){
            // A missing name must never fail the policy read; the stored number stays the only fact.
            return Map.of();
        }
    }

    private static Long approvedById(Object approvedBy){
        if(approvedBy==null)return null;
        try{
            long id=Long.parseLong(String.valueOf(approvedBy).trim());
            return id>0?id:null;
        }catch(NumberFormatException ignored){
            return null;
        }
    }

    public Map<String,Object> create(String id,Map<String,Object> request){
        validator.validate(id==null?"PolicyDraft":"PolicyVersionInput",request);
        var content=map(request.get("content"));long version=1;
        if(id!=null){
            var last=db.requiredRow("SELECT * FROM nx_promotion_policy WHERE policy_id=? ORDER BY version DESC LIMIT 1 FOR UPDATE",id);
            require(number(last.get("revision"))==number(request.get("expectedRevision")),"PROMOTION_CONCURRENT_CHANGE");
            require(content.get("kind").equals(last.get("kind")),"PROMOTION_POLICY_KIND_IMMUTABLE");version=number(last.get("version"))+1;
        }else id=id("PP");
        var evidence=values("evidenceRefs",request.get("evidenceRefs"),"resolvedDeviceRights",null,"nativeContracts",List.of());
        changed(db.write("INSERT INTO nx_promotion_policy(policy_id,version,kind,executor_code,content_json,content_hash,evidence_json) VALUES(?,?,?,?,?,?,?)",
            id,version,content.get("kind"),content.get("executorCode"),json(content),hash(content),json(evidence)));
        return PromotionAdminService.resource("POLICY",id+":"+version,1L);
    }
    public Map<String,Object> transition(String id,long version,String action,Map<String,Object> request,long actor){
        validator.validate("Action",request);var row=row(id,version,true);
        require(number(row.get("revision"))==number(request.get("expectedRevision")),"PROMOTION_CONCURRENT_CHANGE");
        var evidence=parse(row.get("evidence_json"));
        if("approve".equals(action)){
            require("DRAFT".equals(row.get("status")),"PROMOTION_POLICY_STATE_INVALID");
            String versionId=id+":"+version;
            String legacyTarget=version==1?"promotion-policies":id;
            // Older creation audits used the command target; their receipt identifies the exact version.
            var makers=db.list("""
                SELECT actor_id FROM nx_audit_log
                 WHERE resource_type='PROMOTION' AND action IN ('CREATEPOLICY','CREATEPOLICYVERSION')
                   AND result='SUCCESS' AND actor_type='ADMIN' AND actor_id>0 AND is_deleted=0
                   AND (resource_id=? OR (resource_id=? AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.resource.id'))=?))
                UNION
                SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.actorId')) AS SIGNED) AS actor_id
                  FROM nx_audit_log_archive
                 WHERE JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceType'))='PROMOTION'
                   AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.action')) IN ('CREATEPOLICY','CREATEPOLICYVERSION')
                   AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.result'))='SUCCESS'
                   AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.actorType'))='ADMIN'
                   AND content_sha256=SHA2(CAST(cold_payload AS CHAR),256)
                   AND JSON_EXTRACT(cold_payload,'$.id')=original_audit_id
                   AND (JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceId'))=?
                     OR (JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceId'))=? AND JSON_UNQUOTE(JSON_EXTRACT(
                            JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.detail')),'$.resource.id'))=?))
                LIMIT 2
                """,versionId,legacyTarget,versionId,versionId,legacyTarget,versionId);
            require(makers.size()==1,"PROMOTION_POLICY_MAKER_UNAVAILABLE");
            long maker=number(makers.get(0).get("actor_id"));
            require(maker>0,"PROMOTION_POLICY_MAKER_UNAVAILABLE");
            require(maker!=actor,"PROMOTION_MAKER_CHECKER_REQUIRED");
            natives.fixtureAllowed(row.get("fixture_run_id"));
            evidence(evidence.get("evidenceRefs"),actor);
            var content=parse(row.get("content_json"));validator.validate("PolicyContent",content);
            var resolved=natives.resolve(content);evidence.putAll(resolved);
            changed(db.write("UPDATE nx_promotion_policy SET status='APPROVED',approval_ref=?,approved_by=?,approved_at=NOW(6),evidence_json=?,revision=revision+1 WHERE policy_id=? AND version=? AND revision=?",
                id("PPA"),actor,json(evidence),id,version,row.get("revision")));
        }else{
            require("APPROVED".equals(row.get("status")),"PROMOTION_POLICY_STATE_INVALID");
            changed(db.write("UPDATE nx_promotion_policy SET status='REVOKED',revision=revision+1 WHERE policy_id=? AND version=? AND revision=?",id,version,row.get("revision")));
        }
        return PromotionAdminService.resource("POLICY",id+":"+version,number(row.get("revision"))+1);
    }
    public void evidence(Object refs,long actor){
        if(!(refs instanceof List<?> items)||items.isEmpty())throw new BizException(422,"PROMOTION_POLICY_EVIDENCE_REQUIRED");
        for(Object ref:items){
            // Evidence is an existing owned audit fact or an approved A2 object visible in the growth domain.
            long facts=db.count("SELECT COUNT(*) FROM nx_audit_log WHERE CAST(id AS CHAR)=? AND is_deleted=0 AND actor_id=?",text(ref),actor);
            long tickets=db.count("SELECT COUNT(*) FROM nx_audit_operation_ticket WHERE operation_id=? AND is_deleted=0 AND status='approved' AND source_domain='H'",text(ref));
            if(facts+tickets==0)throw new BizException(422,"PROMOTION_POLICY_EVIDENCE_UNAVAILABLE");
        }
    }
    private Map<String,Object> row(String id,long version,boolean lock){return db.requiredRow("SELECT * FROM nx_promotion_policy WHERE policy_id=? AND version=?"+(lock?" FOR UPDATE":""),id,version);}
}
