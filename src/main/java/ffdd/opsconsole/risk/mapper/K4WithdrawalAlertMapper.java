package ffdd.opsconsole.risk.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper") // Alert queries span admin RBAC and receipt JSON; there is no safe single-entity CRUD surface.
public interface K4WithdrawalAlertMapper {

    @Select("""
            SELECT DISTINCT a.id
              FROM nx_admin a
              JOIN nx_admin_role_relation rr ON rr.admin_id=a.id AND rr.is_deleted=0
              JOIN nx_admin_role r ON r.id=rr.role_id AND r.status=1 AND r.is_deleted=0
              LEFT JOIN nx_admin_role_permission rp ON rp.role_id=r.id AND rp.is_deleted=0
              LEFT JOIN nx_admin_permission p ON p.id=rp.permission_id
               AND p.status=1 AND p.is_deleted=0
               AND p.permission_code='risk_k4_user_override'
             WHERE a.status=1 AND a.is_deleted=0
               AND (r.role_code='SUPER_ADMIN' OR p.id IS NOT NULL)
             ORDER BY a.id
            """)
    List<Long> activeAlertRecipientIds();

    @Insert("""
            INSERT IGNORE INTO nx_k4_withdrawal_alert_receipt
              (event_id,recipient_admin_id,withdrawal_no,payload_json,created_at,updated_at,is_deleted)
            VALUES (#{eventId},#{recipientAdminId},#{withdrawalNo},CAST(#{payloadJson} AS JSON),NOW(),NOW(),0)
            """)
    int insertReceipt(@Param("eventId") String eventId,
                      @Param("recipientAdminId") Long recipientAdminId,
                      @Param("withdrawalNo") String withdrawalNo,
                      @Param("payloadJson") String payloadJson);

    @Select("""
            SELECT COUNT(1) FROM nx_k4_withdrawal_alert_receipt
             WHERE event_id=#{eventId} AND recipient_admin_id=#{recipientAdminId} AND is_deleted=0
            """)
    int countReceipt(@Param("eventId") String eventId, @Param("recipientAdminId") Long recipientAdminId);

    @Select("""
            SELECT id,event_id eventId,withdrawal_no withdrawalNo,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.risk_score')) AS SIGNED) riskScore,
                   JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.priority')) priority,
                   JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.model_version')) modelVersion,
                   JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.score_as_of')) scoreAsOf,
                   read_at readAt,created_at createdAt
              FROM nx_k4_withdrawal_alert_receipt
             WHERE recipient_admin_id=#{recipientAdminId} AND is_deleted=0
             ORDER BY created_at DESC,id DESC LIMIT #{limit}
            """)
    List<AlertRow> listAlerts(@Param("recipientAdminId") Long recipientAdminId, @Param("limit") int limit);

    @Update("""
            UPDATE nx_k4_withdrawal_alert_receipt
               SET read_at=COALESCE(read_at,NOW()),updated_at=NOW()
             WHERE event_id=#{eventId} AND recipient_admin_id=#{recipientAdminId} AND is_deleted=0
            """)
    int markRead(@Param("eventId") String eventId, @Param("recipientAdminId") Long recipientAdminId);

    record AlertRow(Long id, String eventId, String withdrawalNo, Integer riskScore,
                    String priority, String modelVersion, String scoreAsOf,
                    LocalDateTime readAt, LocalDateTime createdAt) { }
}
