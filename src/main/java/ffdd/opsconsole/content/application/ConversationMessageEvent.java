package ffdd.opsconsole.content.application;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 会话提交事件：App、坐席及系统写入在事务提交后发布。
 * WebSocket 将事件转换为授权会话的失效通知，客户端补拉各自可见的消息投影。
 * 旧 SSE 订阅端点仍可消费此事件以兼容历史客户端。
 *
 * <p>不继承 ApplicationEvent：Spring 4.2+ 的 ApplicationEventPublisher.publishEvent(Object)
 * 接受任意类型，plain POJO 更利于 Jackson 序列化进 SSE 数据帧（避免把 ApplicationEvent 的
 * source/timestamp 字段一起吐到前端）。
 *
 * <p>当前事件总线仅在单 Java 实例内生效；多实例部署需要接入共享事件总线。
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
    /** 发送方类型：AGENT / USER / SYSTEM。 */
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
