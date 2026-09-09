package ffdd.opsconsole.content.realtime;

import com.fasterxml.jackson.databind.*;
import ffdd.opsconsole.content.application.ConversationMessageEvent;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;

@Component
@RequiredArgsConstructor
public class ConversationSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper json;
    private final ConversationSocketTickets tickets;
    private final ConversationSocketAccess access;
    private final ConversationSocketCommands commands;
    private final Map<String,Client> clients=new ConcurrentHashMap<>();
    private final AtomicBoolean presencePending=new AtomicBoolean();
    private final ThreadPoolExecutor outbound=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2048),
            r->{Thread t=new Thread(r,"conversation-events");t.setDaemon(true);return t;});

    private static final class Client {
        final WebSocketSession socket;
        volatile ConversationSocketTickets.Grant grant;
        volatile Authentication auth;
        volatile String watching;
        volatile long seen=System.currentTimeMillis(), typingUntil=0;
        long window=seen; int frames;
        Client(WebSocketSession socket){this.socket=new ConcurrentWebSocketSessionDecorator(socket,5000,262144);}
    }
    @Override public synchronized void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if(clients.size()>=2000 || clients.values().stream().filter(c->c.auth==null).count()>=100
                || (session.getRemoteAddress()!=null && clients.values().stream().filter(c->c.auth==null && c.socket.getRemoteAddress()!=null
                    && c.socket.getRemoteAddress().getAddress().equals(session.getRemoteAddress().getAddress())).count()>=8)){
            session.close(CloseStatus.SERVICE_OVERLOAD);return;}
        session.setTextMessageSizeLimit(16384);
        Client client=new Client(session);clients.put(session.getId(),client);
        CompletableFuture.delayedExecutor(5,TimeUnit.SECONDS).execute(()->{
            if(client.auth==null && clients.get(session.getId())==client)close(client,4401);
        });
    }
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message) {
        Client c=clients.get(session.getId()); if(c==null)return;
        String requestId=null;
        try {
            long now=System.currentTimeMillis();
            if(now-c.window>10000){c.window=now;c.frames=0;}
            if(++c.frames>60)throw new BizException(429,"SOCKET_RATE_LIMIT");
            JsonNode frame=json.readTree(message.getPayload());
            String type=frame.path("type").asText();
            if(c.grant==null){
                if(!"auth".equals(type))throw new BizException(401,"SOCKET_AUTH_REQUIRED");
                c.grant=tickets.consume(frame.path("ticket").asText());
                c.auth=access.authenticate(c.grant.token(),c.grant.audience());
                c.seen=now;send(c,Map.of("type","ready")); requestPresence();return;
            }
            // A revoked login never survives through the lifetime of a socket.
            c.auth=access.authenticate(c.grant.token(),c.grant.audience()); c.seen=now;
            switch(type){
                case "ping" -> send(c,Map.of("type","pong"));
                case "watch" -> {
                    String no=frame.path("conversationNo").asText(null);
                    if(no!=null && !access.canRead(c.auth,c.grant.audience(),no))throw new BizException(404,"CONVERSATION_NOT_FOUND");
                    c.watching=no;c.typingUntil=0;requestPresence();
                }
                case "typing" -> {
                    String no=frame.path("conversationNo").asText();
                    if(!Objects.equals(c.watching,no) || !access.canRead(c.auth,c.grant.audience(),no))throw new BizException(404,"CONVERSATION_NOT_FOUND");
                    access.write(c.auth,c.grant.audience());
                    c.typingUntil=frame.path("active").asBoolean()?now+5000:0;requestPresence();
                }
                case "command" -> {
                    requestId=frame.path("requestId").asText();
                    if(!requestId.matches("[A-Za-z0-9_-]{1,100}"))throw new BizException(400,"INVALID_REQUEST_ID");
                    send(c,Map.of("type","ack","requestId",requestId,"result",commands.execute(c.auth,c.grant.audience(),frame)));
                }
                default -> throw new BizException(400,"UNKNOWN_SOCKET_FRAME");
            }
        } catch(Exception e){
            int code=e instanceof BizException b?b.getCode():e instanceof AccessDeniedException?403:
                    e instanceof org.springframework.security.core.AuthenticationException?401:500;
            String reason=e instanceof BizException?e.getMessage():"SOCKET_REQUEST_FAILED";
            if(requestId!=null)send(c,Map.of("type","ack","requestId",requestId,"result",ApiResult.fail(code,reason)));
            else send(c,Map.of("type","error","code",code));
            if(c.auth==null || code==401 || code==403 || code==429 || code==500)close(c,code==401||code==403?4401:1013);
        }
    }
    @EventListener public void changed(ConversationMessageEvent event){
        Runnable push=()->enqueue(()->{
            String eventId=UUID.randomUUID().toString();
            for(Client c:clients.values()){
                if(c.auth==null)continue;
                try{
                    c.auth=access.authenticate(c.grant.token(),c.grant.audience());
                    if(access.canRead(c.auth,c.grant.audience(),event.getConversationNo()))
                        // Only invalidations cross this boundary; each UI reads its authorized projection.
                        send(c,Map.of("type","event","eventId",eventId,"conversationNo",event.getConversationNo(),
                                "eventType","STATUS","senderType","SYSTEM"));
                }catch(Exception e){close(c,4401);}
            }
            requestPresence();
        });
        // Publishers already emit after commit. Registering another synchronization here
        // would lose events published from an existing afterCommit callback.
        push.run();
    }
    private boolean enqueue(Runnable work){
        try{outbound.execute(work);return true;}
        catch(RejectedExecutionException e){clients.values().forEach(c->close(c,1013));return false;}
    }
    @Scheduled(fixedDelay=10000) public void heartbeat(){
        long now=System.currentTimeMillis();
        for(Client c:clients.values()){
            if(now-c.seen>(c.auth==null?5000:60000)){close(c,1001);continue;}
            if(c.auth!=null)try{c.auth=access.authenticate(c.grant.token(),c.grant.audience());}catch(Exception e){close(c,4401);}
        }
        requestPresence();
    }
    private void requestPresence(){
        if(!presencePending.compareAndSet(false,true))return;
        CompletableFuture.delayedExecutor(100,TimeUnit.MILLISECONDS).execute(()->{
            if(!enqueue(()->{presencePending.set(false);broadcastPresence();}))presencePending.set(false);
        });
    }
    private void broadcastPresence(){
        long now=System.currentTimeMillis();
        List<Client> live=new ArrayList<>();
        Map<String,List<Client>> peers=new HashMap<>();
        // Index authenticated actors once, instead of looking up every viewer/peer pair in MySQL.
        for(Client c:clients.values()){
            if(c.auth==null || now-c.seen>=60000)continue;
            try{
                c.auth=access.authenticate(c.grant.token(),c.grant.audience());
                live.add(c);
                for(String key:access.presenceKeys(c.auth,c.grant.audience()))
                    peers.computeIfAbsent(key,k->new ArrayList<>()).add(c);
            }catch(Exception e){close(c,4401);}
        }
        Map<String,Optional<ConversationSocketAccess.Participants>> participants=new HashMap<>();
        for(Client viewer:live){
            String watching=viewer.watching;
            if(viewer.auth==null || watching==null)continue;
            try{
                var scope=participants.computeIfAbsent(watching,access::participants).orElse(null);
                if(scope==null || !scope.canRead(viewer.auth,viewer.grant.audience())){
                    if(Objects.equals(viewer.watching,watching))viewer.watching=null;
                    continue;
                }
                List<Client> matching=peers.getOrDefault(scope.peerKey(viewer.grant.audience()),List.of());
                boolean online=!matching.isEmpty();
                long typingRemaining=matching.stream()
                        .filter(peer->Objects.equals(peer.watching,watching))
                        .mapToLong(peer->Math.max(0,peer.typingUntil-now))
                        .max().orElse(0);
                boolean typing=typingRemaining>0;
                if(Objects.equals(viewer.watching,watching))
                    send(viewer,Map.of("type","presence","conversationNo",watching,"online",online,"typing",typing,
                            "expiresIn",Math.min(5000,typingRemaining)));
            }catch(Exception e){close(viewer,1013);}
        }
    }
    private void send(Client c,Object value){try{if(c.socket.isOpen())c.socket.sendMessage(new TextMessage(json.writeValueAsString(value)));}catch(Exception e){close(c,1013);}}
    private void close(Client c,int code){clients.remove(c.socket.getId());try{c.socket.close(new CloseStatus(code));}catch(Exception ignored){}}
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){clients.remove(session.getId());requestPresence();}
    @Override public void handleTransportError(WebSocketSession session,Throwable error){Client c=clients.get(session.getId());if(c!=null)close(c,1013);}
    @PreDestroy public void stop(){clients.values().forEach(c->close(c,1001));outbound.shutdownNow();}
}
