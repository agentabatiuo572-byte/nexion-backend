package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.content.domain.SupportAvatarAsset;
import ffdd.opsconsole.content.mapper.SupportAdminAvatarMapper;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.exception.BizException;
import org.springframework.mock.web.MockMultipartFile;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.*;

class SupportDefaultAvatarServiceTest {
    final SupportAdminAvatarMapper mapper=mock(SupportAdminAvatarMapper.class);
    final AdminRoleRelationMapper roles=mock(AdminRoleRelationMapper.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final SupportAttachmentService attachments=mock(SupportAttachmentService.class);
    final SupportAttachmentPolicy policy=new SupportAttachmentPolicy();
    final ObjectStorageService storage=mock(ObjectStorageService.class);
    SupportAdminAvatarService service;
    @BeforeEach void setup() {
        policy.setAllowedMimeTypes(List.of("image/png","image/jpeg"));policy.setMaxBytes(1_048_576L);policy.setMaxPixels(1_000_000L);policy.setTtlSeconds(300L);
        when(attachments.actor("ADMIN")).thenReturn(1L);when(roles.activeRoleCode(anyLong())).thenReturn("SUPPORT");
        when(mapper.attach(anyString(),anyLong())).thenReturn(1);when(mapper.accountAvatar(anyLong(),anyString())).thenReturn(1);when(storage.exists(anyString())).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1","ignored",List.of(new SimpleGrantedAuthority("platform_a1_write"))));
        TransactionSynchronizationManager.initSynchronization();service=new SupportAdminAvatarService(mapper,roles,ownership,attachments,policy,storage,mock(ffdd.opsconsole.content.mapper.SupportGroupMapper.class));
    }
    @AfterEach void clear(){TransactionSynchronizationManager.clearSynchronization();SecurityContextHolder.clearContext();}
    @Test void eachCreatedAccountGetsIndependentPrivateAssetAndSourceAudit() {
        assertThat(service.attachDefaultCreated(9L,"MALE")).containsEntry("defaultAvatarStatus","ATTACHED").containsKey("sourceHash");
        assertThat(service.attachDefaultCreated(10L,"FEMALE")).containsEntry("defaultAvatarStatus","ATTACHED").containsKey("poolEntryId");
        var rows=ArgumentCaptor.forClass(SupportAvatarAsset.class);verify(mapper,times(2)).insertAsset(rows.capture());
        assertThat(rows.getAllValues().get(0).id()).isNotEqualTo(rows.getAllValues().get(1).id());
        assertThat(rows.getAllValues().get(0).objectKey()).isNotEqualTo(rows.getAllValues().get(1).objectKey());
        assertThat(rows.getAllValues()).allSatisfy(row->{assertThat(row.state()).isEqualTo("READY");assertThat(row.byteCount()).isLessThanOrEqualTo(policy.getMaxBytes());});
        verify(mapper).attach(rows.getAllValues().get(0).id(),9L);verify(mapper).attach(rows.getAllValues().get(1).id(),10L);
    }
    @Test void defaultPrepareReplayReturnsSameAssetAndRejectsChangedPoolBeforeSampling() {
        var result=service.prepareDefault("MALE","client-key-123","command-key-123");
        var rows=ArgumentCaptor.forClass(SupportAvatarAsset.class);verify(mapper).insertAsset(rows.capture());
        when(mapper.prior(1L,"client-key-123","command-key-123")).thenReturn(List.of(rows.getValue()));
        assertThat(service.prepareDefault("MALE","client-key-123","command-key-123").get("assetId")).isEqualTo(result.get("assetId"));
        assertThatThrownBy(()->service.prepareDefault("FEMALE","client-key-123","command-key-123")).hasMessage("AVATAR_STATE_CONFLICT");
        verify(mapper,times(1)).insertAsset(any());verify(storage,times(1)).put(anyString(),anyString(),any(),anyLong());
    }
    @Test void storageWriteFailureIsNotConvertedToSuccessfulMissingAvatar() {
        doThrow(new IllegalStateException("storage-offline")).when(storage).put(anyString(),anyString(),any(),anyLong());
        assertThatThrownBy(()->service.attachDefaultCreated(9L,"MALE")).isInstanceOf(BizException.class).hasMessage("AVATAR_STORAGE_UNAVAILABLE")
                .satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(503)).hasCauseInstanceOf(IllegalStateException.class);
        verify(mapper,never()).attach(anyString(),anyLong());verify(mapper,never()).accountAvatar(anyLong(),anyString());
        var rows=ArgumentCaptor.forClass(SupportAvatarAsset.class);verify(mapper).insertAsset(rows.capture());
        for(var synchronization:TransactionSynchronizationManager.getSynchronizations())synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage,times(1)).remove(rows.getValue().objectKey());verify(storage,never()).remove("private/original-avatar");
    }
    @Test void manualUploadStorageBiz500Becomes503AndRollbackRemovesExactlyItsPreparedObject() {
        var choice=new SupportDefaultAvatarCatalog().select("MALE",policy.view()).orElseThrow();
        var file=new MockMultipartFile("file","portrait.jpg","image/jpeg",choice.image().content());
        var offline=new BizException(500,"OBJECT_STORAGE_WRITE_FAILED");doThrow(offline).when(storage).put(anyString(),anyString(),any(),anyLong());
        assertThatThrownBy(()->service.upload("client-upload-503","command-upload-503",file)).isInstanceOf(BizException.class)
                .hasMessage("AVATAR_STORAGE_UNAVAILABLE").satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(503)).hasCause(offline);
        var rows=ArgumentCaptor.forClass(SupportAvatarAsset.class);verify(mapper).insertAsset(rows.capture());
        assertThat(rows.getValue().state()).isEqualTo("READY");verify(mapper,never()).attach(anyString(),anyLong());verify(mapper,never()).accountAvatar(anyLong(),anyString());
        for(var synchronization:TransactionSynchronizationManager.getSynchronizations())synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage,times(1)).remove(rows.getValue().objectKey());verify(storage,never()).remove("private/original-avatar");
    }
    @Test void attachFailurePropagatesAndRollbackCleanupRemovesOnlyNewObject() {
        when(mapper.attach(anyString(),anyLong())).thenReturn(0);
        assertThatThrownBy(()->service.attachDefaultCreated(9L,"MALE")).hasMessage("AVATAR_STATE_CONFLICT");
        verify(mapper,never()).accountAvatar(anyLong(),anyString());
        var rows=ArgumentCaptor.forClass(SupportAvatarAsset.class);verify(mapper).insertAsset(rows.capture());
        for(var synchronization:TransactionSynchronizationManager.getSynchronizations())synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage).remove(rows.getValue().objectKey());
    }
}
