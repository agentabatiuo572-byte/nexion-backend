package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.promotion.domain.PromotionRules;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Service
@RequiredArgsConstructor
public class PromotionAdminService {
    private final PromotionMapper db;
    private final PromotionContractValidator validator;
    private final PromotionPolicyResolver resolver;
    private final PromotionPolicyService policies;
    private final PromotionNativeContractResolver natives;
    private final PromotionEvaluationService evaluator;
    private final PromotionQuoteService quotes;
    private final PromotionRewardService rewards;
    private final PromotionOrderService orders;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final A2RuntimePolicy reasonPolicy;
    private final ffdd.opsconsole.platform.application.OpsOptionsService options;

    public static long actor(){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||!(auth.getDetails() instanceof Map<?,?> d)||!"ADMIN".equals(d.get("subjectType")))throw new BizException(403,"PROMOTION_ADMIN_REQUIRED");
        return number(auth.getPrincipal());
    }
    public static boolean has(String permission){var auth=SecurityContextHolder.getContext().getAuthentication();return auth!=null&&auth.getAuthorities().stream().anyMatch(a->permission.equals(a.getAuthority()));}
    public static void permission(String operation){if(!has(permissionFor(operation)))throw new BizException(403,"PROMOTION_PERMISSION_DENIED");}
    public static String permissionFor(String op){return "growth_promotion_"+switch(op){
        case "createPromotion","saveDraft","createDraftVersion","copyPromotion"->"edit";
        case "submitPromotion","withdrawPromotion"->"submit";case "approvePromotion","rejectPromotion"->"approve";case "publishPromotion"->"publish";
        case "pausePromotion","resumePromotion"->"pause";case "endPromotion"->"end";case "archivePromotion"->"archive";
        case "createPolicy","createPolicyVersion"->"policy_write";case "approvePolicy","revokePolicy"->"policy_approve";
        case "retryReward"->"reward_retry";case "reconcileReward"->"reward_reconcile";case "cancelReward"->"reward_cancel";
        case "reverseReward"->"reward_reverse";case "resolveReward"->"reward_resolve";case "createExport"->"metrics_export";
        default->throw new BizException(422,"PROMOTION_COMMAND_OPERATION_INVALID");};}
    private static String scope(long actor,String op,String target){return ("PROMOTION_ADMIN:"+actor+":"+op+":"+sha256(target).substring(0,32)).toUpperCase(Locale.ROOT);}
    @SuppressWarnings("unchecked")
    public Map<String,Object> command(String operation,String target,String key,Map<String,Object> request,Supplier<Map<String,Object>> action){
        long actor=actor();permission(operation);validator.validate("CommandKey",key);
        var attempted=new java.util.concurrent.atomic.AtomicBoolean();
        try{return idempotency.executeRetained(scope(actor,operation,target),key,hash(request),Map.class,()->{
            reasonPolicy.validateReason(text(request.get("reason")));
            String command=id("PC");Instant now=Instant.now();var before=before(target);
            attempted.set(true);var resource=action.get();var after=before(text(resource.get("id")));
            String auditedTarget="PROMOTION".equals(resource.get("type"))?text(resource.get("id")):target;
            audit.recordRequired(AuditLogWriteRequest.builder().action(operation).resourceType("PROMOTION").resourceId(auditedTarget)
                .actorId(actor).actorType("ADMIN").method("POST").path("/api/admin/growth").result("SUCCESS").riskLevel("HIGH")
                .detail(values("commandId",command,"idempotencyKey",key,"reason",request.get("reason"),"evidenceRefs",request.get("evidenceRefs"),"requestHash",hash(request),"beforeHash",hash(before),"afterHash",hash(after),"resource",resource)).build());
            auditSnapshot(command,auditedTarget,"before",before,actor);auditSnapshot(command,auditedTarget,"after",after,actor);
            return receipt(command,operation,target,key,"SUCCEEDED",resource,null,now);
        });}catch(RuntimeException failure){
            String stage=switch(operation){case "retryReward"->"ISSUE";case "reconcileReward"->"RECONCILE";case "cancelReward"->"CANCEL";case "reverseReward"->"REVERSE";case "resolveReward"->"RESOLVE";default->null;};
            if(attempted.get()&&stage!=null){String verb=operation.substring(0,operation.length()-"Reward".length());String command="PC-"+sha256(actor+":"+verb+":"+target+":"+key).substring(0,48);
                // The retained executor has rolled back before this separate attempt transaction starts.
                try{rewards.recordActionFailure(target,command,stage,failure.getMessage());}catch(RuntimeException evidenceFailure){failure.addSuppressed(evidenceFailure);}
            }
            throw failure;
        }
    }
    private void auditSnapshot(String command,String target,String side,Object value,long actor){
        String serialized=json(value);for(int start=0,index=0;start<serialized.length();start+=1600,index++)
            audit.recordRequired(AuditLogWriteRequest.builder().action("PROMOTION_COMMAND_SNAPSHOT").resourceType("PROMOTION").resourceId(target).actorId(actor).actorType("ADMIN").result("SUCCESS").riskLevel("HIGH")
                .detail(values("commandId",command,"side",side,"part",index,"contentHash",hash(value),"content",serialized.substring(start,Math.min(start+1600,serialized.length())))).build());
    }
    private Object before(String target){
        var root=db.one("SELECT * FROM nx_promotion WHERE activity_id=?",target);
        if(root!=null)return values("root",root,"versions",db.list("SELECT * FROM nx_promotion_version WHERE activity_id=? ORDER BY version",target));
        int colon=target.lastIndexOf(':');if(colon>0){String id=target.substring(0,colon);String v=target.substring(colon+1);
            if(v.matches("[1-9][0-9]*")){var version=db.one("SELECT * FROM nx_promotion_version WHERE activity_id=? AND version=?",id,number(v));if(version!=null)return version;
                return db.one("SELECT * FROM nx_promotion_policy WHERE policy_id=? AND version=?",id,number(v));}}
        var reward=db.one("SELECT * FROM nx_promotion_reward WHERE obligation_id=?",target);if(reward!=null)return reward;
        return db.list("SELECT * FROM nx_promotion_policy WHERE policy_id=? ORDER BY version",target);
    }
    public Map<String,Object> recover(String key,String operation,String target){
        long actor=actor();permission(operation);validator.validate("CommandKey",key);
        String scope=scope(actor,operation,target);var record=db.one("SELECT request_hash,created_at,updated_at FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=? AND is_deleted=0",scope,key);
        if(record==null)return receipt("missing-"+sha256(key).substring(0,24),operation,target,key,"NOT_FOUND",null,null,Instant.now());
        var result=idempotency.recoveryResult(scope,key,text(record.get("request_hash")),Map.class);
        if(result.response()!=null)return map(result.response());
        String state=switch(result.status()){case SUCCEEDED,UNKNOWN,MISMATCH->"OUTCOME_UNKNOWN";case PROCESSING->"PROCESSING";case FAILED->"FAILED";case NOT_FOUND->"NOT_FOUND";};
        return receipt("recovery-"+sha256(key).substring(0,24),operation,target,key,state,null,
            "FAILED".equals(state)?values("code",409,"message","PROMOTION_COMMAND_FAILED","data",null):null,instant(record.get("created_at")));
    }
    private static Map<String,Object> receipt(String id,String op,String target,String key,String state,Object resource,Object error,Instant time){return values("commandId",id,"operation",op,"targetId",target,"idempotencyKey",key,"status",state,"resource",resource,"order",null,"error",error,"createdAt",time.toString(),"updatedAt",Instant.now().toString());}
    public static Map<String,Object> resource(String type,String id,Long revision){return values("type",type,"id",id,"revision",revision);}

    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> get(String id){return readPromotion(id,Instant.now());}
    private Map<String,Object> readPromotion(String id,Instant asOf){
        var p=db.activity(id,false);Object current=p.get("draft_version")!=null?p.get("draft_version"):p.get("active_version");
        Object effective=p.get("active_version")!=null?p.get("active_version"):p.get("draft_version");
        var config=effective==null?Map.<String,Object>of():parse(db.version(id,number(effective),false).get("contract_json"));
        var draft=p.get("draft_version")==null?null:db.version(id,number(p.get("draft_version")),false);
        var impact=PromotionMetricsService.readImpact(db,id,asOf);
        return values("activityId",id,"eventCode",p.get("event_code"),"revision",number(p.get("revision")),"state",p.get("status"),"activeVersion",p.get("active_version"),"draftVersion",p.get("draft_version"),"draftVersionState",draft==null?null:draft.get("status"),
            "name",config.get("name"),"category",config.get("category"),"template",config.get("template"),"startsAt",config.get("startsAt"),"endsAt",config.get("endsAt"),"displayTimezone",config.get("displayTimezone"),"updatedAt",time(p.get("updated_at")),
            "current",current==null?null:version(id,number(current)),"impact",impact,"reserved",impact.get("reserved"),"issued",impact.get("issued"),"unresolved",impact.get("unresolved"),"asOf",asOf.toString(),"serverTime",asOf.toString(),"fixture",!text(p.get("fixture_run_id")).isEmpty());
    }
    public Map<String,Object> version(String id,long version){var v=db.version(id,version,false);return values("activityId",id,"version",version,"revision",number(v.get("revision")),"state",v.get("status"),"contentHash",v.get("content_hash"),"draft",parse(v.get("contract_json")),"approvedBy",nullableText(v.get("approved_by")),"approvedAt",time(v.get("approved_at")),"publishedBy",nullableText(v.get("published_by")),"publishedAt",time(v.get("published_at")),"policyResolutionErrors",List.of());}
    private static String nullableText(Object v){return v==null?null:text(v);}private static String time(Object v){return v==null?null:instant(v).toString();}
    public Map<String,Object> catalog(){
        actor();var skus=new ArrayList<Map<String,Object>>();var nativeOptions=new ArrayList<Map<String,Object>>();boolean canReadNative=has("growth_promotion_policy_read")||has("growth_promotion_policy_write");
        if(canReadNative){
            nativeOptions.add(natives.catalogOption("ORDER_CONTRACT","WALLET_ORDER_V1",values("zh","钱包订单与整单退款","en","Wallet orders and whole-order refunds","vi","Đơn hàng ví và hoàn toàn bộ đơn"),null));
            nativeOptions.add(natives.catalogOption("EARNINGS_RELEASE","risk.k1.release.version",values("zh","现行收益释放规则","en","Current earnings release rules","vi","Quy tắc giải phóng thu nhập hiện hành"),null));
            nativeOptions.add(natives.catalogOption("A2_POLICY","admin.a2.schema_version",values("zh","现行审批权限规则","en","Current approval authorization rules","vi","Quy tắc quyền phê duyệt hiện hành"),null));
        }
        for(var p:db.list("SELECT p.*,s.purchase_gate_generation FROM nx_product p JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0 WHERE p.is_deleted=0 ORDER BY p.product_no")){
            String inventory=text(p.get("inventory_mode"));if(!Set.of("FINITE","UNLIMITED").contains(inventory))throw new BizException(503,"PROMOTION_INVENTORY_MODE_UNAVAILABLE");
            var sku=db.requiredRow("SELECT * FROM nx_admin_device_sku WHERE sku_id=? AND is_deleted=0",p.get("product_no"));String giftFailure=PromotionNativeContractResolver.giftFailure(p,sku);
            if(canReadNative)nativeOptions.add(natives.catalogOption("E1_PRODUCT",text(p.get("product_no")),localized(text(sku.get("name"))),giftFailure));
            var reason=giftFailure==null?null:values("zh","当前商品的赠送权益尚未具备执行条件，请选择其他赠品。","en","This product's gift rights are not executable. Choose another gift.","vi","Quyền quà tặng của sản phẩm chưa thể thực hiện. Hãy chọn quà khác.");
            skus.add(values("productNo",p.get("product_no"),"productRevision",Math.max(1,number(p.get("purchase_gate_generation"))),"name",localized(text(p.get("name"))),"available","ACTIVE".equals(p.get("status"))&&number(p.get("store_visible"))==1,"inventoryMode",inventory,"availableQuantity","UNLIMITED".equals(inventory)?null:number(p.get("stock")),"giftEligible",giftFailure==null,"giftUnavailabilityReason",reason));
        }
        var ranks=db.list("SELECT rank_code,title_cn,title_en FROM nx_v_rank_config WHERE status=1 AND is_deleted=0 ORDER BY sort_order,rank_code").stream().map(r->values("id",r.get("rank_code"),"name",values("zh",r.get("title_cn"),"en",r.get("title_en"),"vi",r.get("title_en")))).toList();
        var markets=options.options("user","regions").getData().stream().filter(o->!o.disabled()).map(o->values("id",o.value(),"name",localized(o.label()))).toList();
        var allowed=SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().map(a->a.getAuthority()).filter(a->a.startsWith("growth_promotion_")||"user_c1_read".equals(a)).sorted().toList();
        var visible=has("growth_promotion_policy_read")?db.list("SELECT * FROM nx_promotion_policy ORDER BY policy_id,version").stream().map(resolver::view).toList():List.of();
        return values("assets",List.of(values("asset","USDT","scale",6,"minimumUnit","0.000001"),values("asset","NEX","scale",6,"minimumUnit","0.000001")),"skus",skus,"ranks",ranks,"markets",markets,"policies",visible,"nativeContracts",nativeOptions,"capabilities",allowed,"serverTime",Instant.now().toString());
    }
    private static Map<String,Object> localized(String value){return values("zh",value,"en",value,"vi",value);}
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> simulate(String id,Map<String,Object> request){
        if(request.get("sampleAccountId")!=null&&!has("user_c1_read"))throw new BizException(403,"PROMOTION_ACCOUNT_SAMPLE_FORBIDDEN");
        validator.validate("SimulateInput",request);var p=db.activity(id,false);var c=map(request.get("draft"));
        validator.validate("PromotionContract",c);validateWindow(c);validateConfiguration(c);
        return new PromotionAdminEvaluation(db,validator,resolver,evaluator,quotes).simulate(id,p,c,request);
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> audiencePreview(String id,Map<String,Object> request){
        validator.validate("AudiencePreviewInput",request);db.activity(id,false);
        var result=new PromotionAdminEvaluation(db,validator,resolver,evaluator,quotes).audience(map(request.get("draft")));
        validator.validate("AudiencePreview",result);return result;
    }
    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,rollbackFor=Exception.class)
    public Map<String,Object> page(Map<String,Object> filters,String querySnapshot,String cursor,int limit){
        long actor=actor();if(!has("growth_promotion_read"))throw new BizException(403,"PROMOTION_PERMISSION_DENIED");
        field(limit>=1&&limit<=100,"limit");var query=normalizeListQuery(filters);long offset=cursor==null?0:number(cursor);field(offset>=0,"cursor");
        Map<String,Object> snapshot;
        if(querySnapshot!=null){
            validator.validate("Id",querySnapshot);snapshot=db.requiredRow("SELECT * FROM nx_promotion_list_snapshot WHERE snapshot_id=? AND actor_id=?",querySnapshot,actor);
            require(instant(snapshot.get("expires_at")).isAfter(Instant.now()),"PROMOTION_LIST_SNAPSHOT_EXPIRED");require(hash(parse(snapshot.get("query_json"))).equals(hash(query)),"PROMOTION_LIST_QUERY_CHANGED");
        }else{
            field(cursor==null,"querySnapshot");Instant asOf=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);String id=id("PLS");var rows=new ArrayList<Map<String,Object>>();
            String sql="SELECT p.activity_id FROM nx_promotion p JOIN nx_promotion_version v ON v.activity_id=p.activity_id AND v.version=COALESCE(p.active_version,p.draft_version) WHERE 1=1";var args=new ArrayList<Object>();
            for(String key:List.of("activityId","state","category","template"))if(query.get(key)!=null){
                String column=switch(key){case "activityId"->"p.activity_id";case "state"->"p.status";default->"JSON_UNQUOTE(JSON_EXTRACT(v.contract_json,'$."+key+"'))";};sql+=" AND "+column+"=?";args.add(query.get(key));
            }
            String name="COALESCE(JSON_UNQUOTE(JSON_EXTRACT(v.contract_json,'$.name')),'')";
            if(query.get("name")!=null){sql+=" AND LOWER("+name+") LIKE ? ESCAPE '!'";args.add(like(text(query.get("name"))));}
            if(query.get("query")!=null){sql+=" AND (LOWER("+name+") LIKE ? ESCAPE '!' OR LOWER(p.activity_id) LIKE ? ESCAPE '!' OR LOWER(p.event_code) LIKE ? ESCAPE '!')";for(int i=0;i<3;i++)args.add(like(text(query.get("query"))));}
            if(query.get("from")!=null){sql+=" AND v.ends_at>? AND v.starts_at<?";args.add(timestamp(instant(query.get("from"))));args.add(timestamp(instant(query.get("to"))));}
            sql+=" ORDER BY "+switch(text(query.get("sort"))){case "NAME_ASC"->"LOWER("+name+") ASC,p.activity_id ASC";case "STARTS_ASC"->"v.starts_at ASC,p.activity_id ASC";default->"p.updated_at DESC,p.activity_id ASC";};
            for(var row:db.list(sql,args.toArray()))rows.add(readPromotion(text(row.get("activity_id")),asOf));
            var summary=PromotionMetricsService.summarizeActivities(rows,asOf);
            changed(db.write("INSERT INTO nx_promotion_list_snapshot(snapshot_id,actor_id,query_json,rows_json,summary_json,total,as_of,expires_at) VALUES(?,?,?,?,?,?,?,?)",id,actor,json(query),json(rows),json(summary),rows.size(),timestamp(asOf),timestamp(asOf.plusSeconds(1800))));
            snapshot=db.requiredRow("SELECT * FROM nx_promotion_list_snapshot WHERE snapshot_id=? AND actor_id=?",id,actor);
        }
        var rows=maps(parse("{\"rows\":"+text(snapshot.get("rows_json"))+"}").get("rows"));field(offset<=rows.size(),"cursor");int end=(int)Math.min(rows.size(),offset+limit);boolean more=end<rows.size();
        return values("items",rows.subList((int)offset,end),"hasMore",more,"nextCursor",more?String.valueOf(end):null,"total",number(snapshot.get("total")),"query",query,"querySnapshot",snapshot.get("snapshot_id"),"asOf",instant(snapshot.get("as_of")).toString(),"expiresAt",instant(snapshot.get("expires_at")).toString(),"summary",parse(snapshot.get("summary_json")));
    }
    public Map<String,Object> normalizeListQuery(Map<String,Object> filters){
        var query=new LinkedHashMap<String,Object>();filters.forEach((key,value)->{if(value!=null){String text=text(value).trim();if(!text.isEmpty())query.put(key,text);}});validator.validate("PromotionListQuery",query);
        boolean time=query.containsKey("from")||query.containsKey("to")||query.containsKey("timezone");
        if(time){field(query.containsKey("from")&&query.containsKey("to")&&query.containsKey("timezone"),"from/to/timezone");try{Instant from=instant(query.get("from")),to=instant(query.get("to"));ZoneId.of(text(query.get("timezone")));field(to.isAfter(from),"to");query.put("from",from.toString());query.put("to",to.toString());}catch(RuntimeException e){throw new BizException(422,"PROMOTION_FIELD_INVALID:from/to/timezone");}}
        query.putIfAbsent("sort","UPDATED_DESC");return query;
    }
    private static String like(String text){return "%"+text.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    public Map<String,Object> versions(String id,String cursor,int limit){
        db.activity(id,false);int size=Math.min(100,Math.max(1,limit));long after=cursor==null?0:number(cursor);
        var rows=db.list("SELECT version FROM nx_promotion_version WHERE activity_id=? AND version>? ORDER BY version LIMIT ?",id,after,size+1);boolean more=rows.size()>size;
        var items=rows.stream().limit(size).map(r->version(id,number(r.get("version")))).toList();return values("items",items,"hasMore",more,"nextCursor",more?text(items.get(items.size()-1).get("version")):null);
    }
    public Map<String,Object> create(Map<String,Object> request){validator.validate("CreatePromotion",request);return createDraft(map(request.get("draft")));}
    private Map<String,Object> createDraft(Map<String,Object> draft){
        validator.validate("Draft",draft);validateWindow(draft);String id=id("PROMO");
        changed(db.write("INSERT INTO nx_promotion(activity_id,event_code,category,template,draft_version) VALUES(?,?,?,?,1)",id,id,draft.get("category"),draft.get("template")));
        insertVersion(id,1,draft);return resource("PROMOTION",id,1L);
    }
    private void insertVersion(String id,long version,Map<String,Object> draft){changed(db.write("INSERT INTO nx_promotion_version(activity_id,version,starts_at,ends_at,contract_json,content_hash) VALUES(?,?,?,?,?,?)",id,version,ts(draft.get("startsAt")),ts(draft.get("endsAt")),json(draft),hash(draft)));}
    private static Object ts(Object value){return value==null?null:timestamp(instant(value));}
    public Map<String,Object> save(String id,Map<String,Object> request){
        validator.validate("DraftWrite",request);var p=db.activity(id,true);require(!Set.of("ENDED","ARCHIVED").contains(text(p.get("status"))),"PROMOTION_READ_ONLY");
        require(p.get("draft_version")!=null,"PROMOTION_DRAFT_MISSING");var v=db.version(id,number(p.get("draft_version")),true);
        require(number(v.get("revision"))==number(request.get("expectedRevision")),"PROMOTION_CONCURRENT_CHANGE");require("DRAFT".equals(v.get("status")),"PROMOTION_VERSION_READ_ONLY");
        var draft=mergeDraft(parse(v.get("contract_json")),map(request.get("draft")));validator.validate("Draft",draft);validateWindow(draft);identities(id,draft,false);
        changed(db.write("UPDATE nx_promotion_version SET contract_json=?,content_hash=?,starts_at=?,ends_at=?,status='DRAFT',approved_by=NULL,approved_at=NULL,approval_ref=NULL,resolved_policies_json=NULL,revision=revision+1 WHERE activity_id=? AND version=? AND revision=?",
            json(draft),hash(draft),ts(draft.get("startsAt")),ts(draft.get("endsAt")),id,v.get("version"),v.get("revision")));
        changed(db.write("UPDATE nx_promotion SET updated_at=NOW(6) WHERE activity_id=?",id));return resource("VERSION",id+":"+v.get("version"),number(v.get("revision"))+1);
    }
    public static Map<String,Object> mergeDraft(Map<String,Object> old,Map<String,Object> patch){
        boolean changed=(patch.containsKey("category")&&!Objects.equals(old.get("category"),patch.get("category")))||(patch.containsKey("template")&&!Objects.equals(old.get("template"),patch.get("template")));
        if(changed)for(String key:List.of("rules","buyerAudience","inviterAudience","combinationMatch","perPersonLimit","maxRewardUnitsPerOrder","budgets","shortagePolicy","policies","activityLimit"))
            if(old.get(key)!=null&&!patch.containsKey(key))throw new BizException(422,"PROMOTION_TEMPLATE_CLEAR_REQUIRED:"+key);
        var merged=copy(old);merged.putAll(patch);return merged;
    }
    public static void validateWindow(Map<String,Object> draft){
        if(draft.get("startsAt")!=null||draft.get("endsAt")!=null){try{ZoneId.of(required(draft.get("displayTimezone"),"displayTimezone"));}catch(RuntimeException e){throw new BizException(422,"PROMOTION_TIMEZONE_REQUIRED");}}
        if(draft.get("startsAt")!=null&&draft.get("endsAt")!=null&&!instant(draft.get("endsAt")).isAfter(instant(draft.get("startsAt"))))throw new BizException(422,"PROMOTION_TIME_WINDOW_INVALID");
    }
    public Map<String,Object> newVersion(String id,Map<String,Object> request,boolean copy){
        validator.validate("Action",request);var p=db.activity(id,true);require(number(p.get("revision"))==number(request.get("expectedRevision")),"PROMOTION_CONCURRENT_CHANGE");
        Object source=p.get("draft_version")!=null?p.get("draft_version"):p.get("active_version");require(source!=null,"PROMOTION_VERSION_MISSING");var draft=parse(db.version(id,number(source),false).get("contract_json"));
        if(copy)return createDraft(draft);
        require(!Set.of("ENDED","ARCHIVED").contains(text(p.get("status")))&&p.get("draft_version")==null,"PROMOTION_DRAFT_EXISTS_OR_READ_ONLY");
        long version=db.count("SELECT COALESCE(MAX(version),0)+1 FROM nx_promotion_version WHERE activity_id=?",id);insertVersion(id,version,draft);
        changed(db.write("UPDATE nx_promotion SET draft_version=?,revision=revision+1,updated_at=NOW(6) WHERE activity_id=?",version,id));return resource("VERSION",id+":"+version,1L);
    }
    public Map<String,Object> versionAction(String id,String action,Map<String,Object> request){
        validator.validate("PublishAction",request);var p=db.activity(id,true);long ver=number(request.get("version"));var v=db.version(id,ver,true);
        require(number(v.get("revision"))==number(request.get("expectedRevision")),"PROMOTION_CONCURRENT_CHANGE");
        require(p.get("draft_version")!=null&&number(p.get("draft_version"))==ver&&!Set.of("ENDED","ARCHIVED").contains(text(p.get("status"))),"PROMOTION_VERSION_NOT_CURRENT");
        String current=text(v.get("status"));String expected=switch(action){case "submit"->"DRAFT";case "approve","reject"->"PENDING_APPROVAL";case "publish"->"APPROVED";case "withdraw"->current;default->throw new BizException(422,"PROMOTION_ACTION_INVALID");};require(expected.equals(current)&&(!"withdraw".equals(action)||Set.of("PENDING_APPROVAL","APPROVED").contains(current)),"PROMOTION_VERSION_STATE_INVALID");
        var draft=parse(v.get("contract_json"));Map<String,Object> resolved=Map.of();
        if(!Set.of("reject","withdraw").contains(action)){validator.validate("PromotionContract",draft);validateWindow(draft);natives.fixtureAllowed(p.get("fixture_run_id"));resolved=governancePolicies(draft,true);semantic(id,draft,resolved);}
        long actor=actor();
        if("approve".equals(action)||"publish".equals(action)){
            var auth=PromotionPolicyResolver.content(resolved,"authorization");
            if(Boolean.TRUE.equals(auth.get("separateMakerChecker"))){
                if("publish".equals(action))require(number(v.get("approved_by"))!=actor,"PROMOTION_MAKER_CHECKER_REQUIRED");
                else require(db.count("SELECT COUNT(*) FROM nx_audit_log WHERE resource_type='PROMOTION' AND resource_id=? AND actor_id=? AND action IN ('CREATEPROMOTION','SAVEDRAFT','CREATEDRAFTVERSION','COPYPROMOTION','SUBMITPROMOTION') AND is_deleted=0",id,actor)==0,"PROMOTION_MAKER_CHECKER_REQUIRED");
            }
        }
        if("publish".equals(action)){
            require(instant(draft.get("endsAt")).isAfter(Instant.now()),"PROMOTION_ALREADY_ENDED");identities(id,draft,true);budgets(id,draft);
            changed(db.write("UPDATE nx_promotion_version SET status='PUBLISHED',published_by=?,published_at=NOW(6),resolved_policies_json=?,revision=revision+1 WHERE activity_id=? AND version=?",actor,json(resolved),id,ver));
            changed(db.write("UPDATE nx_promotion SET category=?,template=?,active_version=?,draft_version=NULL,status=?,revision=revision+1,updated_at=NOW(6) WHERE activity_id=?",draft.get("category"),draft.get("template"),ver,publicationState(text(p.get("status")),instant(draft.get("startsAt"))),id));
        }else if("approve".equals(action))changed(db.write("UPDATE nx_promotion_version SET status='APPROVED',approved_by=?,approved_at=NOW(6),approval_ref=?,revision=revision+1 WHERE activity_id=? AND version=?",actor,id("PA"),id,ver));
        else changed(db.write("UPDATE nx_promotion_version SET status=?,approved_by=NULL,approved_at=NULL,approval_ref=NULL,resolved_policies_json=NULL,revision=revision+1 WHERE activity_id=? AND version=?","submit".equals(action)?"PENDING_APPROVAL":"DRAFT",id,ver));
        return resource("VERSION",id+":"+ver,number(v.get("revision"))+1);
    }
    public static String publicationState(String old,Instant starts){return "PAUSED".equals(old)?"PAUSED":starts.isAfter(Instant.now())?"SCHEDULED":"ACTIVE";}
    public Map<String,Object> state(String id,String action,Map<String,Object> request){
        validator.validate("Action",request);var p=db.activity(id,true);require(number(p.get("revision"))==number(request.get("expectedRevision")),"PROMOTION_CONCURRENT_CHANGE");String old=text(p.get("status")),next;
        switch(action){
            case "pause"->{require(Set.of("ACTIVE","SCHEDULED").contains(old),"PROMOTION_STATE_INVALID");next="PAUSED";}
            case "resume"->{require("PAUSED".equals(old),"PROMOTION_STATE_INVALID");var v=db.version(id,number(p.get("active_version")),true);var c=parse(v.get("contract_json"));validator.validate("PromotionContract",c);semantic(id,c,governancePolicies(c,true));require(instant(c.get("endsAt")).isAfter(Instant.now()),"PROMOTION_ALREADY_ENDED");resumeResources(id,c);next=publicationState("DRAFT",instant(c.get("startsAt")));}
            case "end"->{require(Set.of("ACTIVE","SCHEDULED","PAUSED").contains(old),"PROMOTION_STATE_INVALID");next="ENDED";}
            case "archive"->{require("ENDED".equals(old),"PROMOTION_STATE_INVALID");require(db.count("SELECT COUNT(*) FROM nx_promotion_reservation WHERE activity_id=? AND status='RESERVED'",id)==0&&db.count("SELECT COUNT(*) FROM nx_promotion_reward WHERE activity_id=? AND status NOT IN ('ISSUED','CANCELLED','REVERSED')",id)==0,"PROMOTION_OBLIGATIONS_OPEN");next="ARCHIVED";}
            default->throw new BizException(422,"PROMOTION_ACTION_INVALID");
        }
        changed(db.write("UPDATE nx_promotion SET status=?,revision=revision+1,updated_at=NOW(6) WHERE activity_id=?",next,id));return resource("PROMOTION",id,number(p.get("revision"))+1);
    }
    private void semantic(String id,Map<String,Object> c,Map<String,Object> resolved){
        validateConfiguration(c);
        String template=text(c.get("template"));var buyer=map(c.get("buyerAudience"));
        field(("DIRECT_REFERRAL".equals(template))=="REFERRAL".equals(c.get("category")),"category");
        if(Set.of("FIRST_PURCHASE","DIRECT_REFERRAL").contains(template))field("NEVER_PAID".equals(buyer.get("purchaseHistory"))&&resolved.get("$firstPurchase")!=null,"policies.firstPurchase");
        if("REPURCHASE".equals(template))field("HAS_VALID_PURCHASE".equals(buyer.get("purchaseHistory"))&&buyer.get("lastValidPurchaseAge")!=null,"buyerAudience.lastValidPurchaseAge");
        if("DIRECT_REFERRAL".equals(template))field(c.get("inviterAudience")!=null,"inviterAudience");
        for(String audience:List.of("buyerAudience","inviterAudience"))if(c.get(audience)!=null){var a=map(c.get(audience));
            if(!"ANY".equals(a.get("devicePresence")))field(a.get("deviceAudiencePolicy")!=null,audience+".deviceAudiencePolicy");
        }
        identities(id,c,false);Set<String> skus=new HashSet<>();for(var r:maps(c.get("rules"))){field(skus.add(text(r.get("productNo"))),"rules.productNo");db.product(text(r.get("productNo")),false);field(r.get("buyerReward")!=null||r.get("inviterReward")!=null,"rules.buyerReward");}
    }
    private Map<String,Object> governancePolicies(Map<String,Object> contract,boolean lock){
        try{return resolver.resolve(contract,lock);}catch(BizException failure){
            if(Set.of(404,409,422).contains(failure.getCode()))throw new BizException(422,"PROMOTION_FIELD_INVALID:policies:"+failure.getMessage());
            throw failure;
        }
    }
    public static void validateConfiguration(Map<String,Object> contract){
        String template=text(contract.get("template"));
        for(String role:List.of("buyerAudience","inviterAudience"))if(contract.get(role)!=null){
            var audience=map(contract.get(role));
            for(String name:List.of("registrationAge","lastValidPurchaseAge")){
                var range=map(audience.get(name));
                field(range.get("minDays")==null||range.get("maxDays")==null||number(range.get("minDays"))<=number(range.get("maxDays")),role+"."+name);
            }
            field(!Boolean.TRUE.equals(audience.get("neverPurchased"))||!"HAS_VALID_PURCHASE".equals(audience.get("purchaseHistory")),role+".purchaseHistory");
        }
        var buyer=map(contract.get("buyerAudience"));
        if("REPURCHASE".equals(template)){var age=map(buyer.get("lastValidPurchaseAge"));field(age.get("minDays")!=null||age.get("maxDays")!=null,"buyerAudience.lastValidPurchaseAge");}
        if("DIRECT_REFERRAL".equals(template))field(!"ABSENT".equals(buyer.get("sponsor")),"buyerAudience.sponsor");
        Map<String,BigDecimal> budgets=new HashMap<>();
        for(var budget:maps(contract.get("budgets"))){String asset=text(budget.get("asset")),key=asset+":"+text(budget.get("productNo"));field(!budgets.containsKey(key),"budgets");budgets.put(key,decimal(budget.get("total")));}
        for(var rule:maps(contract.get("rules")))for(String role:List.of("buyerReward","inviterReward"))if(rule.get(role)!=null){
            field(!"inviterReward".equals(role)||"DIRECT_REFERRAL".equals(template),"rules.inviterReward");
            var reward=map(rule.get(role));String type=text(reward.get("type")),key=type+":"+("DEVICE".equals(type)?text(reward.get("giftProductNo")):"");
            BigDecimal needed=decimal(reward.get("DEVICE".equals(type)?"quantity":"amount"));
            field(budgets.containsKey(key)&&budgets.get(key).compareTo(needed)>=0,"budgets."+key);
        }
    }
    private static void field(boolean valid,String path){if(!valid)throw new BizException(422,"PROMOTION_FIELD_INVALID:"+path);}
    private void resumeResources(String id,Map<String,Object> contract){
        for(var rule:maps(contract.get("rules"))){
            var product=db.product(text(rule.get("productNo")),false);
            require("ACTIVE".equals(product.get("status"))&&number(product.get("store_visible"))==1,"PROMOTION_PRODUCT_UNAVAILABLE");
            require("UNLIMITED".equals(product.get("inventory_mode"))||number(product.get("stock"))>=number(rule.get("minBuyQty")),"PROMOTION_PRODUCT_STOCK_EXHAUSTED");
            for(String role:List.of("buyerReward","inviterReward"))if(rule.get(role)!=null){
                var reward=map(rule.get(role));String asset=text(reward.get("type")),gift="DEVICE".equals(asset)?text(reward.get("giftProductNo")):"";
                BigDecimal needed=decimal(reward.get("DEVICE".equals(asset)?"quantity":"amount"));
                var budget=db.requiredRow("SELECT * FROM nx_promotion_budget WHERE activity_id=? AND asset=? AND product_no=? FOR UPDATE",id,asset,gift);
                BigDecimal available=decimal(budget.get("total")).subtract(decimal(budget.get("reserved"))).subtract(decimal(budget.get("committed"))).subtract(decimal(budget.get("issued"))).add(decimal(budget.get("reversed")));
                require(available.compareTo(needed)>=0,"PROMOTION_BUDGET_EXHAUSTED");
                if(!gift.isEmpty()){var p=db.product(gift,false);require("ACTIVE".equals(p.get("status")),"PROMOTION_GIFT_UNAVAILABLE");require("UNLIMITED".equals(p.get("inventory_mode"))||decimal(p.get("stock")).compareTo(needed)>=0,"PROMOTION_GIFT_STOCK_EXHAUSTED");}
            }
        }
    }
    private void identities(String id,Map<String,Object> draft,boolean insert){
        if(draft.get("rules")==null)return;Set<String> rules=new HashSet<>(),rewards=new HashSet<>();
        for(var rule:maps(draft.get("rules"))){String rid=text(rule.get("ruleId"));require(rules.add(rid),"PROMOTION_DUPLICATE_RULE_ID");
            var old=db.one("SELECT * FROM nx_promotion_rule_identity WHERE activity_id=? AND rule_id=?",id,rid);
            require(old==null||Objects.equals(old.get("product_no"),rule.get("productNo")),"PROMOTION_RULE_IDENTITY_IMMUTABLE");
            if(old==null&&rule.get("productNo")!=null)require(db.count("SELECT COUNT(*) FROM nx_promotion_rule_identity WHERE activity_id=? AND product_no=? AND rule_id<>?",id,rule.get("productNo"),rid)==0,"PROMOTION_RULE_REPLACEMENT_RESETS_HISTORY");
            if(insert&&old==null)changed(db.write("INSERT INTO nx_promotion_rule_identity(activity_id,rule_id,product_no) VALUES(?,?,?)",id,rid,rule.get("productNo")));
            for(String role:List.of("buyerReward","inviterReward"))if(rule.get(role)!=null){var reward=map(rule.get(role));String rr=text(reward.get("rewardRuleId")),beneficiary="buyerReward".equals(role)?"BUYER":"DIRECT_INVITER";
                require(rewards.add(rr)&&beneficiary.equals(reward.get("beneficiaryRole")),"PROMOTION_REWARD_IDENTITY_INVALID");
                var prev=db.one("SELECT * FROM nx_promotion_reward_identity WHERE activity_id=? AND (reward_rule_id=? OR (rule_id=? AND beneficiary_role=?))",id,rr,rid,beneficiary);
                require(prev==null||(rr.equals(prev.get("reward_rule_id"))&&rid.equals(prev.get("rule_id"))&&beneficiary.equals(prev.get("beneficiary_role"))),"PROMOTION_REWARD_IDENTITY_IMMUTABLE");
                if(insert&&prev==null)changed(db.write("INSERT INTO nx_promotion_reward_identity(activity_id,reward_rule_id,rule_id,beneficiary_role) VALUES(?,?,?,?)",id,rr,rid,beneficiary));
            }
        }
    }
    private void budgets(String id,Map<String,Object> draft){
        Set<String> seen=new HashSet<>();for(var b:maps(draft.get("budgets"))){String asset=text(b.get("asset")),product=text(b.get("productNo"));require(seen.add(asset+":"+product),"PROMOTION_DUPLICATE_BUDGET");BigDecimal total="DEVICE".equals(asset)?BigDecimal.valueOf(number(b.get("total"))):amount(b.get("total"));
            var old=db.one("SELECT * FROM nx_promotion_budget WHERE activity_id=? AND asset=? AND product_no=? FOR UPDATE",id,asset,product);
            if(old==null)changed(db.write("INSERT INTO nx_promotion_budget(activity_id,asset,product_no,total) VALUES(?,?,?,?)",id,asset,product,total));
            else {require(total.compareTo(decimal(old.get("reserved")).add(decimal(old.get("committed"))).add(decimal(old.get("issued"))).subtract(decimal(old.get("reversed"))))>=0,"PROMOTION_BUDGET_BELOW_COMMITMENT");changed(db.write("UPDATE nx_promotion_budget SET total=?,revision=revision+1 WHERE activity_id=? AND asset=? AND product_no=?",total,id,asset,product));}
        }
    }
    public Map<String,Object> rewardAction(String id,String action,Map<String,Object> request,String command){
        validator.validate(Set.of("cancel","reverse").contains(action)?"ReverseInput":"resolve".equals(action)?"ResolveInput":"Action",request);
        var initial=db.requiredRow("SELECT order_no FROM nx_promotion_reward WHERE obligation_id=?",id);orders.lockOrderParticipants(text(initial.get("order_no")));db.order(text(initial.get("order_no")),true);
        // A normal RR read would retain the pre-wait snapshot after another issuer commits.
        var row=db.requiredRow("SELECT revision FROM nx_promotion_reward WHERE obligation_id=? FOR UPDATE",id);require(number(row.get("revision"))==number(request.get("expectedRevision")),"PROMOTION_CONCURRENT_CHANGE");
        var result=switch(action){case "retry"->rewards.issue(id,command);case "reconcile"->rewards.reconcile(id,command);case "cancel"->rewards.cancel(id,request,command);case "reverse"->rewards.reverse(id,request,command);case "resolve"->rewards.resolve(id,request,command);default->throw new BizException(422,"PROMOTION_ACTION_INVALID");};
        return resource("REWARD",id,number(result.get("revision")));
    }
    public ffdd.opsconsole.shared.api.ApiResult<?> approveCorrection(Map<String,Object> request,ffdd.opsconsole.platform.domain.AuditReplayContext context){
        if(!request.keySet().equals(Set.of("obligationId","expectedRevision","snapshotHash","action","asset")))throw new BizException(422,"PROMOTION_CORRECTION_FIELDS_INVALID");
        String id=required(request.get("obligationId"),"obligationId"),action=text(request.get("action"));
        if(!Set.of("CANCEL","REVERSE").contains(action))throw new BizException(422,"PROMOTION_CORRECTION_ACTION_INVALID");
        permission("CANCEL".equals(action)?"cancelReward":"reverseReward");
        var initial=db.requiredRow("SELECT order_no FROM nx_promotion_reward WHERE obligation_id=?",id);orders.lockOrderParticipants(text(initial.get("order_no")));db.order(text(initial.get("order_no")),true);
        var reward=db.requiredRow("SELECT * FROM nx_promotion_reward WHERE obligation_id=? FOR UPDATE",id);var snapshot=parse(reward.get("snapshot_json"));
        require(number(reward.get("revision"))==number(request.get("expectedRevision"))&&Objects.equals(reward.get("snapshot_hash"),request.get("snapshotHash")),"PROMOTION_CORRECTION_OBLIGATION_CHANGED");
        require(hash(snapshot).equals(reward.get("snapshot_hash"))&&Objects.equals(map(map(snapshot.get("award")).get("reward")).get("type"),request.get("asset")),"PROMOTION_CORRECTION_ASSET_MISMATCH");
        require(("CANCEL".equals(action)?Set.of("PENDING","READY","RETRYABLE_FAILED"):Set.of("ISSUED")).contains(text(reward.get("status"))),"PROMOTION_CORRECTION_STATE_INVALID");
        audit.recordRequired(AuditLogWriteRequest.builder().action("promotionRewardCorrectionApproved").resourceType("PROMOTION_REWARD").resourceId(id).actorId(actor()).actorType("ADMIN").result("SUCCESS").riskLevel("HIGH").detail(values("request",request,"reason",context.reason(),"approvalCommand",context.idempotencyKey())).build());
        return ffdd.opsconsole.shared.api.ApiResult.ok(values("obligationId",id,"revision",reward.get("revision"),"snapshotHash",reward.get("snapshot_hash"),"action",action,"asset",request.get("asset")));
    }
}
