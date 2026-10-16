package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.*;
import ffdd.opsconsole.content.domain.SupportAvatarAsset;
import ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification;
import ffdd.opsconsole.content.dto.SupportAvatarRequests.*;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;

class SupportSelfAvatarServiceTest {
    final SupportAdminAvatarMapper mapper=mock(SupportAdminAvatarMapper.class);
    final SupportAttachmentService attachments=mock(SupportAttachmentService.class);
    final SupportAttachmentPolicy policy=mock(SupportAttachmentPolicy.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final AdminMapper admins=mock(AdminMapper.class);
    final AdminRoleRelationMapper roles=mock(AdminRoleRelationMapper.class);
    final SupportGroupMapper groups=mock(SupportGroupMapper.class);
    final AuditObjectLockMapper locks=mock(AuditObjectLockMapper.class);
    final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final ObjectStorageService storage=mock(ObjectStorageService.class);
    final SupportAdminAvatarService avatars=new SupportAdminAvatarService(mapper,roles,ownership,attachments,policy,storage,mock(ffdd.opsconsole.content.mapper.SupportGroupMapper.class));
    final SupportSelfAvatarService service=new SupportSelfAvatarService(avatars,mapper,attachments,policy,ownership,admins,roles,groups,locks,idempotency,audit);
    final String oldAsset="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",newAsset="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    AdminEntity admin;
    String persistedAsset;long persistedAvatarVersion;
    @BeforeEach void setup() {
        admin=new AdminEntity();admin.setId(6L);admin.setUsername("advisor");admin.setStatus(1);admin.setVersion(2L);
        when(attachments.actor("ADMIN")).thenReturn(6L);when(admins.selectActiveForUpdate(6L)).thenReturn(admin);
        when(roles.activeRoleCode(6L)).thenReturn("SUPPORT");when(mapper.enabledServiceProfile(6L)).thenReturn(6L);
        when(groups.qualificationCurrent(6L,"SERVICE")).thenReturn(new Qualification(1L,6L,"SERVICE","ENABLED",1L,LocalDateTime.now().minusDays(1)));
        persistedAsset=oldAsset;persistedAvatarVersion=3L;
        when(mapper.selfReference(6L)).thenAnswer(invocation->Map.of("assetId",persistedAsset,"avatarVersion",persistedAvatarVersion,"avatarGender","FEMALE"));
        when(mapper.reference(6L)).thenAnswer(invocation->Map.of("assetId",persistedAsset,"version",persistedAvatarVersion));
        when(mapper.lock(newAsset)).thenReturn(asset(6L));when(storage.exists("private/avatar")).thenReturn(true);
        when(mapper.attach(newAsset,6L)).thenReturn(1);when(mapper.accountAvatar(6L,newAsset)).thenAnswer(invocation->{persistedAsset=newAsset;persistedAvatarVersion++;return 1;});
        when(admins.incrementVersionIfVersion(6L,2L)).thenAnswer(invocation->{admin.setVersion(3L);return 1;});
        var receipts=new HashMap<String,Object>();var hashes=new HashMap<String,String>();
        when(idempotency.execute(anyString(),anyString(),anyString(),any(),any())).thenAnswer(invocation->{
            String key=invocation.getArgument(0)+"|"+invocation.getArgument(1),hash=invocation.getArgument(2);
            if(hashes.containsKey(key)){if(!hash.equals(hashes.get(key)))throw new BizException(409,"IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");return receipts.get(key);}
            Object result=((Supplier<?>)invocation.getArgument(4)).get();hashes.put(key,hash);receipts.put(key,result);return result;
        });
    }
    SupportAvatarAsset asset(Long uploader){return new SupportAvatarAsset(newAsset,uploader,"client-key","command-key","hash","image/jpeg",20L,"private/avatar","READY",null,LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));}
    @Test void saveUsesPrincipalTargetAndReplaysOneCasAndAudit() {
        var request=new SelfSave(newAsset,"2","换头像");
        assertThat(service.save("save-key-123",request)).containsEntry("accountVersion","3").containsEntry("assetId",newAsset).containsEntry("avatarVersion",4L);
        assertThat(service.save("save-key-123",request)).containsEntry("accountVersion","3").containsEntry("assetId",newAsset).containsEntry("avatarVersion",4L);
        verify(admins,times(1)).incrementVersionIfVersion(6L,2L);verify(mapper,times(1)).attach(newAsset,6L);
        verify(audit,times(1)).recordRequired(any());verify(mapper,never()).accountAvatar(eq(7L),anyString());
        assertThatThrownBy(()->service.save("save-key-123",new SelfSave(newAsset,"2","different"))).hasMessage("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
    }
    @Test void foreignAssetAndStorageFailureNeverChangeAccountAvatarOrWriteSuccessAudit() {
        when(mapper.lock(newAsset)).thenReturn(asset(7L));
        assertThatThrownBy(()->service.save("save-key-123",new SelfSave(newAsset,"2","reason"))).hasMessage("AVATAR_NOT_FOUND");
        verify(mapper,never()).attach(anyString(),anyLong());verify(audit,never()).recordRequired(any());
    }
    @Test void storageUnavailableLeavesAttachmentAndSuccessAuditUntouched() {
        when(storage.exists("private/avatar")).thenReturn(false);
        assertThatThrownBy(()->service.save("save-key-123",new SelfSave(newAsset,"2","reason"))).hasMessage("AVATAR_STORAGE_UNAVAILABLE");
        verify(mapper,never()).accountAvatar(anyLong(),anyString());verify(audit,never()).recordRequired(any());
    }
    @Test void storageExistsBiz500Becomes503BeforeAnyAttachmentAndKeepsOriginalAvatarReference() {
        var offline=new BizException(500,"OBJECT_STORAGE_METADATA_FAILED");when(storage.exists("private/avatar")).thenThrow(offline);
        assertThatThrownBy(()->service.save("save-storage-503",new SelfSave(newAsset,"2","reason"))).isInstanceOf(BizException.class)
                .hasMessage("AVATAR_STORAGE_UNAVAILABLE").satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(503)).hasCause(offline);
        verify(storage,times(1)).exists("private/avatar");verify(mapper,never()).attach(anyString(),anyLong());
        verify(mapper,never()).accountAvatar(anyLong(),anyString());verify(audit,never()).recordRequired(any());
        assertThat(persistedAsset).isEqualTo(oldAsset);assertThat(persistedAvatarVersion).isEqualTo(3L);
        assertThat(service.snapshot()).containsEntry("assetId",oldAsset).containsEntry("avatarVersion",3L);
        // Unit collaborators do not emulate rollback of the preceding account-version CAS; native must prove that separately.
    }
    @Test void auditFailureEscapesSaveAndDoesNotCommitAnIdempotencyReceipt() {
        doThrow(new IllegalStateException("AUDIT_WRITE_FAILED")).when(audit).recordRequired(any());
        assertThatThrownBy(()->service.save("save-key-audit",new SelfSave(newAsset,"2","reason")))
                .isInstanceOf(IllegalStateException.class).hasMessage("AUDIT_WRITE_FAILED");
        verify(audit).recordRequired(any());verify(audit,never()).record(any());
        // Unit collaborators do not emulate database rollback. The transaction and native gate must prove restoration.
        admin.setVersion(2L);persistedAsset=oldAsset;persistedAvatarVersion=3L;
        doNothing().when(audit).recordRequired(any());
        assertThat(service.save("save-key-audit",new SelfSave(newAsset,"2","reason"))).containsEntry("assetId",newAsset);
        verify(admins,times(2)).incrementVersionIfVersion(6L,2L);
    }
    @Test void defaultPrepareAuditFailureEscapesWithoutAReceipt() {
        var mockedAvatars=mock(SupportAdminAvatarService.class);
        var target=new SupportSelfAvatarService(mockedAvatars,mapper,attachments,policy,ownership,admins,roles,groups,locks,idempotency,audit);
        when(mockedAvatars.prepareDefaultSelf(6L,"MALE","client-key","prepare-key"))
                .thenReturn(Map.of("assetId",newAsset,"poolEntryId","male-01","sourceHash","source"));
        doThrow(new IllegalStateException("AUDIT_WRITE_FAILED")).when(audit).recordRequired(any());
        var request=new DefaultPrepare("MALE","client-key");
        assertThatThrownBy(()->target.prepareDefault("prepare-key",request)).hasMessage("AUDIT_WRITE_FAILED");
        doNothing().when(audit).recordRequired(any());
        assertThat(target.prepareDefault("prepare-key",request)).containsEntry("assetId",newAsset);
        verify(mockedAvatars,times(2)).prepareDefaultSelf(6L,"MALE","client-key","prepare-key");
        verify(audit,never()).record(any());
    }
    @Test void selfPreviewNeverReadsAnAssetAttachedToSomeoneElseEvenIfActorUploadedIt() {
        var owned=asset(6L);when(mapper.lock(newAsset)).thenReturn(new SupportAvatarAsset(owned.id(),owned.uploaderId(),owned.clientUploadId(),owned.idempotencyKey(),owned.requestHash(),owned.mime(),owned.byteCount(),owned.objectKey(),"ATTACHED",7L,owned.expiresAt()));
        assertThatThrownBy(()->service.preview(newAsset)).hasMessage("AVATAR_NOT_FOUND");verifyNoInteractions(storage);
    }
    @Test void staleCasAndA2LockDoNotAttachOrWriteAudit() {
        assertThatThrownBy(()->service.save("save-key-123",new SelfSave(newAsset,"1","reason"))).hasMessage("ACCOUNT_VERSION_STALE");
        when(locks.countActiveByTarget("A","account","6")).thenReturn(1);
        assertThatThrownBy(()->service.save("save-key-124",new SelfSave(newAsset,"2","reason"))).hasMessage("OBJECT_LOCKED_BY_A2");
        verify(admins,never()).incrementVersionIfVersion(anyLong(),anyLong());verify(mapper,never()).attach(anyString(),anyLong());
    }
    @Test void disabledOrRevokedSelfCannotPrepareOrReadOrReplaySuccess() {
        admin.setStatus(0);assertThatThrownBy(service::snapshot).hasMessage("AVATAR_SELF_FORBIDDEN");admin.setStatus(1);
        when(groups.qualificationCurrent(6L,"SERVICE")).thenReturn(null);
        assertThatThrownBy(()->service.prepareDefault("prepare-key",new DefaultPrepare("MALE","client-key"))).hasMessage("AVATAR_SELF_FORBIDDEN");
        verifyNoInteractions(idempotency,storage);
    }
    @Test void supervisorCanEditSelfWithoutA1AndWithoutInventingAServiceSeat() {
        when(groups.qualificationCurrent(6L,"SERVICE")).thenReturn(null);
        when(groups.qualificationCurrent(6L,"SUPERVISOR")).thenReturn(new Qualification(2L,6L,"SUPERVISOR","ENABLED",1L,LocalDateTime.now().minusDays(1)));
        assertThat(service.snapshot().get("assetId")).isEqualTo(oldAsset);verifyNoInteractions(ownership);
        assertThatThrownBy(()->service.prepareAdmin("prepare-key",new DefaultPrepare("MALE","client-key"))).hasMessage("AVATAR_SELF_FORBIDDEN");
        verify(mapper,never()).insertAsset(any());
    }
}
