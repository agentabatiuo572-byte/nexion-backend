package ffdd.opsconsole.shared.canonical.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;

/**
 * Typed MyBatis-Plus anchor for canonical user-scoped transactions.
 * CanonicalStateMapper uses explicit SQL for cross-table reads and writes, but
 * its inherited CRUD surface remains safely bound to nx_user instead of Object.
 */
@TableName("nx_user")
public class CanonicalUserEntity extends BaseEntity {
}
