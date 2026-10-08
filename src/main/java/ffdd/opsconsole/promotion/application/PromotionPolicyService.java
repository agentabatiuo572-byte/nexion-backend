package ffdd.opsconsole.promotion.application;

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

    public Map<String,Object> get(String id,long version){return resolver.view(row(id,version,false));}
    public Map<String,Object> page(String kind,String state,String cursor,int limit){
        int size=Math.min(100,Math.max(1,limit));long offset=cursor==null?0:number(cursor);
        if(offset<0)throw new BizException(422,"PROMOTION_CURSOR_INVALID");
        var rows=db.list("SELECT * FROM nx_promotion_policy WHERE (? IS NULL OR kind=?) AND (? IS NULL OR status=?) ORDER BY policy_id,version LIMIT ? OFFSET ?",kind,kind,state,state,size+1,offset);
        boolean more=rows.size()>size;var items=rows.stream().limit(size).map(resolver::view).toList();
        return values("items",items,"hasMore",more,"nextCursor",more?String.valueOf(offset+size):null);
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
