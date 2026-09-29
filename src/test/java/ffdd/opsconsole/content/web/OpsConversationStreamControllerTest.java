package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.content.application.ConversationMessageEvent;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
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
        authenticateAs("1");

        assertThat(controller.stream("Bearer test")).isSameAs(emitter);

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
        authenticateAs("1");

        assertThat(controller.stream("Bearer test")).isSameAs(emitter);

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(controller.activeEmitterCount()).isZero();
        controller.shutdown();
    }

    @Test
    void streamRetainsM3ReadOnlyAuthorityBoundary() throws Exception {
        PreAuthorize authorize = OpsConversationStreamController.class
                .getMethod("stream",String.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(authorize.value()).isEqualTo("hasAuthority('service_m3_read')");
    }

    @Test
    void statusProbeIsAReadOnlyTerminalAuthorizationPreflight() throws Exception {
        PreAuthorize authorize = OpsConversationStreamController.class
                .getMethod("status")
                .getAnnotation(PreAuthorize.class);

        ResponseEntity<Void> response = new OpsConversationStreamController(ffdd.opsconsole.content.SupportTestDependencies.ownership(), authentication()).status();

        assertThat(authorize.value()).isEqualTo("hasAuthority('service_m3_read')");
        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void streamDeliversConversationTerminalStatusEvent() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        OpsConversationStreamController controller = controllerUsing(emitter);
        authenticateAs("1");
        controller.stream("Bearer test");

        controller.onConversationMessage(ConversationMessageEvent.builder()
                .conversationNo("CV-TERMINAL-1")
                .eventType(ConversationMessageEvent.EventType.STATUS)
                .senderType("SYSTEM")
                .senderName("System")
                .body("CONVERTED_TO_TICKET")
                .ts(LocalDateTime.now())
                .build());

        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(controller.activeEmitterCount()).isEqualTo(1);
        controller.shutdown();
    }

    private OpsConversationStreamController controllerUsing(SseEmitter emitter) {
        return new OpsConversationStreamController(ffdd.opsconsole.content.SupportTestDependencies.ownership(), authentication()) {
            @Override
            protected SseEmitter createEmitter(long timeoutMs) {
                return emitter;
            }
        };
    }

    private ffdd.opsconsole.shared.security.JwtAuthenticationFilter authentication() {
        var filter=mock(ffdd.opsconsole.shared.security.JwtAuthenticationFilter.class);
        org.mockito.Mockito.when(filter.authenticateSocketToken("test")).thenReturn(
            new UsernamePasswordAuthenticationToken("1",null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("service_m3_read"))));
        return filter;
    }
    private void authenticateAs(String adminId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminId, null, List.of()));
    }
}
