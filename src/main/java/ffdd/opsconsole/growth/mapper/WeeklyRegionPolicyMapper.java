package ffdd.opsconsole.growth.mapper;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only legacy calendar evidence; this boundary never publishes a definition. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface WeeklyRegionPolicyMapper {
    record LegacyWindow(String instanceKey, LocalDateTime eligibleFrom, LocalDateTime eligibleUntil) {}

    @Select("""
            SELECT instance_key instanceKey,eligible_from eligibleFrom,eligible_until eligibleUntil
              FROM nx_growth_weekly_definition
             WHERE instance_key REGEXP '^WEEK:[0-9]{4}-W[0-9]{2}$'
               AND eligible_from<=#{now} AND eligible_until>#{now}
             ORDER BY eligible_from DESC LIMIT 2
            """)
    List<LegacyWindow> openLegacy(@Param("now") LocalDateTime now);

    record AssignedWindow(String callingCode, Long mappingVersion, String instanceKey,
                          LocalDate assignedWeekStart, String calendarVersion, String businessTimezone,
                          LocalDate frozenWeekStart, LocalDateTime eligibleFrom, LocalDateTime eligibleUntil,
                          String allocationState, long users) {}

    @Select("""
            WITH ranked_assignments AS (
                SELECT user_id, country_calling_code, mapping_version, instance_key, local_week_start,
                       ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY local_week_start DESC) assignment_rank
                  FROM nx_growth_account_weekly_period
            )
            SELECT a.country_calling_code callingCode, a.mapping_version mappingVersion,
                   a.instance_key instanceKey, a.local_week_start assignedWeekStart,
                   h.calendar_version calendarVersion, h.business_timezone businessTimezone,
                   h.local_week_start frozenWeekStart, h.eligible_from eligibleFrom, h.eligible_until eligibleUntil,
                   CASE WHEN a.user_id IS NULL THEN 'UNASSIGNED'
                        WHEN h.instance_key IS NULL THEN 'HEADER_MISSING'
                        WHEN h.eligible_from IS NULL OR h.eligible_until IS NULL
                          OR h.eligible_until <= h.eligible_from THEN 'HEADER_INVALID'
                        WHEN h.eligible_from > #{asOf} THEN 'FUTURE_ASSIGNMENT'
                        WHEN h.eligible_until <= #{asOf} THEN 'EXPIRED_ASSIGNMENT'
                        ELSE 'ACTIVE' END allocationState,
                   COUNT(DISTINCT u.id) users
              FROM nx_user u
              LEFT JOIN ranked_assignments a ON a.user_id = u.id AND a.assignment_rank = 1
              LEFT JOIN nx_growth_weekly_definition h ON h.instance_key = a.instance_key
             WHERE u.status = 'ACTIVE' AND u.is_deleted = 0 AND COALESCE(u.sandbox, 0) = 0
             GROUP BY a.country_calling_code, a.mapping_version, a.instance_key, a.local_week_start,
                      h.instance_key, h.calendar_version, h.business_timezone, h.local_week_start,
                      h.eligible_from, h.eligible_until, allocationState
             ORDER BY allocationState, callingCode, eligibleFrom, instanceKey, mappingVersion
            """)
    List<AssignedWindow> assignedWindows(@Param("asOf") LocalDateTime asOf);

}
