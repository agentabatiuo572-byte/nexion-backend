package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAvatarAsset;
import ffdd.opsconsole.content.mapper.SupportAdminAvatarMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.io.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class SupportAdminAvatarService {
    private final SupportAdminAvatarMapper mapper;
    private final AdminRoleRelationMapper roles;
    private final SupportOwnershipService ownership;
    private final SupportAttachmentService attachments;
    private final SupportAttachmentPolicy policy;
    private final ObjectStorageService storage;
    private final SupportGroupMapper groups;

    @Transactional
    public Map<String,Object> upload(String client,String key,MultipartFile file) {
        Long actor=superWriter();token(client);token(key);
        var limits=policy.view();if(!limits.available())throw new BizException(422,"AVATAR_POLICY_UNCONFIGURED");
        var image=SupportAttachmentService.decode(file,limits);
        return prepareUpload(actor,client,key,image,image.rawHash(),false);
    }

    Map<String,Object> uploadSelf(Long actor,String client,String key,MultipartFile file) {
        requirePrincipal(actor);token(client);token(key);
        var limits=policy.view();if(!limits.available())throw new BizException(422,"AVATAR_POLICY_UNCONFIGURED");
        var image=SupportAttachmentService.decode(file,limits);
        return prepareUpload(actor,client,key,image,commandHash("SELF_UPLOAD|"+actor+"|"+image.rawHash()),true);
    }

    private Map<String,Object> prepareUpload(Long actor,String client,String key,SupportAttachmentService.Encoded image,String hash,boolean self) {
        var previous=mapper.prior(actor,client,key);
        if(!previous.isEmpty()) {
            if(previous.size()!=1)throw conflict();var row=previous.get(0);
            if(!client.equals(row.clientUploadId()) || !key.equals(row.idempotencyKey()) || !hash.equals(row.requestHash()))throw conflict();
            if(!"ATTACHED".equals(row.state()))ready(row);
            return ownedView(row,self);
        }
        return ownedView(store(actor,client,key,hash,image),self);
    }

    @Transactional
    public Map<String,Object> prepareDefault(String gender,String client,String key) {
        return prepareDefaultOwned(superWriter(),gender,client,key,false);
    }

    Map<String,Object> prepareDefaultSelf(Long actor,String gender,String client,String key) {
        requirePrincipal(actor);return prepareDefaultOwned(actor,gender,client,key,true);
    }

    private Map<String,Object> prepareDefaultOwned(Long actor,String gender,String client,String key,boolean self) {
        token(client);token(key);String pool=SupportDefaultAvatarCatalog.gender(gender);
        String hash=commandHash((self?"SELF_DEFAULT|":"ADMIN_DEFAULT|")+actor+"|"+pool);
        var previous=mapper.prior(actor,client,key);
        if(!previous.isEmpty()) {
            if(previous.size()!=1)throw conflict();var row=previous.get(0);
            if(!client.equals(row.clientUploadId()) || !key.equals(row.idempotencyKey()) || !hash.equals(row.requestHash()))throw conflict();
            if(!"ATTACHED".equals(row.state()))ready(row);
            return ownedView(row,self);
        }
        var selected=new SupportDefaultAvatarCatalog().select(pool,policy.view()).orElseThrow(()->new BizException(422,"AVATAR_DEFAULT_POOL_UNAVAILABLE"));
        var result=new LinkedHashMap<>(ownedView(store(actor,client,key,hash,selected.image()),self));
        result.put("poolEntryId",selected.poolEntryId());result.put("sourceHash",selected.sourceHash());return result;
    }

    /** A2 has a separate create-only capability; it does not authorize maker uploads. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> attachDefaultCreated(Long admin,String gender) {
        Long actor=superWriter();SupportWorkbenchService.requireSafeId(admin);
        if(A2ReplayContext.isReplaying())A2ReplayContext.requireDefaultAvatarTarget(actor,admin);
        if(!"SUPPORT".equals(roles.activeRoleCode(admin)))throw new BizException(422,"AVATAR_DEFAULT_INTENT_INVALID");
        var current=mapper.reference(admin);
        if(current!=null && current.get("assetId")!=null)throw conflict();
        var selected=new SupportDefaultAvatarCatalog().select(gender,policy.view());
        if(selected.isEmpty())return Map.of("defaultAvatarStatus","MISSING");
        var choice=selected.get();String command=UUID.randomUUID().toString();
        var row=store(actor,command,command,commandHash("CREATE_DEFAULT|"+admin+"|"+choice.sourceHash()),choice.image());
        attachOwned(admin,row,actor);
        return Map.of("defaultAvatarStatus","ATTACHED","poolEntryId",choice.poolEntryId(),"sourceHash",choice.sourceHash());
    }

    private SupportAvatarAsset store(Long actor,String client,String key,String hash,SupportAttachmentService.Encoded image) {
        var limits=policy.view();if(!limits.available())throw new BizException(422,"AVATAR_POLICY_UNCONFIGURED");
        String id=UUID.randomUUID().toString(),location="private/admin-avatar/"+UUID.randomUUID();
        var row=new SupportAvatarAsset(id,actor,client,key,hash,image.mime(),(long)image.content().length,location,"READY",null,
                LocalDateTime.now(ZoneOffset.UTC).plusSeconds(limits.ttlSeconds()));
        mapper.insertAsset(row);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status==STATUS_ROLLED_BACK)try{storage.remove(location);}catch(RuntimeException ex){
                    org.slf4j.LoggerFactory.getLogger(SupportAdminAvatarService.class).error("Avatar rollback cleanup failed: {}",id);
                }
            }
        });
        try{storage.put(location,row.mime(),new ByteArrayInputStream(image.content()),row.byteCount());}
        catch(RuntimeException ex){throw storageUnavailable(ex);}return row;
    }

    private static BizException storageUnavailable(RuntimeException cause) {
        var failure=new BizException(503,"AVATAR_STORAGE_UNAVAILABLE");failure.initCause(cause);return failure;
    }

    /** Called after the original account version CAS, in that same transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void attach(Long admin,String assetId) {
        Long actor=superWriter();SupportWorkbenchService.requireSafeId(admin);
        Long uploader=A2ReplayContext.isReplaying() ? A2ReplayContext.approvedAvatarUploader(actor,admin,assetId) : actor;
        attachOwned(admin,find(assetId),uploader);
    }

    void attachSelf(Long actor,String assetId) {requirePrincipal(actor);attachOwned(actor,find(assetId),actor);}

    private void attachOwned(Long admin,SupportAvatarAsset row,Long uploader) {
        String assetId=row.id();if(!uploader.equals(row.uploaderId()))throw missing();
        var current=mapper.reference(admin);
        if(current!=null && assetId.equals(current.get("assetId")))return;
        ready(row);boolean present;
        try{present=storage.exists(row.objectKey());}catch(RuntimeException ex){throw storageUnavailable(ex);}
        if(!present)throw new BizException(503,"AVATAR_STORAGE_UNAVAILABLE");
        if(mapper.attach(assetId,admin)!=1 || mapper.accountAvatar(admin,assetId)==0)throw conflict();
    }

    @Transactional
    public Map<String,Object> cancel(String assetId,String key) {
        return cancelOwned(superWriter(),assetId,key,false);
    }
    Map<String,Object> cancelSelf(Long actor,String assetId,String key) {requirePrincipal(actor);return cancelOwned(actor,assetId,key,true);}
    private Map<String,Object> cancelOwned(Long actor,String assetId,String key,boolean self) {
        token(key);var row=find(assetId);
        if(!actor.equals(row.uploaderId()))throw missing();
        if("ATTACHED".equals(row.state()))throw conflict();
        if("READY".equals(row.state())) {
            if(mapper.cancel(assetId)!=1)throw conflict();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit(){try{storage.remove(row.objectKey());}catch(RuntimeException ex){
                    org.slf4j.LoggerFactory.getLogger(SupportAdminAvatarService.class).error("Cancelled avatar cleanup failed: {}",assetId);
                }}
            });
        }
        return ownedView(find(assetId),self);
    }

    @Transactional
    public SupportAttachmentService.Content preview(String assetId) {
        Long actor=superWriter();var row=find(assetId);if(!actor.equals(row.uploaderId()))throw missing();
        if(!"ATTACHED".equals(row.state()))ready(row);return bytes(row);
    }
    SupportAttachmentService.Content previewSelf(Long actor,String assetId) {
        requirePrincipal(actor);var row=find(assetId);if(!actor.equals(row.uploaderId()))throw missing();
        if("ATTACHED".equals(row.state()) && !actor.equals(row.attachedAdminId()))throw missing();
        if(!"ATTACHED".equals(row.state()))ready(row);return bytes(row);
    }
    SupportAttachmentService.Content contentSelf(Long actor) {requirePrincipal(actor);return attachedContent(actor);}
    private void requirePrincipal(Long actor) {if(!attachments.actor("ADMIN").equals(actor))throw new BizException(403,"AVATAR_WRITE_FORBIDDEN");}
    private static String commandHash(String value){return SupportDefaultAvatarCatalog.sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @Transactional
    public SupportAttachmentService.Content content(Long admin,boolean app) {
        SupportWorkbenchService.requireSafeId(admin);
        if(app) {
            Long customer=attachments.actor("USER");
            if(mapper.appVisible(customer,admin)!=1)throw missing();
        } else {
            attachments.actor("ADMIN");if(!SupportOwnershipService.hasAuthority("platform_a1_read"))throw new BizException(403,"AVATAR_READ_FORBIDDEN");
        }
        return attachedContent(admin);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public SupportAttachmentService.Content supportContent(Long admin,Long customer) {
        Long actor=attachments.actor("ADMIN");SupportWorkbenchService.requireSafeId(admin);
        boolean roster=SupportOwnershipService.hasAuthority("service_m1_read");
        if(!roster && !SupportOwnershipService.hasAuthority("service_m3_read"))throw new BizException(403,"AVATAR_READ_FORBIDDEN");
        if(customer!=null) {
            SupportWorkbenchService.requireSafeId(customer);
            ownership.lockCustomer(customer);ownership.requireRead(customer);
            if(mapper.appVisible(customer,admin)!=1)throw missing();
        } else {
            if(!roster)throw new BizException(403,"AVATAR_READ_FORBIDDEN");
            if(!ownership.canReadAgent(actor,admin))throw missing();
            if(!rosterTarget(admin))throw missing();
        }
        return attachedContent(admin);
    }
    private boolean rosterTarget(Long admin) {
        String role=roles.activeRoleCode(admin);
        // Existing SUPPORT roster reads include disabled seats visible to their manager.
        if("SUPPORT".equals(role))return mapper.rosterAdminForShare(admin)!=null;
        return "SUPER_ADMIN".equals(role) && (groups.qualificationCurrent(admin,"SUPERVISOR")!=null
                || (groups.qualificationCurrent(admin,"SERVICE")!=null && mapper.enabledServiceProfile(admin)!=null));
    }
    /** Only the current public service can construct this authorization; private avatar scopes are unchanged. */
    @Transactional(propagation=Propagation.MANDATORY)
    public SupportAttachmentService.Content publicLeaderboardContent(SupportLeaderboardService.AvatarAuthorization authorization) {
        if(authorization==null || !TransactionSynchronizationManager.isActualTransactionActive())
            throw new BizException(403,"AVATAR_READ_FORBIDDEN");
        Long actor=attachments.actor("ADMIN");
        if(actor!=authorization.actor() || (!SupportOwnershipService.hasAuthority("service_m1_read")
            && !SupportOwnershipService.hasAuthority("service_m3_read")))throw new BizException(403,"AVATAR_READ_FORBIDDEN");
        var reference=mapper.reference(authorization.agent());
        if(reference==null || reference.get("assetId")==null)throw missing();
        if(!Objects.equals(reference.get("assetId"),authorization.assetId())
            || !(reference.get("version") instanceof Number version) || version.longValue()!=authorization.assetVersion())throw conflict();
        return attachedContent(authorization.agent());
    }
    private SupportAttachmentService.Content attachedContent(Long admin) {
        var ref=mapper.reference(admin);if(ref==null || ref.get("assetId")==null)throw missing();
        var row=find(ref.get("assetId").toString());
        if(!"ATTACHED".equals(row.state()) || !admin.equals(row.attachedAdminId()))throw missing();
        return bytes(row);
    }
    private SupportAttachmentService.Content bytes(SupportAvatarAsset row) {
        try(InputStream stream=storage.get(row.objectKey())) {
            byte[] bytes=stream.readNBytes(Math.toIntExact(row.byteCount())+1);
            if(bytes.length!=row.byteCount())throw new IOException();return new SupportAttachmentService.Content(row.mime(),bytes);
        }catch(IOException|RuntimeException ex){throw new BizException(503,"AVATAR_STORAGE_UNAVAILABLE");}
    }
    private Long superWriter(){Long actor=attachments.actor("ADMIN");ownership.requireSuperAdminSnapshot();
        if(!SupportOwnershipService.hasAuthority("platform_a1_write"))throw new BizException(403,"AVATAR_WRITE_FORBIDDEN");return actor;}
    private SupportAvatarAsset find(String id){if(id==null || !id.matches("[a-f0-9-]{36}"))throw missing();var row=mapper.lock(id);if(row==null)throw missing();return row;}
    private static void ready(SupportAvatarAsset row){if(!"READY".equals(row.state()) || !row.expiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)))throw conflict();}
    private static void token(String value){if(value==null || !value.matches("[A-Za-z0-9_-]{8,128}"))throw new BizException(422,"AVATAR_COMMAND_INVALID");}
    private static Map<String,Object> view(SupportAvatarAsset row){return Map.of("assetId",row.id(),"status",row.state(),"expiresAt",row.expiresAt().atOffset(ZoneOffset.UTC).toString(),"previewRef","/api/admin/platform/accounts/avatar-assets/"+row.id());}
    private static Map<String,Object> ownedView(SupportAvatarAsset row,boolean self){var value=new LinkedHashMap<>(view(row));if(self)value.put("previewRef","/api/admin/platform/accounts/self/avatar-assets/"+row.id());return value;}
    private static BizException conflict(){return new BizException(409,"AVATAR_STATE_CONFLICT");}
    private static BizException missing(){return new BizException(404,"AVATAR_NOT_FOUND");}
}
