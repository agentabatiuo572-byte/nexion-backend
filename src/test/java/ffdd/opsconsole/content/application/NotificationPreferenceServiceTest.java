package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.content.domain.NotificationPreferenceView;
import ffdd.opsconsole.content.mapper.NotificationPreferenceMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;

class NotificationPreferenceServiceTest {
    private final NotificationPreferenceMapper mapper = mock(NotificationPreferenceMapper.class);
    private final NotificationPreferenceService service = new NotificationPreferenceService(mapper);

    @Test
    void missingRowReadsAllCategoriesEnabled() {
        when(mapper.findByUserId(7L)).thenReturn(null);

        var result = service.get(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isEqualTo(NotificationPreferenceView.allEnabled(7L));
        verify(mapper).findByUserId(7L);
    }

    @Test
    void patchUsesOneAtomicFieldUpdateThenReadsBackCanonicalState() {
        var canonical = new NotificationPreferenceView(7L, false, true, true, true, true, true);
        var reads = new AtomicInteger();
        when(mapper.findByUserId(7L)).thenAnswer(invocation -> {
            reads.incrementAndGet();
            return canonical;
        });

        var result = service.patch(7L, new NotificationPreferenceService.PatchRequest(false, null, null, null, null, null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().commission()).isFalse();
        assertThat(result.getData().team()).isTrue();
        InOrder order = inOrder(mapper);
        order.verify(mapper).upsert(7L, false, null, null, null, null, null);
        order.verify(mapper).findByUserId(7L);
        assertThat(reads).hasValue(1);
        verify(mapper, never()).upsert(eq(8L), any(), any(), any(), any(), any(), any());
    }

    @Test
    void emptyPatchIsRejectedInsteadOfSilentlySucceeding() {
        var result = service.patch(7L, new NotificationPreferenceService.PatchRequest(null, null, null, null, null, null));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("NOTIFICATION_PREFERENCES_PATCH_EMPTY");
        verifyNoInteractions(mapper);
    }

    @Test
    void nullPatchIsRejectedInsteadOfSilentlySucceeding() {
        var result = service.patch(7L, null);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("NOTIFICATION_PREFERENCES_PATCH_EMPTY");
        verifyNoInteractions(mapper);
    }

    @Test
    void unauthenticatedAccessFailsClosedWithoutTouchingMapper() {
        assertThat(service.get(null).getCode()).isEqualTo(403);
        assertThat(service.patch(0L, null).getCode()).isEqualTo(403);
        verifyNoInteractions(mapper);
    }
}
