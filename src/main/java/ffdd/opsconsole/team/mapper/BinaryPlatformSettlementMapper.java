package ffdd.opsconsole.team.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BinaryPlatformSettlementMapper {
    @Select("""
            SELECT COALESCE(SUM(o.amount_usdt), 0)
              FROM nx_order o JOIN nx_user u ON u.id=o.user_id AND u.sandbox=0
             WHERE o.is_deleted=0 AND o.amount_usdt>0
               AND UPPER(COALESCE(o.payment_status,'')) IN ('PAID','CONFIRMED','SUCCESS')
               AND UPPER(COALESCE(o.order_status,'')) NOT IN
                   ('REFUNDED','CHARGEBACK','CANCELLED','FAILED','EXPIRED','REVERSED')
               AND o.paid_at>=#{start} AND o.paid_at<#{end}
            """)
    BigDecimal paidSales(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM nx_binary_commission_settlement s
              JOIN nx_user u ON u.id=s.user_id AND u.sandbox=0
             WHERE s.settlement_date=#{date} AND s.is_deleted=0 AND s.amount_usdt>0 AND s.commission_event_id>0)
            """)
    boolean hasEarlierSettlement(LocalDate date);

    @Select("""
            SELECT settlement_date settlementDate,period_start periodStart,period_days periodDays,platform_cap_pct platformCapPct,
                   paid_sales_usdt paidSales,cap_amount_usdt capAmount,raw_total_usdt rawTotal
              FROM nx_binary_platform_period WHERE settlement_date=#{date}
            """)
    Period period(LocalDate date);

    @Select("SELECT MAX(settlement_date) FROM nx_binary_platform_period WHERE settlement_date<#{date}")
    LocalDate previousEnd(LocalDate date);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM nx_binary_platform_period
             WHERE settlement_date>#{date} AND period_start<=#{date})
            """)
    boolean includedInLaterPeriod(LocalDate date);

    @Select("""
            SELECT user_id userId,raw_amount_usdt rawAmount,allocated_usdt allocated
              FROM nx_binary_platform_allocation WHERE settlement_date=#{date} AND user_id=#{userId}
            """)
    Allocation allocation(@Param("date") LocalDate date, @Param("userId") long userId);

    @Select("""
            SELECT COALESCE(SUM(allocated_usdt),0) FROM nx_binary_platform_allocation
             WHERE settlement_date=#{date} AND settlement_mode IS NULL
            """)
    BigDecimal remaining(LocalDate date);

    @Insert("""
            INSERT INTO nx_binary_platform_period
              (settlement_date,period_start,period_days,platform_cap_pct,paid_sales_usdt,cap_amount_usdt,raw_total_usdt)
            VALUES(#{settlementDate},#{periodStart},#{periodDays},#{platformCapPct},#{paidSales},#{capAmount},#{rawTotal})
            """)
    int insertPeriod(Period period);

    @Insert("""
            <script>
            INSERT INTO nx_binary_platform_allocation(settlement_date,user_id,raw_amount_usdt,allocated_usdt) VALUES
            <foreach collection="rows" item="row" separator=",">
              (#{date},#{row.userId},#{row.rawAmount},#{row.allocated})
            </foreach>
            </script>
            """)
    int insertAllocations(@Param("date") LocalDate date, @Param("rows") List<Allocation> rows);

    @Update("""
            UPDATE nx_binary_platform_allocation SET settlement_mode=#{mode},settled_raw_amount_usdt=#{rawAmount}
             WHERE settlement_date=#{date} AND user_id=#{userId} AND settlement_mode IS NULL
            """)
    int markSettled(@Param("date") LocalDate date, @Param("userId") long userId,
                    @Param("mode") String mode, @Param("rawAmount") BigDecimal rawAmount);

    record Period(LocalDate settlementDate, LocalDate periodStart, int periodDays, BigDecimal platformCapPct,
                  BigDecimal paidSales, BigDecimal capAmount, BigDecimal rawTotal) { }

    record Allocation(long userId, BigDecimal rawAmount, BigDecimal allocated) { }
}
