package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.content.domain.SupportAvatarAsset;
import ffdd.opsconsole.content.mapper.SupportAdminAvatarMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification;
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
                mock(SupportAttachmentPolicy.class),mock(ObjectStorageService.class),mock(ffdd.opsconsole.content.mapper.SupportGroupMapper.class));
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
                mock(SupportAttachmentPolicy.class),storage,mock(ffdd.opsconsole.content.mapper.SupportGroupMapper.class));
        assertThat(service.supportContent(7L,23L).bytes()).containsExactly(bytes);
        verify(ownership).requireRead(23L);verify(ownership,never()).canReadAgent(any(),any());
    }

    @Test void qualifiedSuperAdminServiceAvatarIsReadableWithinRosterScope() {
        var h=new Roster();h.qualification("SERVICE");when(h.mapper.enabledServiceProfile(7L)).thenReturn(7L);
        assertThat(h.service.supportContent(7L,null).bytes()).containsExactly(1,2);
        verify(h.ownership).canReadAgent(6L,7L);
    }

    @Test void qualifiedSuperAdminSupervisorNeedsNoServiceProfile() {
        var h=new Roster();h.qualification("SUPERVISOR");
        assertThat(h.service.supportContent(7L,null).bytes()).containsExactly(1,2);
        verify(h.mapper,never()).rosterAdminForShare(any());
    }

    @Test void superAdminRoleAloneCannotReadAnUnqualifiedTargetsAvatar() {
        var h=new Roster();when(h.mapper.rosterAdminForShare(7L)).thenReturn(7L);
        h.denied();
    }

    @Test void revokedCurrentQualificationStopsFurtherAvatarReads() {
        var h=new Roster();h.qualification("SERVICE");when(h.mapper.enabledServiceProfile(7L)).thenReturn(7L);
        assertThat(h.service.supportContent(7L,null).bytes()).containsExactly(1,2);
        when(h.groups.qualificationCurrent(7L,"SERVICE")).thenReturn(null);
        clearInvocations(h.mapper,h.storage);
        h.denied();
    }

    @Test void serviceQualificationWithoutEnabledProfileIsNotEnough() {
        var h=new Roster();h.qualification("SERVICE");h.denied();
    }

    @Test void qualifiedSuperAdminOutsideReaderScopeIsNotRead() {
        var h=new Roster();h.qualification("SUPERVISOR");when(h.ownership.canReadAgent(6L,7L)).thenReturn(false);
        h.denied();verifyNoInteractions(h.groups,h.roles);
    }

    @Test void anotherRoleWithStaleQualificationsIsNotRead() {
        var h=new Roster();when(h.roles.activeRoleCode(7L)).thenReturn("CONTENT");h.qualification("SUPERVISOR");
        h.denied();verifyNoInteractions(h.groups);
    }

    @Test void supportRosterKeepsExistingDisabledSeatVisibility() {
        var h=new Roster();when(h.roles.activeRoleCode(7L)).thenReturn("SUPPORT");
        when(h.mapper.rosterAdminForShare(7L)).thenReturn(7L);
        assertThat(h.service.supportContent(7L,null).bytes()).containsExactly(1,2);
        verify(h.mapper,never()).enabledServiceProfile(any());verifyNoInteractions(h.groups);
    }

    @Test void m3PermissionAloneStillCannotReadRosterAvatar() {
        var h=new Roster();authenticate("service_m3_read");
        assertThatThrownBy(()->h.service.supportContent(7L,null)).hasMessage("AVATAR_READ_FORBIDDEN");
        verifyNoInteractions(h.ownership,h.groups,h.roles,h.mapper,h.storage);
    }

    private static final class Roster {
        final SupportAdminAvatarMapper mapper=mock(SupportAdminAvatarMapper.class);
        final SupportGroupMapper groups=mock(SupportGroupMapper.class);
        final AdminRoleRelationMapper roles=mock(AdminRoleRelationMapper.class);
        final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
        final SupportAttachmentService attachments=mock(SupportAttachmentService.class);
        final ObjectStorageService storage=mock(ObjectStorageService.class);
        final SupportAdminAvatarService service=new SupportAdminAvatarService(mapper,roles,ownership,attachments,
                mock(SupportAttachmentPolicy.class),storage,groups);
        Roster() {
            authenticate("service_m1_read");when(attachments.actor("ADMIN")).thenReturn(6L);
            when(ownership.canReadAgent(6L,7L)).thenReturn(true);when(roles.activeRoleCode(7L)).thenReturn("SUPER_ADMIN");
            when(mapper.enabledServiceProfile(7L)).thenReturn(null);
            when(mapper.rosterAdminForShare(7L)).thenReturn(null);
            String asset="00000000-0000-0000-0000-000000000001";
            when(mapper.reference(7L)).thenReturn(Map.of("assetId",asset));
            when(mapper.lock(asset)).thenReturn(new SupportAvatarAsset(asset,1L,"upload-key","receipt-key","hash","image/png",2L,
                    "private/avatar","ATTACHED",7L,LocalDateTime.now().plusHours(1)));
            when(storage.get("private/avatar")).thenAnswer(ignored->new ByteArrayInputStream(new byte[]{1,2}));
        }
        void qualification(String kind) {
            when(groups.qualificationCurrent(7L,kind)).thenReturn(new Qualification(1L,7L,kind,"ENABLED",1L,LocalDateTime.now().minusDays(1)));
        }
        void denied() {
            assertThatThrownBy(()->service.supportContent(7L,null)).hasMessage("AVATAR_NOT_FOUND");
            verify(mapper,never()).reference(any());verifyNoInteractions(storage);
        }
    }

    private static void authenticate(String permission) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(6L,"ignored",List.of(new SimpleGrantedAuthority(permission))));
    }
}
