package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Projects only server-owned facts; notification text never includes the event's internal reason. */
public interface BusinessNotificationMapper extends BaseMapper<Object> {
    @Select("SELECT user_id FROM nx_withdrawal_order WHERE withdrawal_no=#{id} AND is_deleted=0")
    Long withdrawalOwner(String id);

    @Select("SELECT user_id FROM nx_order WHERE order_no=#{id} AND is_deleted=0")
    Long orderOwner(String id);

    @Select("SELECT user_id FROM nx_user_device WHERE instance_no=#{id} AND is_deleted=0")
    Long deviceOwner(String id);

    @Select("SELECT language FROM nx_user WHERE id=#{userId} AND is_deleted=0")
    String language(Long userId);

    @Insert("""
            INSERT INTO nx_notification
              (biz_no,user_id,type,priority,title,body,cta_label,cta_href,read_flag,
               push_status,push_attempts,pushed_at,created_at,updated_at,is_deleted)
            SELECT #{bizNo},id,#{kind},#{priority},#{title},#{body},#{label},#{href},0,
                   'DELIVERED',0,NOW(),NOW(),NOW(),0
              FROM nx_user WHERE id=#{userId} AND is_deleted=0
            ON DUPLICATE KEY UPDATE biz_no=VALUES(biz_no)
            """)
    int deliver(@Param("bizNo") String bizNo, @Param("userId") Long userId,
                @Param("kind") String kind, @Param("priority") String priority,
                @Param("title") String title, @Param("body") String body,
                @Param("label") String label, @Param("href") String href);
}
