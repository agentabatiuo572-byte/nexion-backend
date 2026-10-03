package ffdd.opsconsole.onboarding.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface PhoneInstallationMapper {
    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockUser(@Param("userId") long userId);

    @Select("SELECT execution_installation_id FROM nx_phone_binding WHERE user_id=#{userId} "
            + "AND source_environment='PRODUCTION' AND run_id='' FOR UPDATE")
    String executionInstallation(@Param("userId") long userId);

    @Select("""
        SELECT d.device_type deviceType,b.installation_id installationId
          FROM nx_user_device d LEFT JOIN nx_phone_binding b
            ON b.user_id=d.user_id AND b.user_device_id=d.id AND b.source_environment='PRODUCTION' AND b.run_id=''
         WHERE d.user_id=#{userId} AND d.id=#{deviceId} AND d.is_deleted=0
           AND d.source_environment='PRODUCTION' AND d.run_id=''
        """)
    DeviceBinding deviceBinding(@Param("userId") long userId, @Param("deviceId") long deviceId);

    @Select("""
        SELECT user_device_id FROM nx_compute_task WHERE user_id=#{userId} AND task_no=#{taskNo}
          AND is_deleted=0 AND source_environment='PRODUCTION' LIMIT 1
        """)
    Long taskDevice(@Param("userId") long userId, @Param("taskNo") String taskNo);

    record DeviceBinding(String deviceType, String installationId) { }
}
