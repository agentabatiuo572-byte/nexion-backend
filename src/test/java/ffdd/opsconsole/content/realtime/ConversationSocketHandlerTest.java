package ffdd.opsconsole.content.realtime;
import com.fasterxml.jackson.databind.*;
import ffdd.opsconsole.content.application.ConversationMessageEvent;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.*;
import org.springframework.web.socket.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationSocketHandlerTest {
    ObjectMapper json=new ObjectMapper();
    ConversationSocketAccess access=mock(ConversationSocketAccess.class);
    ConversationSocketCommands commands=mock(ConversationSocketCommands.class);
    ConversationSocketTickets tickets=new ConversationSocketTickets(access);
    ConversationSocketHandler handler=new ConversationSocketHandler(json,tickets,access,commands);
    UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("12",null,List.of());
    List<String> frames=new CopyOnWriteArrayList<>(); WebSocketSession socket;
    @BeforeEach void setup()throws Exception{
        when(access.authenticate("jwt","USER")).thenReturn(auth);
        socket=mock(WebSocketSession.class);when(socket.getId()).thenReturn("one");when(socket.isOpen()).thenReturn(true);
        doAnswer(a->{frames.add(((TextMessage)a.getArgument(0)).getPayload());return null;}).when(socket).sendMessage(any());
        handler.afterConnectionEstablished(socket);
    }
    @AfterEach void close(){handler.stop();}
    void frame(Object body)throws Exception{handler.handleTextMessage(socket,new TextMessage(json.writeValueAsString(body)));}
    void login()throws Exception{frame(Map.of("type","auth","ticket",tickets.issue("Bearer jwt","USER")));}
    @Test void noDataOrCommandsBeforeTicketAuthentication()throws Exception{
        frame(Map.of("type","command","operation","create","requestId","1","body",Map.of()));
        verifyNoInteractions(commands);assertTrue(frames.get(0).contains("401"));verify(socket).close(any());
    }
    @Test void authenticatesEveryCommandAndReturnsItsPersistedResult()throws Exception{
        login();doReturn(ApiResult.ok(Map.of("messageId",31))).when(commands).execute(eq(auth),eq("USER"),any());
        frame(Map.of("type","command","operation","reply","requestId","req1","conversationNo","CV-1","idempotencyKey","stable-key","body",Map.of("body","hello")));
        assertTrue(frames.stream().anyMatch(s->s.contains("\"requestId\":\"req1\"")&&s.contains("\"messageId\":31")));
        verify(access,atLeast(3)).authenticate("jwt","USER");
        verify(commands).execute(eq(auth),eq("USER"),argThat(n->n.path("idempotencyKey").asText().equals("stable-key")));
    }
    @Test void revocationClosesBeforeTheNextWrite()throws Exception{
        login();when(access.authenticate("jwt","USER")).thenThrow(new BadCredentialsException("revoked"));
        frame(Map.of("type","command","operation","reply","requestId","req1","body",Map.of()));
        verifyNoInteractions(commands);verify(socket).close(new CloseStatus(4401));
    }
    @Test void cannotWatchAnotherUsersConversation()throws Exception{
        login();frame(Map.of("type","watch","conversationNo","CV-OTHER"));
        assertTrue(frames.stream().anyMatch(s->s.contains("404")));
        assertFalse(frames.stream().anyMatch(s->s.contains("presence")));
    }
    @Test void eventPayloadIsAnAuthorizedInvalidationWithoutPrivateSystemBody()throws Exception{
        login();when(access.canRead(auth,"USER","CV-1")).thenReturn(true);
        handler.changed(ConversationMessageEvent.builder().conversationNo("CV-1").body("internal transfer reason").build());
        long deadline=System.currentTimeMillis()+2000;
        while(frames.stream().noneMatch(s->s.contains("eventId"))&&System.currentTimeMillis()<deadline)Thread.sleep(10);
        assertTrue(frames.stream().anyMatch(s->s.contains("eventId")));
        assertFalse(frames.stream().anyMatch(s->s.contains("internal transfer reason")));
    }
    @Test void presenceRecoversAfterTheOutboundQueueRejectsABatch()throws Exception{
        when(access.canRead(auth,"USER","CV-1")).thenReturn(true);
        when(access.participants("CV-1")).thenReturn(Optional.of(new ConversationSocketAccess.Participants("12","seat")));
        when(access.presenceKeys(auth,"USER")).thenReturn(Set.of("USER:12"));
        login();frame(Map.of("type","watch","conversationNo","CV-1"));
        awaitPresence();frames.clear();
        var field=ConversationSocketHandler.class.getDeclaredField("outbound");field.setAccessible(true);
        var executor=(ThreadPoolExecutor)field.get(handler);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        executor.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        try {
            for(int i=0;i<2048;i++)assertTrue(executor.getQueue().offer(()->{}));
            frame(Map.of("type","watch","conversationNo","CV-1"));
            verify(socket,timeout(2000)).close(new CloseStatus(1013));
        } finally { release.countDown(); }
        long deadline=System.currentTimeMillis()+2000;
        while(!executor.getQueue().isEmpty()&&System.currentTimeMillis()<deadline)Thread.sleep(10);
        handler.afterConnectionEstablished(socket);
        login();frame(Map.of("type","watch","conversationNo","CV-1"));
        awaitPresence();
    }
    void awaitPresence()throws Exception{
        long deadline=System.currentTimeMillis()+2000;
        while(frames.stream().noneMatch(s->s.contains("\"type\":\"presence\""))&&System.currentTimeMillis()<deadline)Thread.sleep(10);
        assertTrue(frames.stream().anyMatch(s->s.contains("\"type\":\"presence\"")));
    }
}
