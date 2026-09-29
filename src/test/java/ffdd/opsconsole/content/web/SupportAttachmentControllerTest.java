package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.application.SupportAttachmentService;
import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class SupportAttachmentControllerTest {
    private final SupportAttachmentService service = mock(SupportAttachmentService.class);
    private final SupportAttachmentController controller = new SupportAttachmentController(service, new ObjectMapper());

    @Test void binaryResponseIsPrivateAndPreservesBytes() {
        when(service.actor("USER")).thenReturn(10L);
        when(service.content("id", "USER", 10L)).thenReturn(new SupportAttachmentService.Content("image/png", new byte[]{0, -1, 3}));
        var response = controller.appContent("id");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).containsExactly(0, -1, 3);
    }

    @Test void multipartIdsCannotTruncateOrOverflow() {
        for (String id : new String[]{"1.0", "1e2", "-1", "9007199254740992", "9223372036854775808", " 10", "01"}) {
            assertThatThrownBy(() -> controller.adminUpload(new MockMultipartFile("file", new byte[]{1}),
                    id, "20", "client-123", "upload-123")).isInstanceOf(BizException.class);
        }
        verify(service, never()).upload(any(), anyString(), any(), any(), anyString(), anyString(), any());
    }

    @Test void appUploadAlwaysUsesAuthenticatedCustomer() {
        when(service.actor("USER")).thenReturn(10L);
        var file = new MockMultipartFile("file", new byte[]{1});
        controller.appUpload(file, "client-123", "upload-123");
        verify(service).upload(10L, "USER", 10L, null, "client-123", "upload-123", file);
    }
}
