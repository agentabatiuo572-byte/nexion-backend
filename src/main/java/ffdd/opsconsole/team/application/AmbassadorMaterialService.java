package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.team.dto.AmbassadorMaterialRequest;
import ffdd.opsconsole.team.mapper.AmbassadorMaterialMapper;
import ffdd.opsconsole.team.mapper.AmbassadorMaterialMapper.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/** One application lock serializes submissions, reviews and the final budget grant. */
@Service
@RequiredArgsConstructor
public class AmbassadorMaterialService {
    public static final String BUSINESS = "network_f4_ambassador_material_review";
    public static final String FINANCE = "network_f4_ambassador_finance_review";
    private static final Map<String,List<String>> REQUIREMENTS = Map.of(
            "venue",List.of("attendance","activity","expense"), "kol",List.of("promotion","traffic","expense"),
            "print",List.of("materials","quota","expense"), "dev",List.of("requirements","work_hours","acceptance","expense"));
    private static final Map<String,String> BUDGET_TYPES = Map.of("venue","EVENT","kol","KOL","print","PROMOTION","dev","DEV");
    private static final Map<String,String> LABELS = Map.of("attendance","签到及去重人数","activity","活动证明","expense","费用凭证",
            "promotion","推广证明","traffic","流量证明","materials","材料清单","quota","配额证明",
            "requirements","需求说明","work_hours","工时记录","acceptance","验收证明");
    private final AmbassadorMaterialMapper mapper;
    private final ObjectStorageService storage;
    private final ObjectMapper json;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final AdminPermissionCache permissions;
    private final Environment environment;

    public Map<String,Object> appPacket(long user,long id) { return packet(owned(user,id,false),null); }
    public Map<String,Object> adminPacket(long id) { long admin=requireAdmin("network_f4_read"); return packet(required(id,false),admin); }
    public Map<String,Object> adminListPacket(long id) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || auth.getAuthorities().stream().noneMatch(a->"network_f4_read".equals(a.getAuthority()))) return Map.of();
        return adminPacket(id);
    }

    public Map<String,Object> summary(long id) {
        var app=required(id,false);
        Map<String,Object> data=packet(app,null);
        Map<String,Object> result=new LinkedHashMap<>();
        for(String field:List.of("bucket","materialRevision","materialState","missingKinds","businessReview","financeReview","approvalBlockers","legacyApproved")) result.put(field,data.get(field));
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String,Object> upload(long user,long id,String kind,String commandKey,MultipartFile file) {
        var app=owned(user,id,false);
        boolean validKind=kind!=null && (requirements(app.bucket()).contains(kind)
                || !REQUIREMENTS.containsKey(Objects.toString(app.bucket(),"")) && LABELS.containsKey(Objects.toString(kind,"")));
        if(!validKind || file==null || file.isEmpty() || file.getSize()>10L*1024*1024)
            throw invalid("AMBASSADOR_MATERIAL_FILE_INVALID");
        byte[] bytes;
        try { bytes=file.getBytes(); } catch(IOException ex) { throw new BizException(503,"AMBASSADOR_MATERIAL_FILE_UNREADABLE"); }
        String type=bytes.length>=8 && bytes[0]==(byte)0x89 && bytes[1]=='P' && bytes[2]=='N' && bytes[3]=='G'
                && bytes[4]==13 && bytes[5]==10 && bytes[6]==26 && bytes[7]==10 ? "image/png"
                : bytes.length>=3 && bytes[0]==(byte)0xff && bytes[1]==(byte)0xd8 && bytes[2]==(byte)0xff ? "image/jpeg"
                : bytes.length>=5 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-") ? "application/pdf" : null;
        if(type==null) throw invalid("AMBASSADOR_MATERIAL_FILE_TYPE_INVALID");
        String name=file.getOriginalFilename()==null ? "material" : file.getOriginalFilename().replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]", "").trim();
        if(name.isEmpty() || name.length()>180) throw invalid("AMBASSADOR_MATERIAL_FILE_NAME_INVALID");
        String filename=name;
        return (Map<String,Object>) idempotency.executeRetained("F4_MATERIAL_UPLOAD:"+user+":"+id+":"+kind,commandKey,
                hash(kind+"\n"+filename+"\n"+type+"\n"+hash(bytes)),Map.class,()->{
            var locked=owned(user,id,true); pending(locked);
            String fileId=UUID.randomUUID().toString(), key="private/ambassador/"+id+"/"+fileId;
            var row=new FileRow(fileId,id,user,kind,filename,type,bytes.length,key);
            mapper.insertFile(row);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { if(status==STATUS_ROLLED_BACK) storage.removeQuietly(key); }
            });
            storage.put(key,type,new ByteArrayInputStream(bytes),bytes.length);
            auditRequired("F4_AMBASSADOR_MATERIAL_UPLOADED",locked,Map.of("fileId",fileId,"kind",kind,"idempotencyKey",commandKey),"USER");
            return attachment(row);
        });
    }

    public Download appFile(long user,long id,String fileId) { owned(user,id,false); return download(id,fileId); }
    public Download adminFile(long id,String fileId) { requireAdmin("network_f4_read"); required(id,false); return download(id,fileId); }
    private Download download(long id,String fileId) {
        var row=mapper.file(id,fileId); if(row==null) throw new BizException(404,"AMBASSADOR_MATERIAL_FILE_NOT_FOUND");
        try(var input=storage.get(row.objectKey())) { return new Download(row.name(),row.contentType(),input.readAllBytes()); }
        catch(IOException ex) { throw new BizException(503,"AMBASSADOR_MATERIAL_FILE_UNREADABLE"); }
    }
    public record Download(String name,String contentType,byte[] bytes) {}

    @SuppressWarnings("unchecked")
    public ApiResult<Map<String,Object>> submit(long user,long id,String key,AmbassadorMaterialRequest request) {
        owned(user,id,false);
        if(request==null || request.expectedMaterialRevision()==null || request.expectedMaterialRevision()<0
                || request.attachments()==null || request.attachments().isEmpty() || request.attachments().size()>12
                || request.statement()==null || request.statement().isBlank() || request.statement().length()>4000)
            throw invalid("AMBASSADOR_MATERIAL_SUBMISSION_INVALID");
        return (ApiResult<Map<String,Object>>) idempotency.executeRetained("F4_MATERIAL_SUBMIT:"+user+":"+id,key,hash(encode(request)),ApiResult.class,()->{
            var app=owned(user,id,true); pending(app);
            var current=mapper.current(id); long revision=current==null?0:current.revision();
            if(request.expectedMaterialRevision()!=revision) throw new BizException(409,"MATERIAL_REVISION_STALE");
            String bucket=REQUIREMENTS.containsKey(Objects.toString(app.bucket(),""))?app.bucket():null;
            if(bucket==null) { bucket=request.bucket(); if(!REQUIREMENTS.containsKey(Objects.toString(bucket,""))) throw invalid("APPLICATION_BUCKET_REQUIRED"); }
            else if(request.bucket()!=null && !bucket.equals(request.bucket())) throw invalid("AMBASSADOR_BUCKET_CHANGE_FORBIDDEN");
            BigDecimal budget=app.budget();
            if(request.budgetUsdt()!=null && request.budgetUsdt().compareTo(budget)!=0) {
                var reviews=mapper.reviews(id);
                boolean returned=Arrays.asList(latest(reviews,revision,"BUSINESS"),latest(reviews,revision,"FINANCE")).stream()
                        .anyMatch(r->r!=null && "NEEDS_MORE".equals(r.status()));
                if(!returned || request.budgetUsdt().scale()>6 || request.budgetUsdt().signum()<=0 || request.budgetUsdt().compareTo(budget)>0)
                    throw invalid("AMBASSADOR_BUDGET_DECREASE_REQUIRES_RETURN");
                budget=request.budgetUsdt();
            }
            if(budget==null || budget.signum()<=0) throw invalid("AMBASSADOR_BUDGET_INVALID");
            Set<String> seen=new HashSet<>();
            for(var item:request.attachments()) {
                if(item==null || item.fileId()==null || item.kind()==null || !seen.add(item.fileId()) || !requirements(bucket).contains(item.kind()))
                    throw invalid("AMBASSADOR_MATERIAL_ATTACHMENT_INVALID");
                var file=mapper.file(id,item.fileId());
                if(file==null || file.userId()!=user || !file.kind().equals(item.kind())) throw new BizException(403,"AMBASSADOR_MATERIAL_FILE_FORBIDDEN");
                if(!storage.exists(file.objectKey())) throw new BizException(409,"AMBASSADOR_MATERIAL_FILE_MISSING");
            }
            mapper.updateApplication(id,bucket,budget);
            mapper.insertRevision(id,revision+1,bucket,budget,request.statement().trim(),encode(request.attachments()));
            auditRequired("F4_AMBASSADOR_MATERIAL_SUBMITTED",app,Map.of("materialRevision",revision+1,"previousRevision",revision,
                    "bucket",bucket,"budgetUsdt",budget,"idempotencyKey",key),"USER");
            return ApiResult.ok(packet(required(id,false),null));
        });
    }

    @SuppressWarnings("unchecked")
    public ApiResult<Map<String,Object>> review(long id,String key,AmbassadorMaterialRequest.Review request) {
        if(request==null || !Set.of("BUSINESS","FINANCE").contains(Objects.toString(request.stage(),""))
                || !Set.of("PASSED","NEEDS_MORE").contains(Objects.toString(request.decision(),""))
                || request.expectedMaterialRevision()==null || request.expectedMaterialRevision()<=0
                || request.reason()==null || request.reason().isBlank() || request.reason().length()>1000)
            throw invalid("AMBASSADOR_MATERIAL_REVIEW_INVALID");
        String authority="BUSINESS".equals(request.stage())?BUSINESS:FINANCE;
        long admin=requireAdmin(authority);
        return (ApiResult<Map<String,Object>>) idempotency.executeRetained("F4_MATERIAL_REVIEW:"+admin+":"+id,key,hash(encode(request)),ApiResult.class,()->{
            requireAdmin(authority);
            var app=required(id,true); pending(app);
            var current=mapper.current(id);
            if(current==null || current.revision()!=request.expectedMaterialRevision()) throw new BizException(409,"MATERIAL_REVISION_STALE");
            var missing=missing(app,current);
            var requestedMissing=request.missingKinds()==null?List.<String>of():request.missingKinds();
            if(requestedMissing.stream().anyMatch(k->k==null || !requirements(app.bucket()).contains(k)) || requestedMissing.size()>requirements(app.bucket()).size())
                throw invalid("AMBASSADOR_MATERIAL_MISSING_KINDS_INVALID");
            boolean business="BUSINESS".equals(request.stage()), passed="PASSED".equals(request.decision());
            if(passed && (!requestedMissing.isEmpty() || missing.stream().anyMatch(k->business?!"expense".equals(k):"expense".equals(k))))
                throw invalid("AMBASSADOR_REQUIRED_MATERIAL_MISSING");
            if(business) {
                if(request.recognizedBudgetUsdt()!=null || request.verifiedAttendees()!=null && request.verifiedAttendees()<0
                        || passed && "venue".equals(app.bucket()) && (request.verifiedAttendees()==null || request.verifiedAttendees()<100))
                    throw invalid("AMBASSADOR_VERIFIED_ATTENDEES_INVALID");
            } else if(request.verifiedAttendees()!=null || request.recognizedBudgetUsdt()!=null && (request.recognizedBudgetUsdt().scale()>6
                    || request.recognizedBudgetUsdt().signum()<0 || request.recognizedBudgetUsdt().precision()-request.recognizedBudgetUsdt().scale()>12)
                    || passed && request.recognizedBudgetUsdt()==null) throw invalid("AMBASSADOR_RECOGNIZED_BUDGET_INVALID");
            mapper.insertReview(id,current.revision(),request.stage(),request.decision(),admin,AdminActorResolver.resolve(null),request.reason().trim(),
                    request.verifiedAttendees(),request.recognizedBudgetUsdt(),encode(requestedMissing));
            Map<String,Object> detail=new LinkedHashMap<>();
            detail.put("materialRevision",current.revision()); detail.put("stage",request.stage()); detail.put("decision",request.decision());
            detail.put("reason",request.reason().trim()); detail.put("verifiedAttendees",request.verifiedAttendees());
            detail.put("recognizedBudgetUsdt",request.recognizedBudgetUsdt()); detail.put("missingKinds",requestedMissing); detail.put("idempotencyKey",key);
            auditRequired("F4_AMBASSADOR_MATERIAL_REVIEWED",app,detail,"ADMIN");
            return ApiResult.ok(packet(app,admin));
        });
    }

    /** Called inside the existing final status transaction before its UPDATE and budget INSERT. */
    public void requireApproval(long id,Long expectedRevision) {
        long admin=requireAdmin("network_f4_ambassador_approve");
        var app=required(id,true); pending(app);
        var current=mapper.current(id);
        if(expectedRevision==null || current==null || expectedRevision!=current.revision()) throw new BizException(409,"MATERIAL_REVISION_STALE");
        var blockers=blockers(app,current,mapper.reviews(id),admin);
        if(!blockers.isEmpty()) throw new BizException(409,blockers.get(0));
        for(var item:attachments(current)) {
            var file=mapper.file(id,item.fileId());
            if(file==null || !storage.exists(file.objectKey())) throw new BizException(409,"AMBASSADOR_MATERIAL_FILE_MISSING");
        }
    }

    public Map<String,Object> proposal(long id,Long expectedRevision) {
        long admin=requireAdmin("network_f4_read");
        var app=required(id,false); var result=packet(app,admin);
        Map<String,Object> proposal=new LinkedHashMap<>();
        for(String field:List.of("applicationId","bucket","budgetUsdt","materialRevision","materialState","businessReview","financeReview","canApprove","approvalBlockers")) proposal.put(field,result.get(field));
        proposal.put("recognizedBudgetUsdt",((Map<?,?>)result.get("financeReview")).get("recognizedBudgetUsdt"));
        proposal.put("expectedMaterialRevision",expectedRevision);
        if(expectedRevision==null || !Objects.equals(expectedRevision,result.get("materialRevision"))) {
            var blockers=new ArrayList<>((List<String>)result.get("approvalBlockers")); blockers.add("MATERIAL_REVISION_STALE");
            proposal.put("approvalBlockers",blockers); proposal.put("canApprove",false);
        }
        return proposal;
    }

    public static boolean isProposal(AuditReplayCommand command) {
        return command!=null && "F".equals(command.domain()) && "f_ui_config".equals(command.op()) && command.params()!=null
                && Objects.toString(command.params().get("key"),"").matches("F\\.ambassador\\.[1-9][0-9]{0,15}\\.status");
    }
    public Map<String,Object> proposal(AuditReplayCommand command) {
        if(!isProposal(command)) return null;
        String key=String.valueOf(command.params().get("key"));
        long id=Long.parseLong(key.substring("F.ambassador.".length(),key.length()-".status".length()));
        Object revision=command.params().get("expectedMaterialRevision");
        String value=Objects.toString(revision,"");
        Long expected=value.matches("[0-9]{1,18}")?Long.valueOf(value):null;
        var result=proposal(id,expected);
        String decision=switch(Objects.toString(command.params().get("value"),"").trim().toLowerCase(Locale.ROOT)) {
            case "approved","通过","已批" -> "APPROVE";
            case "rejected","驳回","已驳" -> "REJECT";
            default -> null;
        };
        result.put("applicationDecision",decision);
        if("REJECT".equals(decision)) {
            long admin=requireAdmin("network_f4_read");
            List<String> blockers=new ArrayList<>();
            if(!"PENDING".equals(required(id,false).status())) blockers.add("APPLICATION_NOT_PENDING");
            if(!permissions.getPermissionCodes(admin).contains("network_f4_ambassador_approve")) blockers.add("PERMISSION_DENIED");
            result.put("canApprove",blockers.isEmpty()); result.put("approvalBlockers",blockers);
        }
        return result;
    }
    public Map<String,Object> queueProposal(AuditReplayCommand command) {
        if(!isProposal(command)) return null;
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || auth.getAuthorities().stream().noneMatch(a->"network_f4_read".equals(a.getAuthority()))) return null;
        return proposal(command);
    }

    private Map<String,Object> packet(Application app,Long admin) {
        var current=mapper.current(app.id()); var reviews=mapper.reviews(app.id());
        long revision=current==null?0:current.revision();
        var business=latest(reviews,revision,"BUSINESS"); var finance=latest(reviews,revision,"FINANCE");
        var missing=missing(app,current);
        for(var review:Arrays.asList(business,finance)) if(review!=null && "NEEDS_MORE".equals(review.status()))
            for(String kind:decode(review.missingKindsJson(),new TypeReference<List<String>>(){})) if(!missing.contains(kind)) missing.add(kind);
        String state=current==null?"NOT_SUBMITTED":business!=null && "NEEDS_MORE".equals(business.status()) || finance!=null && "NEEDS_MORE".equals(finance.status())
                ? "NEEDS_MORE" : business!=null && finance!=null && "PASSED".equals(business.status()) && "PASSED".equals(finance.status()) && missing.isEmpty()?"PASSED":"PENDING";
        Set<String> grants=admin==null?Set.of():permissions.getPermissionCodes(admin);
        var blockers=blockers(app,current,reviews,admin);
        if(admin!=null && !grants.contains("network_f4_ambassador_approve")) blockers.add("PERMISSION_DENIED");
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("applicationId",app.id()); data.put("bucket",REQUIREMENTS.containsKey(Objects.toString(app.bucket(),""))?app.bucket():null);
        data.put("eventDate",app.eventDate()); data.put("budgetUsdt",app.budget()); data.put("materialRevision",revision); data.put("materialState",state);
        data.put("requirements",requirementViews(app.bucket()));
        Map<String,Object> requirementsByBucket=new LinkedHashMap<>();
        for(String bucket:List.of("venue","kol","print","dev")) requirementsByBucket.put(bucket,requirementViews(bucket));
        data.put("requirementsByBucket",requirementsByBucket);
        data.put("attachments",current==null?List.of():attachments(current).stream().map(a->attachment(mapper.file(app.id(),a.fileId()))).toList());
        data.put("businessReview",reviewView(business)); data.put("financeReview",reviewView(finance)); data.put("missingKinds",missing);
        data.put("statement",current==null?"":current.statement()); data.put("canSubmit","PENDING".equals(app.status()) && admin==null);
        data.put("canReviewBusiness","PENDING".equals(app.status()) && current!=null && grants.contains(BUSINESS));
        data.put("canReviewFinance","PENDING".equals(app.status()) && current!=null && grants.contains(FINANCE));
        data.put("canApprove",admin!=null && blockers.isEmpty()); data.put("approvalBlockers",blockers);
        List<String> proposalBlockers=new ArrayList<>(blockers.stream().filter(code->!"SELF_REVIEW_APPROVAL_FORBIDDEN".equals(code)).toList());
        if(!grants.contains("network_f4_ambassador_approve") && !proposalBlockers.contains("PERMISSION_DENIED")) proposalBlockers.add("PERMISSION_DENIED");
        if(!grants.contains("platform_a2_write") && !grants.contains("platform_a2_proposal_create")) proposalBlockers.add("A2_PROPOSAL_PERMISSION_REQUIRED");
        data.put("proposalBlockers",proposalBlockers);
        data.put("canProposeApproval",proposalBlockers.isEmpty());
        data.put("legacyApproved","APPROVED".equals(app.status()) && current==null);
        data.put("reviewHistory",reviews.stream().map(r->{var view=reviewView(r);view.put("materialRevision",r.revision());view.put("stage",r.stage());return view;}).toList());
        return data;
    }

    private List<String> blockers(Application app,Revision current,List<Review> reviews,Long admin) {
        List<String> result=new ArrayList<>();
        if(!"PENDING".equals(app.status())) result.add("APPLICATION_NOT_PENDING");
        if(!BUDGET_TYPES.containsKey(Objects.toString(app.bucket(),""))) result.add("APPLICATION_BUCKET_REQUIRED");
        if(current==null) { result.add("MATERIALS_NOT_SUBMITTED"); return result; }
        if(!missing(app,current).isEmpty()) result.add("AMBASSADOR_REQUIRED_MATERIAL_MISSING");
        var business=latest(reviews,current.revision(),"BUSINESS"); var finance=latest(reviews,current.revision(),"FINANCE");
        if(business==null || !"PASSED".equals(business.status())) result.add("BUSINESS_REVIEW_REQUIRED");
        if(finance==null || !"PASSED".equals(finance.status())) result.add("FINANCE_REVIEW_REQUIRED");
        if(business!=null && "NEEDS_MORE".equals(business.status()) || finance!=null && "NEEDS_MORE".equals(finance.status())) result.add("MATERIALS_NEEDS_MORE");
        if("venue".equals(app.bucket()) && (business==null || business.verifiedAttendees()==null || business.verifiedAttendees()<100)) result.add("AMBASSADOR_VERIFIED_ATTENDEES_INVALID");
        if(app.budget()==null || app.budget().signum()<=0 || finance==null || finance.recognizedBudgetUsdt()==null || finance.recognizedBudgetUsdt().compareTo(app.budget())<0) result.add("RECOGNIZED_AMOUNT_INSUFFICIENT");
        if(app.eventDate()==null || app.eventDate().isAfter(BusinessCalendar.legacy().dateAt(Instant.now()))) result.add("EVENT_NOT_COMPLETED");
        // Any verifier of this revision is distinct from the actual final executor, even after a revised conclusion.
        if(admin!=null && reviews.stream().anyMatch(r->r.revision()==current.revision() && r.reviewerId()==admin)) result.add("SELF_REVIEW_APPROVAL_FORBIDDEN");
        return result;
    }

    private Application owned(long user,long id,boolean lock) {
        var app=required(id,lock); Integer sandbox=mapper.userSandbox(user);
        Set<String> profiles=new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        boolean test=profiles.equals(Set.of("test"));
        boolean production=profiles.isEmpty() || profiles.equals(Set.of("dev")) || profiles.equals(Set.of("prod"));
        if(!test && !production) throw new BizException(503,"AMBASSADOR_PROFILE_INVALID");
        if(app.userId()!=user || sandbox==null || test && (sandbox!=1 || !"SANDBOX".equals(app.sourceEnvironment())
                || !Objects.equals(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID"),app.runId()))
                || production && (sandbox!=0 || !"PRODUCTION".equals(app.sourceEnvironment()) || !"".equals(app.runId())))
            throw new BizException(403,"AMBASSADOR_APPLICATION_FORBIDDEN");
        return app;
    }
    private Application required(long id,boolean lock) { var app=lock?mapper.lockApplication(id):mapper.application(id); if(app==null) throw new BizException(404,"AMBASSADOR_APPLICATION_NOT_FOUND"); return app; }
    private void pending(Application app) { if(!"PENDING".equals(app.status())) throw new BizException(409,"APPLICATION_NOT_PENDING"); }
    private long requireAdmin(String code) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?,?> details) || !"ADMIN".equals(details.get("subjectType"))) throw new BizException(403,"ADMIN_AUTH_REQUIRED");
        long admin;
        try { admin=Long.parseLong(String.valueOf(auth.getPrincipal())); } catch(NumberFormatException ex) { throw new BizException(403,"ADMIN_AUTH_REQUIRED"); }
        if(!permissions.getPermissionCodes(admin).contains(code)) throw new BizException(403,"PERMISSION_DENIED");
        return admin;
    }
    private List<String> requirements(String bucket) { return REQUIREMENTS.getOrDefault(Objects.toString(bucket,""),List.of()); }
    private List<Map<String,Object>> requirementViews(String bucket) {
        return requirements(bucket).stream().map(k->Map.<String,Object>of("kind",k,"label",LABELS.get(k),"required",true)).toList();
    }
    private List<AmbassadorMaterialRequest.Attachment> attachments(Revision revision) { return decode(revision.attachmentsJson(),new TypeReference<>(){}); }
    private List<String> missing(Application app,Revision current) {
        Set<String> present=new HashSet<>(); if(current!=null) attachments(current).forEach(a->present.add(a.kind()));
        return new ArrayList<>(requirements(app.bucket()).stream().filter(k->!present.contains(k)).toList());
    }
    private Review latest(List<Review> reviews,long revision,String stage) { return reviews.stream().filter(r->r.revision()==revision && stage.equals(r.stage())).findFirst().orElse(null); }
    private Map<String,Object> attachment(FileRow file) {
        if(file==null) throw new BizException(409,"AMBASSADOR_MATERIAL_FILE_MISSING");
        return Map.of("fileId",file.fileId(),"kind",file.kind(),"name",file.name(),"contentType",file.contentType(),"sizeBytes",file.sizeBytes());
    }
    private Map<String,Object> reviewView(Review review) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("status",review==null?"PENDING":review.status());
        result.put("reviewerLabel",review==null?null:review.reviewerLabel());
        result.put("reviewedAt",review==null || review.reviewedAt()==null?null:review.reviewedAt().atZone(BusinessCalendar.STORAGE_ZONE).toInstant().toString());
        result.put("reason",review==null?null:review.reason()); result.put("verifiedAttendees",review==null?null:review.verifiedAttendees());
        result.put("recognizedBudgetUsdt",review==null?null:review.recognizedBudgetUsdt()); return result;
    }
    private void auditRequired(String action,Application app,Map<String,Object> detail,String subject) {
        audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("AMBASSADOR_APPLICATION").resourceId(String.valueOf(app.id()))
                .userId(app.userId()).actorType(subject).actorUsername(AdminActorResolver.resolve(null)).result("SUCCESS").riskLevel("HIGH").detail(detail).build());
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(JsonProcessingException ex) { throw new IllegalStateException(ex); } }
    private <T> T decode(String value,TypeReference<T> type) { try { return json.readValue(value,type); } catch(JsonProcessingException ex) { throw new IllegalStateException("AMBASSADOR_MATERIAL_STATE_INVALID",ex); } }
    private String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    private String hash(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); } }
    private BizException invalid(String message) { return new BizException(422,message); }
}
