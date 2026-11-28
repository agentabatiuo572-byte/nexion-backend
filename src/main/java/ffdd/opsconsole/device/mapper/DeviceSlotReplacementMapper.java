package ffdd.opsconsole.device.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DeviceSlotReplacementMapper {
    record Pending(long oldDeviceId, long newDeviceId, String oldDeviceName, String newDeviceName) {}

    @Select("""
            SELECT r.old_device_id AS oldDeviceId,r.new_device_id AS newDeviceId,
                   d_old.name AS oldDeviceName,d_new.name AS newDeviceName
              FROM nx_device_slot_replacement r
              JOIN nx_user_device d_old ON d_old.id=r.old_device_id AND d_old.user_id=r.user_id
              JOIN nx_user_device d_new ON d_new.id=r.new_device_id AND d_new.user_id=r.user_id
             WHERE r.user_id=#{userId} AND d_old.pending_deactivate=1
               AND d_old.ownership_status='OWNED' AND d_new.ownership_status='OWNED'
               AND d_old.is_deleted=0 AND d_new.is_deleted=0
            """)
    List<Pending> pending(long userId);

    @Select("""
            SELECT new_device_id FROM nx_device_slot_replacement
             WHERE user_id=#{userId} AND old_device_id=#{oldDeviceId}
             FOR UPDATE
            """)
    Long successor(long userId, long oldDeviceId);

    @Select("""
            SELECT old_device_id FROM nx_device_slot_replacement
             WHERE user_id=#{userId} AND new_device_id=#{newDeviceId}
             FOR UPDATE
            """)
    Long predecessor(long userId, long newDeviceId);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM nx_device_slot_replacement
                           WHERE user_id=#{userId} AND new_device_id=#{newDeviceId})
            """)
    boolean isWaiting(long userId, long newDeviceId);

    @Insert("""
            INSERT INTO nx_device_slot_replacement(user_id,old_device_id,new_device_id)
            VALUES(#{userId},#{oldDeviceId},#{newDeviceId})
            """)
    int bind(long userId, long oldDeviceId, long newDeviceId);

    @Delete("""
            DELETE FROM nx_device_slot_replacement
             WHERE user_id=#{userId} AND old_device_id=#{oldDeviceId}
            """)
    int unbind(long userId, long oldDeviceId);

    @Update("""
            UPDATE nx_user_device SET pending_deactivate=0,row_version=row_version+1,updated_at=NOW(6)
             WHERE user_id=#{userId} AND id=#{oldDeviceId} AND row_version=#{version}
               AND pending_deactivate=1
            """)
    int cancelDeactivation(long userId, long oldDeviceId, long version);
}
