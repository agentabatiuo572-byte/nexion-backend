package ffdd.opsconsole.content.mapper;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * nx_customer_note 插入参数持有对象(mapper 内部用)。
 *
 * <p>{@code @Options(useGeneratedKeys=true, keyProperty="id")} 在 insert 后回填 id,
 * 由 {@code MybatisCustomerProfileRepository} 据此重建领域 {@code CustomerNote}。
 * 刻意为可变类(record 无法回填 key),仅限 infrastructure 范围。</p>
 */
@Data
public class CustomerNoteRow {
    private Long id;
    private Long userId;
    private String author;
    private String content;
    private String operator;
    private LocalDateTime now;
}
