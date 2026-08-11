package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.ConversationMessageEvent;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 即时会话 SSE 推送控制器 + 服务内事件总线订阅端。
 *
 * <p>端点：GET /api/admin/content/conversations/stream —— 返回 SseEmitter（超时 30 分钟）。
 * 鉴权：JwtAuthenticationFilter 已在 SecurityContext 中放入坐席身份——
 *   1) 同源 cookie 走 Next route（app/api/admin/content/[...path]/route.ts）转 Authorization: Bearer；
 *   2) 浏览器只经同源 BFF 与 HttpOnly 会话接入；JWT 不进入 URL、查询参数或日志。
 * 两种方式都让本控制器能从 SecurityContext 拿到当前 adminId（JWT subject）作为订阅 key。
 *
 * <p>线程安全：registry 用 ConcurrentHashMap + CopyOnWriteArrayList；emitter 在 onCompletion /
 * onTimeout / onError 任一路径都从 registry 移除并取消心跳任务，防泄漏。
 *
 * <p>推送策略：onConversationMessage 收到 ConversationMessageEvent 后推给所有在线坐席 emitter
 * （管理台：任一坐席的会话台都需感知变化以避免重复处置；事件自带 conversationNo/ownerAgentId 供客户端过滤）。
 */
@Slf4j
@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/conversations")
public class OpsConversationStreamController {

    /** SSE 连接超时：30 分钟（对齐长连接诉求；到期后客户端自动重连）。 */
    private static final long SSE_TIMEOUT_MS = 30L * 60L * 1000L;
    /** 心跳间隔：25 秒（小于常见反向代理 60s 空闲断链阈值）。 */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 25L;

    /** 坐席 adminId → 该坐席持有的所有 SSE emitter（同一坐席多标签页会建立多条）。 */
    private final Map<String, List<EmitterBinding>> registry = new ConcurrentHashMap<>();

    /** 心跳调度器：所有连接共用单线程（每条连接的任务自管 cancel）。 */
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ops-sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /**
     * SSE 订阅端点。建立连接后立即返回 SseEmitter，后续事件由 @EventListener 异步推入。
     * 单条连接失败（IOException / IllegalStateException）→ 静默从 registry 摘除，等客户端重连。
     */
    // SSE 会话流订阅 — M3 即时会话台 读（坐席被动接收推送/收件箱）
    @PreAuthorize("hasAuthority('service_m3_read')")
    @GetMapping("/stream")
    public SseEmitter stream() {
        String adminId = resolveAdminId();
        SseEmitter emitter = createEmitter(SSE_TIMEOUT_MS);
        EmitterBinding binding = new EmitterBinding(emitter, adminId);
        register(adminId, binding);

        // 生命周期回调：任一结束路径都从 registry 移除，防内存泄漏。
        emitter.onCompletion(() -> unregister(adminId, binding));
        emitter.onTimeout(() -> {
            // SseEmitter 超时后 Spring 会自动 complete；这里显式触发再清理一次，幂等。
            emitter.complete();
            unregister(adminId, binding);
        });
        emitter.onError(ex -> unregister(adminId, binding));

        // 立即刷出一个合法 SSE 注释帧，避免 EventSource 要等首个 25 秒心跳才收到 200/onopen。
        // 首帧失败说明连接已不可用：不登记心跳，摘除注册表并以错误完成，等浏览器按 SSE 语义重连。
        try {
            emitter.send(SseEmitter.event().comment("ready"));
        } catch (IOException | IllegalStateException ex) {
            unregister(adminId, binding);
            emitter.completeWithError(ex);
            return emitter;
        }

        // 心跳：定期发 SSE 注释帧（:ping\n\n），防中间代理因空闲断链。
        // 注释帧不会触发前端 onmessage，仅保活。
        binding.heartbeat = heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException ex) {
                unregister(adminId, binding);
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        log.debug("SSE stream connected: adminId={} activeEmitters={}", adminId, totalEmitters());
        return emitter;
    }

    /** 测试替身入口；生产路径始终创建 Spring 标准 SseEmitter。 */
    protected SseEmitter createEmitter(long timeoutMs) {
        return new SseEmitter(timeoutMs);
    }

    /**
     * Lightweight same-authority probe used after EventSource.onerror. Native
     * EventSource hides the HTTP status, so the BFF calls this endpoint to make
     * 401/403 terminal instead of retrying an authorization failure forever.
     */
    @PreAuthorize("hasAuthority('service_m3_read')")
    @GetMapping("/stream/status")
    public ResponseEntity<Void> status() {
        return ResponseEntity.noContent().build();
    }

    /**
     * 监听 ConversationMessageEvent → 推给所有在线坐席 emitter。
     * 同步执行（publishEvent 在 OpsConversationController 写线程内调用）；
     * 单条 emitter.send 失败不影响其它连接，整体耗时在毫秒级。
     */
    @EventListener
    public void onConversationMessage(ConversationMessageEvent event) {
        if (event == null || event.getConversationNo() == null) {
            return;
        }
        // 兜底 ts，避免前端解析空字段。
        if (event.getTs() == null) {
            event.setTs(LocalDateTime.now());
        }
        registry.forEach((adminId, bindings) -> {
            for (EmitterBinding binding : bindings) {
                try {
                    binding.emitter.send(SseEmitter.event()
                            .name("message")
                            .data(event));
                } catch (IOException | IllegalStateException ex) {
                    // 该连接已断 —— 摘除，等客户端重连。
                    unregister(adminId, binding);
                }
            }
        });
    }

    /** 从 SecurityContext 取 adminId（JWT subject）。未鉴权时返回 "anonymous"，但 RBAC 过滤器会先挡 401。 */
    private String resolveAdminId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null) {
            return String.valueOf(auth.getPrincipal());
        }
        return "anonymous";
    }

    private void register(String adminId, EmitterBinding binding) {
        registry.computeIfAbsent(adminId, key -> new CopyOnWriteArrayList<>()).add(binding);
    }

    private void unregister(String adminId, EmitterBinding binding) {
        List<EmitterBinding> bindings = registry.get(adminId);
        if (bindings == null) {
            return;
        }
        bindings.remove(binding);
        if (binding.heartbeat != null) {
            binding.heartbeat.cancel(false);
        }
        if (bindings.isEmpty()) {
            registry.remove(adminId);
        }
    }

    private int totalEmitters() {
        return registry.values().stream().mapToInt(List::size).sum();
    }

    /** 仅用于同包定向测试，避免通过反射读取连接注册表。 */
    int activeEmitterCount() {
        return totalEmitters();
    }

    @PreDestroy
    public void shutdown() {
        heartbeat.shutdownNow();
        registry.values().forEach(list -> list.forEach(b -> {
            try {
                b.emitter.complete();
            } catch (Exception ignored) {
                // 关闭阶段忽略：单条 emitter 状态异常不影响整体关闭。
            }
        }));
        registry.clear();
    }

    /** 单条 SSE 连接 + 其心跳任务的绑定。 */
    private static final class EmitterBinding {
        final SseEmitter emitter;
        final String adminId;
        volatile ScheduledFuture<?> heartbeat;

        EmitterBinding(SseEmitter emitter, String adminId) {
            this.emitter = emitter;
            this.adminId = adminId;
        }
    }
}
