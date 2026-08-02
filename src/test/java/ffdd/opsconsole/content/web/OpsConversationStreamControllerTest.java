package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class OpsConversationStreamControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void streamSendsReadyCommentImmediatelyAfterRegistering() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        OpsConversationStreamController controller = controllerUsing(emitter);
        authenticateAs("m3-agent");

        assertThat(controller.stream()).isSameAs(emitter);

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(controller.activeEmitterCount()).isEqualTo(1);
        controller.shutdown();
    }

    @Test
    void streamFailsClosedAndUnregistersWhenInitialReadyFrameCannotBeSent() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("client closed before ready"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        OpsConversationStreamController controller = controllerUsing(emitter);
        authenticateAs("m3-agent");

        assertThat(controller.stream()).isSameAs(emitter);

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(controller.activeEmitterCount()).isZero();
        controller.shutdown();
    }

    @Test
    void streamRetainsM3ReadOnlyAuthorityBoundary() throws Exception {
        PreAuthorize authorize = OpsConversationStreamController.class
                .getMethod("stream")
                .getAnnotation(PreAuthorize.class);

        assertThat(authorize.value()).isEqualTo("hasAuthority('service_m3_read')");
    }

    private OpsConversationStreamController controllerUsing(SseEmitter emitter) {
        return new OpsConversationStreamController() {
            @Override
            protected SseEmitter createEmitter(long timeoutMs) {
                return emitter;
            }
        };
    }

    private void authenticateAs(String adminId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminId, null, List.of()));
    }
}
