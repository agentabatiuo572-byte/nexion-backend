package ffdd.opsconsole.team.mapper;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BinarySettlementScheduleMapper {
    @Select("""
            SELECT a.config_value automatic, t.config_value enabledAt,
                   p.config_value cadence
              FROM nx_config_item a
              JOIN nx_config_item t ON t.config_key='team.binary.autoEnabledAtUtc'
               AND t.status=1 AND t.is_deleted=0
              JOIN nx_config_item p ON p.config_key='team.ui.F.binary.settlePeriod'
               AND p.status=1 AND p.is_deleted=0
             WHERE a.config_key='team.ui.F.binary.autoSettle' AND a.status=1 AND a.is_deleted=0
            """)
    ScheduleSettings readSettings();

    @Select("""
            SELECT a.config_value automatic, t.config_value enabledAt
              FROM nx_config_item a
              JOIN nx_config_item t ON t.config_key='team.binary.autoEnabledAtUtc'
               AND t.status=1 AND t.is_deleted=0
             WHERE a.config_key='team.ui.F.binary.autoSettle' AND a.status=1 AND a.is_deleted=0
             FOR UPDATE OF a
            """)
    Activation lockActivation();

    @Select("""
            SELECT u.id
              FROM nx_user u
              JOIN nx_team_member m ON m.user_id=u.id AND m.level=1 AND m.is_deleted=0
               AND m.member_user_id<>u.id
             WHERE u.id>#{afterOwnerId} AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=0
             GROUP BY u.id
            HAVING COUNT(DISTINCT m.member_user_id)>=2
             ORDER BY u.id
             LIMIT #{limit}
            """)
    List<Long> listOwners(@Param("afterOwnerId") long afterOwnerId, @Param("limit") int limit);

    @Select("SELECT MAX(settlement_date) FROM nx_binary_auto_settlement_batch")
    LocalDate latestBatchDate();

    @Insert("""
            INSERT IGNORE INTO nx_binary_auto_settlement_batch
              (settlement_date,status,created_at,updated_at)
            VALUES (#{date},'PENDING',NOW(6),NOW(6))
            """)
    void createBatch(@Param("date") LocalDate date);

    @Select("""
            SELECT settlement_date settlementDate, last_owner_id lastOwnerId
              FROM nx_binary_auto_settlement_batch
             WHERE status='PENDING' AND settlement_date>=#{firstDate} AND settlement_date<#{today}
             ORDER BY settlement_date
            """)
    List<PendingBatch> pendingBatches(@Param("firstDate") LocalDate firstDate, @Param("today") LocalDate today);

    @Update("""
            UPDATE nx_binary_auto_settlement_batch
               SET status='CANCELED', last_error='F3_AUTO_SETTLEMENT_DISABLED', updated_at=NOW(6)
             WHERE status='PENDING' AND settlement_date<#{firstDate}
            """)
    int cancelOldBatches(@Param("firstDate") LocalDate firstDate);

    @Delete("""
            DELETE r FROM nx_binary_auto_settlement_retry r
              JOIN nx_binary_auto_settlement_batch b ON b.settlement_date=r.settlement_date
             WHERE b.status='CANCELED'
            """)
    void deleteCanceledRetries();

    @Update("""
            UPDATE nx_binary_auto_settlement_batch
               SET last_owner_id=#{ownerId}, updated_at=NOW(6)
             WHERE settlement_date=#{date} AND status='PENDING' AND last_owner_id<#{ownerId}
            """)
    int advanceCursor(@Param("date") LocalDate date, @Param("ownerId") long ownerId);

    @Insert("""
            INSERT INTO nx_binary_auto_settlement_retry (settlement_date,owner_user_id,reason)
            VALUES (#{date},#{ownerId},#{reason,jdbcType=VARCHAR})
            ON DUPLICATE KEY UPDATE reason=#{reason,jdbcType=VARCHAR}
            """)
    void enqueueRetry(@Param("date") LocalDate date, @Param("ownerId") long ownerId, @Param("reason") String reason);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM nx_binary_auto_settlement_retry
             WHERE settlement_date=#{date} AND NOT (reason <=> #{ownerWaitReason}))
            """)
    boolean hasBlockingRetries(@Param("date") LocalDate date, @Param("ownerWaitReason") String ownerWaitReason);

    @Select("""
            SELECT owner_user_id
              FROM nx_binary_auto_settlement_retry
             WHERE settlement_date=#{date} AND owner_user_id>#{afterOwnerId}
             ORDER BY owner_user_id
             LIMIT #{limit}
            """)
    List<Long> retryOwners(@Param("date") LocalDate date,
                           @Param("afterOwnerId") long afterOwnerId, @Param("limit") int limit);

    @Delete("""
            DELETE FROM nx_binary_auto_settlement_retry
             WHERE settlement_date=#{date} AND owner_user_id=#{ownerId}
            """)
    int claimRetry(@Param("date") LocalDate date, @Param("ownerId") long ownerId);

    @Update("""
            UPDATE nx_binary_auto_settlement_batch
               SET last_error=#{reason}, updated_at=NOW(6)
             WHERE settlement_date=#{date} AND status='PENDING' AND NOT (last_error <=> #{reason})
            """)
    int recordError(@Param("date") LocalDate date, @Param("reason") String reason);

    @Update("""
            UPDATE nx_binary_auto_settlement_batch
               SET status='COMPLETE', last_error=NULL, updated_at=NOW(6)
             WHERE settlement_date=#{date} AND status='PENDING'
               AND NOT EXISTS (
                   SELECT 1 FROM nx_binary_auto_settlement_retry r WHERE r.settlement_date=#{date}
               )
            """)
    int completeBatch(@Param("date") LocalDate date);

    record ScheduleSettings(String automatic, String enabledAt, String cadence) { }

    record Activation(String automatic, String enabledAt) { }

    record PendingBatch(LocalDate settlementDate, long lastOwnerId) { }
}
