package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.ConversationCustomerProfile;
import ffdd.opsconsole.content.domain.CustomerProfileRepository;
import ffdd.opsconsole.content.mapper.CustomerNoteRow;
import ffdd.opsconsole.content.mapper.CustomerProfileMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link CustomerProfileRepository} 的 MyBatis 实现:注解 SQL 操作 nx_customer_tag / nx_customer_note。
 */
@Repository
@RequiredArgsConstructor
public class MybatisCustomerProfileRepository implements CustomerProfileRepository {
    private final CustomerProfileMapper mapper;

    @Override
    public List<String> findCustomTags(Long userId) {
        return mapper.findCustomTags(userId);
    }

    @Override
    public void addCustomTag(Long userId, String tag, String operator, LocalDateTime now) {
        mapper.addCustomTag(userId, tag, operator, now);
    }

    @Override
    public boolean removeCustomTag(Long userId, String tag) {
        return mapper.removeCustomTag(userId, tag) > 0;
    }

    @Override
    public List<ConversationCustomerProfile.CustomerNote> findNotes(Long userId) {
        return mapper.findNotes(userId);
    }

    @Override
    public ConversationCustomerProfile.CustomerNote addNote(Long userId, String author, String content, String operator, LocalDateTime now) {
        CustomerNoteRow row = new CustomerNoteRow();
        row.setUserId(userId);
        row.setAuthor(author);
        row.setContent(content);
        row.setOperator(operator);
        row.setNow(now);
        mapper.insertNote(row);  // useGeneratedKeys 回填 row.id
        long ts = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new ConversationCustomerProfile.CustomerNote(String.valueOf(row.getId()), ts, author, content);
    }

    @Override
    public boolean removeNote(Long noteId, String operator, LocalDateTime now) {
        return mapper.softDeleteNote(noteId, operator, now) > 0;
    }
}
