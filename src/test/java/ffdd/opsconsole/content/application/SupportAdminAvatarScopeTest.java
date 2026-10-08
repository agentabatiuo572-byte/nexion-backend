package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.content.domain.SupportAvatarAsset;
import ffdd.opsconsole.content.mapper.SupportAdminAvatarMapper;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SupportAdminAvatarScopeTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void supervisorFlagDoesNotGrantAnotherGroupsRosterAvatar() {
        var mapper=mock(SupportAdminAvatarMapper.class);var ownership=mock(SupportOwnershipService.class);
        var attachments=mock(SupportAttachmentService.class);when(attachments.actor("ADMIN")).thenReturn(6L);
        when(ownership.supervisor(6L)).thenReturn(true);
        authenticate("service_m1_read");
        var service=new SupportAdminAvatarService(mapper,mock(AdminRoleRelationMapper.class),ownership,attachments,
                mock(SupportAttachmentPolicy.class),mock(ObjectStorageService.class));
        assertThatThrownBy(()->service.supportContent(7L,null)).hasMessage("AVATAR_NOT_FOUND");
        verify(ownership).canReadAgent(6L,7L);verifyNoInteractions(mapper);
    }

    @Test void currentlyReadableCustomerCanSeeProvenHistoricalAuthorAvatar() {
        var mapper=mock(SupportAdminAvatarMapper.class);var ownership=mock(SupportOwnershipService.class);
        var attachments=mock(SupportAttachmentService.class);when(attachments.actor("ADMIN")).thenReturn(6L);
        var storage=mock(ObjectStorageService.class);String asset="00000000-0000-0000-0000-000000000001";
        var bytes=new byte[]{1,2};when(mapper.appVisible(23L,7L)).thenReturn(1);
        when(mapper.reference(7L)).thenReturn(Map.of("assetId",asset));
        when(mapper.lock(asset)).thenReturn(new SupportAvatarAsset(asset,1L,"upload-key","receipt-key","hash","image/png",2L,
                "private/avatar","ATTACHED",7L,LocalDateTime.now().plusHours(1)));
        when(storage.get("private/avatar")).thenReturn(new ByteArrayInputStream(bytes));
        authenticate("service_m3_read");
        var service=new SupportAdminAvatarService(mapper,mock(AdminRoleRelationMapper.class),ownership,attachments,
                mock(SupportAttachmentPolicy.class),storage);
        assertThat(service.supportContent(7L,23L).bytes()).containsExactly(bytes);
        verify(ownership).requireRead(23L);verify(ownership,never()).canReadAgent(any(),any());
    }

    private static void authenticate(String permission) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(6L,"ignored",List.of(new SimpleGrantedAuthority(permission))));
    }
}
