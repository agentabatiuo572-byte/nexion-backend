package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.mapper.AppConversationInboxMapper;
import org.junit.jupiter.api.Test;

class AppConversationInboxServiceTest {
    final AppConversationInboxMapper mapper = mock(AppConversationInboxMapper.class);
    final ProductionSupportPathGuard guard = mock(ProductionSupportPathGuard.class);
    final AppConversationInboxService service = new AppConversationInboxService(mapper, guard);

    @Test void rejectsNonUsersAndInvalidBoundariesWithoutWriting() {
        assertThat(service.dismiss(null, "CV-1", 11L).getCode()).isEqualTo(403);
        assertThat(service.dismiss(7L, "CV-1", 0L).getCode()).isEqualTo(400);
        verifyNoInteractions(mapper);
    }
    @Test void foreignOrPrivateMessageCannotBeUsedAsBoundary() {
        assertThat(service.dismiss(7L, "CV-foreign", 11L).getCode()).isEqualTo(404);
        verify(mapper, never()).dismiss(anyLong(), anyString(), anyLong());
    }
    @Test void retriesReturnMonotonicMarkerWithoutClosingOrDeletingAnything() {
        when(mapper.publicMessageExists(7L, "CV-1", 11L)).thenReturn(true);
        var marker = new AppConversationInboxMapper.Dismissal("CV-1", 15L);
        when(mapper.find(7L, "CV-1")).thenReturn(marker);
        assertThat(service.dismiss(7L, "CV-1", 11L).getData()).isEqualTo(marker);
        verify(mapper).dismiss(7L, "CV-1", 11L);
        verify(guard).requireAllowed(7L);
    }
}
