package ffdd.opsconsole.user.mapper;

import ffdd.opsconsole.user.domain.UserAccountActionListView;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserAccountActionMapper {
    @Select("SELECT COUNT(*) FROM nx_user WHERE is_deleted=0 AND status=#{status}")
    long countAccounts(@Param("status") String status);

    @Select("""
            SELECT u.id,
                   CONCAT('U', LPAD(u.id, GREATEST(8, LENGTH(CAST(u.id AS CHAR))), '0')) AS userNo,
                   u.nickname, u.status, u.created_at AS registeredAt,
                   u.c2_freeze_source AS freezeSource, u.c2_freeze_source_ref AS freezeSourceRef,
                   u.c2_freeze_reason AS freezeReason, u.c2_freeze_operator AS freezeOperator,
                   u.c2_frozen_at AS frozenAt,
                   (SELECT COUNT(*) FROM nx_withdrawal_order w
                     WHERE w.user_id=u.id AND w.is_deleted=0 AND w.status='FROZEN'
                       AND w.c2_frozen_by_user_status=1) AS d2FrozenWithdrawalCount
              FROM nx_user u
             WHERE u.is_deleted=0 AND u.status=#{status}
             ORDER BY u.c2_frozen_at DESC, u.id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<UserAccountActionListView> pageAccounts(
            @Param("status") String status, @Param("offset") int offset, @Param("limit") int limit);
}
