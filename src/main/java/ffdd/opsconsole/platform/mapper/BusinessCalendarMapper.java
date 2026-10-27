package ffdd.opsconsole.platform.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** R-1: nx_business_calendar. The DDL mirrors scripts/migrations/20261027_r1_business_calendar.sql. */
public interface BusinessCalendarMapper {
    record Row(Long id, String track, String zoneId, String countryCode, Integer offsetMinutes, LocalDate effectiveDate,
               LocalDateTime effectiveStorageAt, String operator, String reason, LocalDateTime createdAt) { }

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_business_calendar (
              id BIGINT NOT NULL AUTO_INCREMENT, track VARCHAR(32) NOT NULL, zone_id VARCHAR(64) NOT NULL,
              country_code VARCHAR(8) NULL, offset_minutes INT NOT NULL, effective_date DATE NOT NULL,
              effective_storage_at DATETIME NOT NULL, operator VARCHAR(64) NOT NULL, reason VARCHAR(255) NOT NULL,
              created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0,
              PRIMARY KEY (id),
              KEY idx_business_calendar_storage (track, is_deleted, effective_storage_at),
              KEY idx_business_calendar_date (track, is_deleted, effective_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void ensureTable();

    @Insert("""
            INSERT INTO nx_business_calendar (track,zone_id,country_code,offset_minutes,effective_date,effective_storage_at,operator,reason,created_at,updated_at,is_deleted)
            SELECT #{track},#{zoneId},#{countryCode},#{offsetMinutes},#{effectiveDate},#{effectiveStorageAt},'migration:r1',#{reason},NOW(),NOW(),0
             WHERE NOT EXISTS (SELECT 1 FROM nx_business_calendar WHERE track=#{track} AND effective_date=#{effectiveDate} AND is_deleted=0)
            """)
    int seed(@Param("track") String track, @Param("zoneId") String zoneId, @Param("countryCode") String countryCode,
             @Param("offsetMinutes") int offsetMinutes, @Param("effectiveDate") LocalDate effectiveDate,
             @Param("effectiveStorageAt") LocalDateTime effectiveStorageAt, @Param("reason") String reason);

    @Select("""
            SELECT id,track,zone_id AS zoneId,country_code AS countryCode,offset_minutes AS offsetMinutes,effective_date AS effectiveDate,
                   effective_storage_at AS effectiveStorageAt,operator,reason,created_at AS createdAt
              FROM nx_business_calendar WHERE is_deleted=0 ORDER BY effective_storage_at,id
            """)
    List<Row> listActive();

    @Insert("""
            INSERT INTO nx_business_calendar (track,zone_id,country_code,offset_minutes,effective_date,effective_storage_at,operator,reason,created_at,updated_at,is_deleted)
            VALUES ('GLOBAL',#{zoneId},#{countryCode},#{offsetMinutes},#{effectiveDate},#{effectiveStorageAt},#{operator},#{reason},#{now},#{now},0)
            """)
    int insertGlobal(@Param("zoneId") String zoneId, @Param("countryCode") String countryCode, @Param("offsetMinutes") int offsetMinutes,
                     @Param("effectiveDate") LocalDate effectiveDate, @Param("effectiveStorageAt") LocalDateTime effectiveStorageAt,
                     @Param("operator") String operator, @Param("reason") String reason, @Param("now") LocalDateTime now);

    /** Only a version that has not taken effect yet can be withdrawn. */
    @Update("UPDATE nx_business_calendar SET is_deleted=1,updated_at=#{now} WHERE id=#{id} AND track='GLOBAL' AND is_deleted=0 AND effective_storage_at>#{now}")
    int cancelPending(@Param("id") Long id, @Param("now") LocalDateTime now);
}
