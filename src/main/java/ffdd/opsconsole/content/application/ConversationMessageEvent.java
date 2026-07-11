package ffdd.opsconsole.content.application;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 即时会话事件 —— 由 OpsConversationController 的写端点在调完 OpsConversationService 后发布，
 * OpsConversationStreamController 通过 @EventListener 接收并把变更通过 SSE 实时推给在线坐席。
 *
 * <p>不继承 ApplicationEvent：Spring 4.2+ 的 ApplicationEventPublisher.publishEvent(Object)
 * 接受任意类型，plain POJO 更利于 Jackson 序列化进 SSE 数据帧（避免把 ApplicationEvent 的
 * source/timestamp 字段一起吐到前端）。
 *
 * <p>跨进程说明：app 端用户主动发消息若落在外部进程，本事件无法跨进程感知；
 * 未来接入 RocketMQ（shared/rocketmq outbox）接收用户端消息事件后，再由消费侧补发本对象。
 * 当前实现仅覆盖 admin 后端闭环（坐席写操作触发 → SSE 推所有在线坐席）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessageEvent {

    /** 事件类型：消息 / 转交 / 状态变更 / 主动发起 / 客户回执。 */
    public enum EventType {
        MESSAGE,
        TRANSFER,
        STATUS,
        INITIATE,
        RECEIPT
    }

    /** 会话编号（与 ContentConversationView.conversationNo 同源）。 */
    private String conversationNo;
    /** 回执或消息明确指向的消息 id；避免并发新消息被误标已读。 */
    private Long messageId;
    /** 事件类型。 */
    private EventType eventType;
    /** 发送方类型：AGENT / USER / SYSTEM（USER 当前仅供未来跨进程接入预留）。 */
    private String senderType;
    /** 发送方名称（坐席名 / 系统标签）。 */
    private String senderName;
    /** 消息正文或状态摘要（前端按需合并）。 */
    private String body;
    /** 事件发生时间。 */
    private LocalDateTime ts;
    /** 会话归属坐席 id（前端按订阅过滤）。 */
    private String ownerAgentId;
    /** 会话归属坐席名。 */
    private String ownerAgentName;
}
