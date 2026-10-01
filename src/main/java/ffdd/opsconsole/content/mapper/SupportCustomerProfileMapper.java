package ffdd.opsconsole.content.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Select;

/** Only service-visible identity and counts; no session, risk model or account security fields. */
public interface SupportCustomerProfileMapper {
    @Select("SELECT COUNT(*) total,COALESCE(SUM(CASE WHEN "+ffdd.opsconsole.device.mapper.DeviceOpsMapper.E5_ACTIVATED_OWNED+
        " AND "+ffdd.opsconsole.device.mapper.DeviceOpsMapper.E5_RUNTIME_ONLINE+" THEN 1 ELSE 0 END),0) observedOnlineCount,"+
        "COALESCE(SUM(CASE WHEN "+ffdd.opsconsole.device.mapper.DeviceOpsMapper.E5_ACTIVATED_OWNED+
        " AND "+ffdd.opsconsole.device.mapper.DeviceOpsMapper.E5_RUNTIME_UNKNOWN+" THEN 1 ELSE 0 END),0) telemetryUnknownCount "+
        "FROM nx_user_device d LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0 WHERE d.user_id=#{customer} AND d.is_deleted=0")
    Map<String,Object> deviceTotals(Long customer);
    @Select("SELECT coverage_start_at coverageStartAt,observed_through_at observedThroughAt FROM nx_support_activity_coverage WHERE id=1")
    ffdd.opsconsole.content.application.SupportActivityService.Coverage coverage();
    @Select("SELECT avatar_url FROM nx_user WHERE id=#{customer} AND is_deleted=0")
    String avatar(Long customer);
    @Select("""
      SELECT (SELECT COUNT(*) FROM nx_conversation c WHERE c.user_id=#{customer} AND c.is_deleted=0) conversationCount,
       (SELECT COUNT(*) FROM nx_support_ticket t WHERE t.user_id=#{customer} AND t.is_deleted=0) ticketCount,
       (SELECT MAX(CONVERT_TZ(m.created_at,'+08:00','+00:00')) FROM nx_conversation_message m JOIN nx_conversation c ON c.conversation_no=m.conversation_no
         WHERE c.user_id=#{customer} AND c.is_deleted=0 AND m.is_deleted=0 AND m.sender_type='agent') lastServiceAt,
       (SELECT c.conversation_no FROM nx_conversation c WHERE c.user_id=#{customer} AND c.is_deleted=0 ORDER BY c.updated_at DESC,c.id DESC LIMIT 1) conversationNo,
       (SELECT c.status FROM nx_conversation c WHERE c.user_id=#{customer} AND c.is_deleted=0 ORDER BY c.updated_at DESC,c.id DESC LIMIT 1) conversationStatus,
       (SELECT c.archived FROM nx_conversation c WHERE c.user_id=#{customer} AND c.is_deleted=0 ORDER BY c.updated_at DESC,c.id DESC LIMIT 1) archived
      """)
    Map<String,Object> service(Long customer);
}
