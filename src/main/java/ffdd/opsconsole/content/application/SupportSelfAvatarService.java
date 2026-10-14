package ffdd.opsconsole.content.application;

import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.content.dto.SupportAvatarRequests.*;
import ffdd.opsconsole.content.mapper.SupportAdminAvatarMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Avatar-only self commands. No A1 permission or account profile fields are granted. */
@Service
@RequiredArgsConstructor
public class SupportSelfAvatarService {
    private final SupportAdminAvatarService avatars;
    private final SupportAdminAvatarMapper mapper;
    private final SupportAttachmentService attachments;
    private final SupportAttachmentPolicy policy;
    private final SupportOwnershipService ownership;
    private final AdminMapper admins;
    private final AdminRoleRelationMapper roles;
    private final SupportGroupMapper groups;
    private final AuditObjectLockMapper locks;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;

    @Transactional
    public Map<String,Object> prepareAdmin(String key,DefaultPrepare request) {
        if(request==null)throw invalid();
        Long actor=attachments.actor("ADMIN");ownership.requireSuperAdminSnapshot();
        if(!SupportOwnershipService.hasAuthority("platform_a1_write"))throw forbidden();
        active(actor);SupportDefaultAvatarCatalog.gender(request.avatarGender());
        return command(actor,"ADMIN_DEFAULT",key,request,()->auditPrepared(actor,key,avatars.prepareDefault(request.avatarGender(),request.clientUploadId(),key)));
    }
    @Transactional
    public Map<String,Object> prepareDefault(String key,DefaultPrepare request) {
        if(request==null)throw invalid();Long actor=self();SupportDefaultAvatarCatalog.gender(request.avatarGender());
        return command(actor,"SELF_DEFAULT",key,request,()->auditPrepared(actor,key,avatars.prepareDefaultSelf(actor,request.avatarGender(),request.clientUploadId(),key)));
    }
    @Transactional
    public Map<String,Object> upload(String client,String key,MultipartFile file) {
        Long actor=self();var limits=policy.view();if(!limits.available())throw new BizException(422,"AVATAR_POLICY_UNCONFIGURED");
        var image=SupportAttachmentService.decode(file,limits);
        return command(actor,"SELF_UPLOAD",key,client+"|"+image.rawHash(),()->avatars.uploadSelf(actor,client,key,file));
    }
    @Transactional
    public Map<String,Object> cancel(String asset,String key) {
        Long actor=self();return command(actor,"SELF_CANCEL",key,asset,()->avatars.cancelSelf(actor,asset,key));
    }
    @Transactional
    public SupportAttachmentService.Content preview(String asset){Long actor=self();return avatars.previewSelf(actor,asset);}
    @Transactional
    public SupportAttachmentService.Content content(){Long actor=self();return avatars.contentSelf(actor);}
    @Transactional
    public Map<String,Object> snapshot(){return snapshot(self());}

    @Transactional
    public Map<String,Object> save(String key,SelfSave request) {
        if(request==null || request.assetId()==null || !request.assetId().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || request.reason()==null || request.reason().isBlank()
                || request.reason().length()>500 || request.expectedVersion()==null || !request.expectedVersion().matches("[0-9]{1,19}"))throw invalid();
        Long actor=self();
        return command(actor,"SELF_SAVE",key,request,()-> {
            // Recheck within the claimed action, including retries after an uncertain outcome.
            if(!actor.equals(self()))throw forbidden();
            var before=snapshot(actor);
            if(!request.expectedVersion().equals(before.get("accountVersion")))throw new BizException(409,"ACCOUNT_VERSION_STALE");
            if(locks.countActiveByTarget("A","account",String.valueOf(actor))>0)throw new BizException(409,"OBJECT_LOCKED_BY_A2");
            if(request.assetId().equals(before.get("assetId")))return before;
            long version;
            try{version=Long.parseLong(request.expectedVersion());}catch(NumberFormatException ex){throw invalid();}
            if(admins.incrementVersionIfVersion(actor,version)!=1)throw new BizException(409,"ACCOUNT_VERSION_STALE");
            avatars.attachSelf(actor,request.assetId());var after=snapshot(actor);
            var detail=new LinkedHashMap<String,Object>();detail.put("reason",request.reason().trim());detail.put("idempotencyKey",key);
            detail.put("fromAvatarAssetId",before.get("assetId"));detail.put("toAvatarAssetId",after.get("assetId"));
            detail.put("fromAvatarVersion",before.get("avatarVersion"));detail.put("toAvatarVersion",after.get("avatarVersion"));
            audit.recordRequired(AuditLogWriteRequest.builder().action("SUPPORT_SELF_AVATAR_UPDATED").resourceType("A1_ADMIN_ACCOUNT")
                    .resourceId(String.valueOf(actor)).actorType("ADMIN").actorUsername(active(actor).getUsername())
                    .result("SUCCESS").riskLevel("HIGH").detail(detail).build());return after;
        });
    }
    private Long self() {
        Long actor=attachments.actor("ADMIN");active(actor);
        if(!Set.of("SUPPORT","SUPER_ADMIN").contains(String.valueOf(roles.activeRoleCode(actor))))throw forbidden();
        var service=groups.qualificationCurrent(actor,"SERVICE");var supervisor=groups.qualificationCurrent(actor,"SUPERVISOR");
        if(service==null && supervisor==null)throw forbidden();
        if(service!=null && supervisor==null && mapper.enabledServiceProfile(actor)==null)throw forbidden();
        return actor;
    }
    private Map<String,Object> auditPrepared(Long actor,String key,Map<String,Object> result) {
        var detail=new LinkedHashMap<String,Object>(result);detail.remove("previewRef");detail.put("idempotencyKey",key);
        audit.recordRequired(AuditLogWriteRequest.builder().action("SUPPORT_DEFAULT_AVATAR_PREPARED").resourceType("A1_ADMIN_ACCOUNT")
                .resourceId(String.valueOf(actor)).actorType("ADMIN").actorUsername(active(actor).getUsername())
                .result("SUCCESS").riskLevel("HIGH").detail(detail).build());return result;
    }
    private AdminEntity active(Long actor) {
        var admin=admins.selectActiveForUpdate(actor);
        if(admin==null || !Integer.valueOf(1).equals(admin.getStatus()))throw forbidden();return admin;
    }
    private Map<String,Object> snapshot(Long actor) {
        var admin=active(actor);var ref=mapper.selfReference(actor);var result=new LinkedHashMap<String,Object>();
        result.put("assetId",ref==null?null:ref.get("assetId"));
        result.put("avatarVersion",ref==null?0L:ref.getOrDefault("avatarVersion",0L));
        result.put("accountVersion",String.valueOf(admin.getVersion()==null?0L:admin.getVersion()));
        result.put("avatarGender",ref==null?null:ref.get("avatarGender"));return result;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private Map<String,Object> command(Long actor,String operation,String key,Object request,Supplier<Map<String,Object>> action) {
        String hash=SupportDefaultAvatarCatalog.sha256((actor+"|"+operation+"|"+request).getBytes(StandardCharsets.UTF_8));
        return (Map<String,Object>)idempotency.execute("AVATAR:"+operation+":"+actor,key,hash,Map.class,(Supplier)action);
    }
    private static BizException invalid(){return new BizException(422,"AVATAR_COMMAND_INVALID");}
    private static BizException forbidden(){return new BizException(403,"AVATAR_SELF_FORBIDDEN");}
}
