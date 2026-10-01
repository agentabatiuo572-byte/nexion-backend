package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAvatarAsset;
import ffdd.opsconsole.content.mapper.SupportAdminAvatarMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
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

    @Transactional
    public Map<String,Object> upload(String client,String key,MultipartFile file) {
        Long actor=superWriter();token(client);token(key);
        var limits=policy.view();if(!limits.available())throw new BizException(422,"AVATAR_POLICY_UNCONFIGURED");
        var image=SupportAttachmentService.decode(file,limits);
        String hash=image.rawHash();var previous=mapper.prior(actor,client,key);
        if(!previous.isEmpty()) {
            if(previous.size()!=1)throw conflict();var row=previous.get(0);
            if(!client.equals(row.clientUploadId()) || !key.equals(row.idempotencyKey()) || !hash.equals(row.requestHash()))throw conflict();
            if(!"ATTACHED".equals(row.state()))ready(row);
            return view(row);
        }
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
        storage.put(location,row.mime(),new ByteArrayInputStream(image.content()),row.byteCount());return view(row);
    }

    /** Called after the original account version CAS, in that same transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void attach(Long admin,String assetId) {
        Long actor=superWriter();SupportWorkbenchService.requireSafeId(admin);
        var row=find(assetId);if(!actor.equals(row.uploaderId()))throw missing();
        var current=mapper.reference(admin);
        if(current!=null && assetId.equals(current.get("assetId")))return;
        ready(row);if(!storage.exists(row.objectKey()))throw new BizException(503,"AVATAR_STORAGE_UNAVAILABLE");
        if(mapper.attach(assetId,admin)!=1 || mapper.accountAvatar(admin,assetId)==0)throw conflict();
    }

    @Transactional
    public Map<String,Object> cancel(String assetId,String key) {
        Long actor=superWriter();token(key);var row=find(assetId);
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
        return view(find(assetId));
    }

    @Transactional
    public SupportAttachmentService.Content preview(String assetId) {
        Long actor=superWriter();var row=find(assetId);if(!actor.equals(row.uploaderId()))throw missing();
        if(!"ATTACHED".equals(row.state()))ready(row);return bytes(row);
    }
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
            if(!ownership.supervisor(actor)) {
                ownership.requireEligibleAgent();
                if(!admin.equals(actor))throw missing();
            }
            if(mapper.rosterAdminForShare(admin)==null || !"SUPPORT".equals(roles.activeRoleCode(admin)))throw missing();
        }
        return attachedContent(admin);
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
    private static BizException conflict(){return new BizException(409,"AVATAR_STATE_CONFLICT");}
    private static BizException missing(){return new BizException(404,"AVATAR_NOT_FOUND");}
}
