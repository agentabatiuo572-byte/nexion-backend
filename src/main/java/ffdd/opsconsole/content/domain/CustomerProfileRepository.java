package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 客户档案标注仓储(content 域):按 {@code user_id} 聚合的自定义标签 + 内部备注。
 *
 * <p>标签 / 备注归属客户(user_id),跨会话共享。由 {@code OpsConversationService} 在
 * {@code buildCustomerProfile} 时只读聚合,在 addCustomTag / removeCustomTag / addNote / removeNote 时写入。
 * 独立于 {@link ConversationRepository}(会话生命周期),守 SRP。</p>
 */
public interface CustomerProfileRepository {

    /** 读用户自定义标签(按 id 升序,稳定展示顺序)。 */
    List<String> findCustomTags(Long userId);

    /** 新增标签(UNIQUE(user_id, tag) 冲突时抛 DuplicateKeyException,由调用方 catch 做幂等)。 */
    void addCustomTag(Long userId, String tag, String operator, LocalDateTime now);

    /** 物理删除标签;返回是否命中(未命中表示标签不存在或已被并发删除)。 */
    boolean removeCustomTag(Long userId, String tag);

    /** 读用户备注(is_deleted=0,按 created_at 升序)。 */
    List<ConversationCustomerProfile.CustomerNote> findNotes(Long userId);

    /** 新增备注,返回带 id / ts 的回显对象(对齐前端 CustomerNote)。 */
    ConversationCustomerProfile.CustomerNote addNote(Long userId, String author, String content, String operator, LocalDateTime now);

    /** 软删除备注(is_deleted=1);返回是否命中。 */
    boolean removeNote(Long noteId, String operator, LocalDateTime now);
}
