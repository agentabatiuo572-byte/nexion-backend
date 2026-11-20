package ffdd.opsconsole.content.realtime;

import com.fasterxml.jackson.databind.*;
import ffdd.opsconsole.content.application.ConversationMessageEvent;
import ffdd.opsconsole.platform.dto.PlatformConfigChangedEvent;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationSocketHandler extends TextWebSocketHandler implements SmartLifecycle {
    @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void userViewCleared(ffdd.opsconsole.content.application.AppConversationManagementService.UserViewCleared event) {
        publish(Map.of("kind", "CLEARED", "customerId", event.userId(), "conversationNos", event.conversationNos()));
    }
    @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void scopeChanged(ffdd.opsconsole.content.domain.SupportGroupFacts.ScopeChanged event) {
        publish(Map.of("kind", "SCOPE", "adminIds", event.affectedAdminIds(), "reason", event.reason()));
    }
    @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void assignmentChanged(ffdd.opsconsole.content.application.SupportBindingService.SupportAssignmentChanged event) {
        Set<Long> advisors = new HashSet<>();
        if (event.current() != null) advisors.add(event.current().agentAdminId());
        if (event.previous() != null) advisors.add(event.previous().agentAdminId());
        publish(Map.of("kind", "ASSIGNMENT", "customerId", event.customerId(), "adminIds", advisors));
    }
    private final ObjectMapper json;
    private final ConversationSocketTickets tickets;
    private final ConversationSocketAccess access;
    private final ConversationSocketCommands commands;
    private final ConversationSocketRedis socketRedis;
    private final ApplicationEventPublisher events;
    private final Map<String,Client> clients=new ConcurrentHashMap<>();
    private final Map<String, Set<Client>> users = new ConcurrentHashMap<>();
    private final Map<String, Set<Client>> admins = new ConcurrentHashMap<>();
    private final Map<String, Set<Client>> watchers = new ConcurrentHashMap<>();
    private final Map<String, Set<Client>> peerWatchers = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private final Map<InetAddress, Integer> pendingByIp = new HashMap<>();
    private final ScheduledThreadPoolExecutor authTimeouts = authTimeoutExecutor();
    private int pendingConnections;
    private int maxConnections;
    private final AtomicBoolean presencePending=new AtomicBoolean();
    private final AtomicBoolean overloadPending = new AtomicBoolean();
    private final CompletableFuture<Void> outboundTerminated = new CompletableFuture<>();
    private final ThreadPoolExecutor outbound=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2048),
            r->{Thread t=new Thread(r,"conversation-events");t.setDaemon(true);return t;}) {
        @Override protected void terminated() { outboundTerminated.complete(null); }
    };

    private record Identity(ConversationSocketTickets.Grant grant, Authentication auth) {}
    private static final class Client {
        final WebSocketSession socket;
        final InetAddress remoteIp;
        ScheduledFuture<?> authTimeout;
        volatile Identity identity;
        volatile String watching;
        String watchedPeer;
        volatile long seen=System.currentTimeMillis(), typingUntil=0;
        boolean online;
        long window=seen; int frames;
        Client(WebSocketSession socket) {
            var remote = socket.getRemoteAddress();
            this.remoteIp = remote == null ? null : remote.getAddress();
            this.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, 262144);
        }
    }

    @Value("${nexion.websocket.max-connections:2000}")
    void configureMaxConnections(int limit) {
        if (limit < 1) throw new IllegalArgumentException("nexion.websocket.max-connections must be positive");
        maxConnections = limit;
    }

    private static ScheduledThreadPoolExecutor authTimeoutExecutor() {
        var scheduler = new ScheduledThreadPoolExecutor(1, work -> {
            Thread thread = new Thread(work, "conversation-auth-timeouts");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.setTextMessageSizeLimit(16384);
        Client client = new Client(session);
        synchronized (admissionLock) {
            if (!authTimeouts.isShutdown() && clients.size() < maxConnections && pendingConnections < 100
                    && pendingByIp.getOrDefault(client.remoteIp, 0) < 8) {
                clients.put(session.getId(), client);
                pendingConnections++;
                if (client.remoteIp != null) pendingByIp.merge(client.remoteIp, 1, Integer::sum);
                client.authTimeout = authTimeouts.schedule(() -> {
                    if (detach(client, true)) closeTransport(client, 4401);
                }, 5, TimeUnit.SECONDS);
                return;
            }
        }
        session.close(CloseStatus.SERVICE_OVERLOAD);
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
            String audience;
            Authentication auth;
            synchronized(c) {
                if(clients.get(session.getId())!=c)return;
                Identity current=c.identity;
                if(current==null){
                    if(!"auth".equals(type))throw new BizException(401,"SOCKET_AUTH_REQUIRED");
                    if (!socketRedis.isAvailable()) throw new BizException(503, "SOCKET_REALTIME_UNAVAILABLE");
                    var grant=tickets.consume(frame.path("ticket").asText());
                    var identity = new Identity(grant, access.revalidate(grant.token(), grant.audience()));
                    synchronized (admissionLock) {
                        if (clients.get(session.getId()) != c) return;
                        c.identity = identity;
                        c.seen = now;
                        releasePending(c);
                        registerClient(c);
                    }
                    send(c,"USER".equals(grant.audience())?Map.of("type","ready","reauth",true):Map.of("type","ready"));
                    refreshPresence(c, identity.auth);return;
                }
                audience=current.grant.audience();
                if("reauth".equals(type)) {
                    var next=tickets.consume(frame.path("ticket").asText());
                    if(!current.grant.actor().equals(next.actor()))throw new BizException(401,"SOCKET_REAUTH_REJECTED");
                    c.identity=new Identity(next,access.reauthenticate(current.auth,next.token(),audience));
                    c.seen=now;send(c,Map.of("type","reauth-ready"));return;
                }
                // Real commands retain the active-session write and its existing locks.
                auth="command".equals(type)?access.authenticate(current.grant.token(),audience)
                        :access.revalidate(current.grant.token(),audience);
                c.seen=now;
            }
            switch(type){
                case "ping" -> {
                    send(c, Map.of("type", "pong"));
                    refreshPresence(c, auth);
                }
                case "watch" -> {
                    String no=frame.path("conversationNo").asText(null);
                    if(no!=null && !access.canRead(auth,audience,no))throw new BizException(404,"CONVERSATION_NOT_FOUND");
                    setWatch(c, no);
                    if (no != null) enqueue(() -> broadcastPresence(no));
                }
                case "typing" -> {
                    String no=frame.path("conversationNo").asText();
                    synchronized (c) {
                        if (clients.get(session.getId()) != c) return;
                        if(!Objects.equals(c.watching,no) || !access.canWrite(auth,audience,no))throw new BizException(404,"CONVERSATION_NOT_FOUND");
                        access.write(auth,audience);
                        if (frame.path("active").asBoolean()) {
                            socketRedis.touchTyping(c.identity.grant.actor(), no, session.getId(), now);
                            c.typingUntil = now + 5000;
                        } else {
                            socketRedis.clearTyping(c.identity.grant.actor(), no, session.getId());
                            c.typingUntil = 0;
                        }
                    }
                    publish(Map.of("kind", "PRESENCE", "conversationNo", no));
                }
                case "command" -> {
                    requestId=frame.path("requestId").asText();
                    if(!requestId.matches("[A-Za-z0-9_-]{1,100}"))throw new BizException(400,"INVALID_REQUEST_ID");
                    send(c,Map.of("type","ack","requestId",requestId,"result",commands.execute(auth,audience,frame)));
                }
                default -> throw new BizException(400,"UNKNOWN_SOCKET_FRAME");
            }
        } catch(Exception e){
            int code=e instanceof BizException b?b.getCode():e instanceof AccessDeniedException?403:
                    e instanceof AuthenticationException?401:500;
            String reason=e instanceof BizException?e.getMessage():"SOCKET_REQUEST_FAILED";
            if(requestId!=null)send(c,Map.of("type","ack","requestId",requestId,"result",
                    e instanceof ffdd.opsconsole.content.application.SupportSessionService.Busy busy
                            ? ApiResult.fail(code,reason,busy.state()) : ApiResult.fail(code,reason)));
            else send(c,Map.of("type","error","code",code));
            if(c.identity==null || code==401 || code==403 || code==429 || code==500 || code==503)close(c,code==401||code==403?4401:1013);
        }
    }
    @EventListener public void changed(ConversationMessageEvent event){
        // Publishers already emit after commit, including from other afterCommit callbacks.
        if (event.getConversationNo() != null) publish(Map.of("kind", "MESSAGE", "conversationNo", event.getConversationNo()));
    }

    @EventListener public void platformConfigChanged(PlatformConfigChangedEvent event) {
        if (!event.replicated()) publish(Map.of("kind", "PLATFORM", "source", socketRedis.instance()));
    }

    private void publish(Map<String,?> notice) {
        if (!enqueue(() -> socketRedis.publish(notice)) && !outbound.isShutdown()) socketRedis.markGap();
    }

    @EventListener void received(ConversationSocketRedis.Delivery delivery) {
        enqueue(() -> {
            try { deliver(delivery); }
            catch (RuntimeException ex) { resetConnections(); }
        });
    }

    @EventListener void deliveryLost(ConversationSocketRedis.DeliveryLost lost) {
        enqueue(this::resetConnections);
    }

    private void resetConnections() {
        events.publishEvent(new PlatformConfigChangedEvent(true));
        clients.values().forEach(c -> close(c, 1013));
    }

    private void deliver(ConversationSocketRedis.Delivery delivery) {
        JsonNode notice = delivery.notice();
        String no = notice.path("conversationNo").asText();
        String customer = notice.path("customerId").asText();
        switch (notice.path("kind").asText()) {
            case "MESSAGE" -> {
                if (users.isEmpty() && admins.isEmpty()) return;
                var frame = statusFrame(delivery.id(), no);
                access.customerId(no).ifPresent(id -> {
                    sendTo(users.getOrDefault(id.toString(), Set.of()), frame);
                    admins.forEach((actor, sockets) -> {
                        if (access.canReadCustomer(actor, id)) sendTo(sockets, frame);
                    });
                });
                broadcastPresence(no);
            }
            case "CLEARED" -> {
                for (Client c : users.getOrDefault(customer, Set.of())) {
                    if (revalidate(c) != null) for (JsonNode entry : notice.path("conversationNos")) {
                        send(c, statusFrame(delivery.id() + ":" + entry.asText(), entry.asText()));
                    }
                }
            }
            case "ASSIGNMENT" -> {
                var frame = Map.of("type", "scope-invalidated", "reason", "ASSIGNMENT_CHANGED",
                        "customerId", notice.path("customerId").longValue());
                var sockets = new HashSet<>(users.getOrDefault(customer, Set.of()));
                for (JsonNode id : notice.path("adminIds")) sockets.addAll(admins.getOrDefault(id.asText(), Set.of()));
                for (Client c : sockets) synchronized (c) {
                    if (revalidate(c) == null) continue;
                    if (c.watching != null && access.customerId(c.watching).map(Object::toString).filter(customer::equals).isPresent()) {
                        setWatch(c, null);
                    }
                    send(c, frame);
                }
                requestPresence();
            }
            case "SCOPE" -> {
                var frame = Map.of("type", "scope-invalidated", "reason", notice.path("reason").asText());
                for (JsonNode id : notice.path("adminIds")) for (Client c : admins.getOrDefault(id.asText(), Set.of())) {
                    synchronized (c) {
                        Authentication auth = revalidate(c);
                        if (auth == null) continue;
                        if (c.watching != null && !access.canRead(auth, "ADMIN", c.watching)) setWatch(c, null);
                        send(c, frame);
                        refreshPresence(c, auth);
                    }
                }
                requestPresence();
            }
            case "PLATFORM" -> {
                if (!socketRedis.instance().equals(notice.path("source").asText())) {
                    events.publishEvent(new PlatformConfigChangedEvent(true));
                }
                var frame = Map.of("type", "event", "eventId", delivery.id(),
                        "eventType", "PLATFORM_CONFIG_CHANGED", "senderType", "SYSTEM");
                users.values().forEach(sockets -> sendTo(sockets, frame));
            }
            case "PRESENCE" -> {
                if (!no.isEmpty()) broadcastPresence(no);
                else peerWatchers.getOrDefault(notice.path("actor").asText(), Set.of()).stream()
                        .map(c -> c.watching).filter(Objects::nonNull).distinct().forEach(this::broadcastPresence);
            }
            case "RESYNC" -> { if (!socketRedis.instance().equals(notice.path("source").asText())) resetConnections(); }
            case "START" -> { }
            default -> throw new IllegalStateException("Unknown conversation realtime notice");
        }
    }

    private Map<String,String> statusFrame(String id, String no) {
        return Map.of("type", "event", "eventId", id, "conversationNo", no,
                "eventType", "STATUS", "senderType", "SYSTEM");
    }

    private void sendTo(Set<Client> sockets, Map<String,String> frame) {
        for (Client c : sockets) if (revalidate(c) != null) send(c, frame);
    }

    private Authentication revalidate(Client c) {
        // A background check must not close a socket based on a grant already replaced by reauth.
        synchronized(c) {
            Identity identity=c.identity;
            if(identity==null || clients.get(c.socket.getId())!=c)return null;
            try {
                return access.revalidate(identity.grant.token(),identity.grant.audience());
            } catch(BizException | AuthenticationException e) {close(c,4401);}
            catch(RuntimeException e) {close(c,1013);}
            return null;
        }
    }
    private boolean enqueue(Runnable work){
        try{outbound.execute(work);return true;}
        catch(RejectedExecutionException e){
            if (!outbound.isShutdown() && overloadPending.compareAndSet(false, true)) {
                log.warn("Conversation realtime queue overloaded queueSize={} clients={}", outbound.getQueue().size(), clients.size());
                CompletableFuture.runAsync(() -> {
                    try { resetConnections(); }
                    finally { overloadPending.set(false); }
                });
            }
            return false;
        }
    }
    @Scheduled(fixedDelay=10000) public void heartbeat(){
        if (!socketRedis.isAvailable()) {
            resetConnections();
            return;
        }
        long now=System.currentTimeMillis();
        for(Client c:clients.values()){
            if (c.identity == null) continue;
            if(now-c.seen>60000){close(c,1001);continue;}
            revalidate(c);
        }
        requestPresence();
    }
    private void requestPresence(){
        if(!presencePending.compareAndSet(false,true))return;
        CompletableFuture.delayedExecutor(100,TimeUnit.MILLISECONDS).execute(()->{
            if(!enqueue(()->{presencePending.set(false);watchers.keySet().forEach(this::broadcastPresence);}))presencePending.set(false);
        });
    }

    private void broadcastPresence(String no) {
        Set<Client> sockets = watchers.get(no);
        if (sockets == null || sockets.isEmpty()) return;
        var scope = access.participants(no).orElse(null);
        Map<String, ConversationSocketRedis.Presence> states = new HashMap<>();
        for (Client c : sockets) synchronized (c) {
            if (!no.equals(c.watching)) continue;
            Authentication auth = revalidate(c);
            if (auth == null) continue;
            String audience = c.identity.grant.audience();
            try {
                if (scope == null || !scope.canRead(auth, audience)) {
                    setWatch(c, null);
                    continue;
                }
                String peer = scope.peerKey(audience);
                if (!peer.equals(c.watchedPeer)) {
                    clearWatchedPeer(c);
                    c.watchedPeer = peer;
                    index(peerWatchers, peer, c);
                }
                var state = states.computeIfAbsent(peer,
                        actor -> socketRedis.presence(actor, no, System.currentTimeMillis()));
                send(c, Map.of("type", "presence", "conversationNo", no, "online", state.online(),
                        "typing", state.typingMillis() > 0, "expiresIn", state.typingMillis()));
            } catch (RuntimeException ex) { close(c, 1013); }
        }
    }

    private void refreshPresence(Client c, Authentication auth) {
        synchronized (c) {
            if (clients.get(c.socket.getId()) != c) return;
            try {
                var grant = c.identity.grant;
                boolean online = access.contributesPresence(auth, grant.audience());
                if (online) socketRedis.touchOnline(grant.actor(), c.socket.getId(), System.currentTimeMillis());
                else if (c.online) socketRedis.clearOnline(grant.actor(), c.socket.getId());
                if (c.online != online) {
                    c.online = online;
                    socketRedis.publish(Map.of("kind", "PRESENCE", "actor", grant.actor()));
                }
            } catch (RuntimeException ex) { close(c, 1013); }
        }
    }

    private void registerClient(Client c) {
        index(actorIndex(c), c.identity.auth.getName(), c);
    }

    private void removeClient(Client c) {
        String no;
        boolean online;
        boolean typing;
        synchronized (c) {
            unindex(actorIndex(c), c.identity.auth.getName(), c);
            no = c.watching;
            if (no != null) unindex(watchers, no, c);
            clearWatchedPeer(c);
            online = c.online;
            typing = no != null && c.typingUntil > System.currentTimeMillis();
            c.watching = null;
        }
        if (!online && !typing) return;
        try {
            outbound.execute(() -> {
                String actor = c.identity.grant.actor();
                String socketId = c.socket.getId();
                try {
                    if (online) socketRedis.clearOnline(actor, socketId);
                    if (typing) socketRedis.clearTyping(actor, no, socketId);
                    socketRedis.publish(online ? Map.of("kind", "PRESENCE", "actor", actor)
                            : Map.of("kind", "PRESENCE", "conversationNo", no));
                } catch (RuntimeException ex) {
                    // The shared leases expire even when Redis is unavailable during close.
                }
            });
        } catch (RejectedExecutionException ex) {
            // A closed or overloaded executor likewise leaves only expiring leases.
        }
    }

    private Map<String, Set<Client>> actorIndex(Client c) {
        return "USER".equals(c.identity.grant.audience()) ? users : admins;
    }

    private void setWatch(Client c, String no) {
        synchronized (c) {
            if (clients.get(c.socket.getId()) != c) return;
            String previous = c.watching;
            if (previous != null && c.typingUntil > System.currentTimeMillis()) {
                socketRedis.clearTyping(c.identity.grant.actor(), previous, c.socket.getId());
                publish(Map.of("kind", "PRESENCE", "conversationNo", previous));
            }
            c.typingUntil = 0;
            if (Objects.equals(previous, no)) return;
            if (previous != null) unindex(watchers, previous, c);
            clearWatchedPeer(c);
            c.watching = no;
            if (no != null) index(watchers, no, c);
        }
    }

    private void clearWatchedPeer(Client c) {
        if (c.watchedPeer == null) return;
        unindex(peerWatchers, c.watchedPeer, c);
        c.watchedPeer = null;
    }

    private void index(Map<String, Set<Client>> index, String key, Client c) {
        index.compute(key, (ignored, sockets) -> {
            Set<Client> bucket = sockets == null ? ConcurrentHashMap.newKeySet() : sockets;
            bucket.add(c);
            return bucket;
        });
    }

    private void unindex(Map<String, Set<Client>> index, String key, Client c) {
        index.computeIfPresent(key, (ignored, sockets) -> {
            sockets.remove(c);
            return sockets.isEmpty() ? null : sockets;
        });
    }
    private void send(Client c,Object value){try{if(c.socket.isOpen())c.socket.sendMessage(new TextMessage(json.writeValueAsString(value)));}catch(Exception e){close(c,1013);}}
    private void releasePending(Client client) {
        client.authTimeout.cancel(false);
        pendingConnections--;
        if (client.remoteIp != null) {
            pendingByIp.computeIfPresent(client.remoteIp, (ip, count) -> count == 1 ? null : count - 1);
        }
    }

    private boolean detach(Client client, boolean pendingOnly) {
        boolean pending;
        synchronized (admissionLock) {
            pending = client.identity == null;
            if ((pendingOnly && !pending) || !clients.remove(client.socket.getId(), client)) return false;
            if (pending) releasePending(client);
        }
        if (!pending) removeClient(client);
        return true;
    }

    private void close(Client client, int code) {
        if (detach(client, false)) closeTransport(client, code);
    }

    private void closeTransport(Client client, int code) {
        try { client.socket.close(new CloseStatus(code)); } catch (Exception ignored) {}
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Client client = clients.get(session.getId());
        if (client != null) detach(client, false);
    }
    @Override public void handleTransportError(WebSocketSession session,Throwable error){Client c=clients.get(session.getId());if(c!=null)close(c,1013);}
    @Override public void start() { }
    @Override public boolean isRunning() { return !outbound.isTerminated(); }

    @EventListener(ContextClosedEvent.class)
    public void contextClosed() { stop(); }

    @Override public void stop(Runnable callback) {
        stop();
        // The highest lifecycle phase waits for the active task before phase-0 Redis and SQL destruction.
        outboundTerminated.thenRun(callback);
    }

    @PreDestroy @Override public void stop() {
        synchronized (admissionLock) {
            authTimeouts.shutdownNow();
            // Notifications resynchronise and presence leases expire. Discard the backlog, but
            // never interrupt the one task that may still be using Redis/SQL.
            outbound.shutdown();
            outbound.getQueue().clear();
        }
        clients.values().forEach(c -> close(c, 1001));
    }
}
