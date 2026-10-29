package ffdd.opsconsole.content.push;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import ffdd.opsconsole.content.push.mapper.NativePushMapper;
import ffdd.opsconsole.content.push.mapper.NativePushMapper.Notification;
import ffdd.opsconsole.shared.security.CurrentAppSessionService;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

/** Real MVC JSON entry -> real registration/lookup service -> controlled session/database boundaries. */
class NativePushControllerTest {
    final NativePushMapper mapper=mock(NativePushMapper.class);
    final CurrentAppSessionService sessions=mock(CurrentAppSessionService.class);
    final NativePushService service=new NativePushService(mapper,sessions);
    final MockMvc mvc=MockMvcBuilders.standaloneSetup(new NativePushController(service)).build();
    final UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("701","unused",java.util.List.of());
    UserSessionEntity session() { var s=new UserSessionEntity(); s.setUserId(701L); s.setInstallationId("install-701"); s.setSessionChainId("chain"); return s; }

    @Test void actualJsonRegistrationHeaderAndBearerIdentityReachOwnedBinding() throws Exception {
        when(sessions.requireCurrent(auth,"install-701",false)).thenReturn(session());
        mvc.perform(put("/api/me/push-installations/install-701").principal(auth)
                .header("X-Push-Binding-Token","a".repeat(64)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"fcm:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\",\"provider\":\"FCM\",\"platform\":\"android\",\"permission\":\"granted\",\"appVersion\":\"1\",\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(mapper).insert(argThat(i -> i.userId()==701L && i.installationId().equals("install-701")));
    }
    @Test void actualLookupEnvelopeUsesStringNotificationIdAndPageSupportedCidQuery() throws Exception {
        when(sessions.requireSession(auth)).thenReturn(session());
        when(mapper.ownedNotification(123,701)).thenReturn(new Notification(123,"SUPPORT_REPLY","/pages/support/chat?cid=conversation-1","en"));
        when(mapper.ownedConversation("conversation-1",701)).thenReturn(1);
        mvc.perform(get("/api/me/push-notifications/123").principal(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.notificationId").value("123"))
                .andExpect(jsonPath("$.data.notificationId").isString()).andExpect(jsonPath("$.data.route").value("/pages/support/chat?cid=conversation-1"));
    }
}
