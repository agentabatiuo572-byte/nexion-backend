package ffdd.opsconsole.overview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.device.mapper.DeviceOpsMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface BusinessSummaryMapper extends BaseMapper<Object> {
    @Select("WITH " + BusinessSummarySql.CTES + """
            SELECT metric,SUM(value) AS value FROM facts
             WHERE occurred_at>=#{from} AND occurred_at<#{to} GROUP BY metric
            """)
    List<Metric> current(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT metric,SUM(value) AS value FROM nx_business_summary_daily
             WHERE biz_date>=#{from} AND biz_date<#{to} GROUP BY metric
            """)
    List<Metric> rolledUp(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("SELECT statistics_start AS statisticsStart,as_of AS asOf,calendar_signature AS calendarSignature FROM nx_business_summary_state WHERE id=1")
    State state();

    @Select("SELECT statistics_start AS statisticsStart,as_of AS asOf,calendar_signature AS calendarSignature FROM nx_business_summary_state WHERE id=1 FOR UPDATE")
    State lockState();

    // A changed source can invalidate an old payment/registration day and the user's lifetime-first day.
    // Read all dates for those users, including soft-deleted sources, so obsolete rollup values are removed.
    @Select("<script>WITH changed_users AS (" + """
            SELECT id AS user_id FROM nx_user WHERE #{since} IS NULL OR updated_at>=#{since}
            UNION SELECT user_id FROM nx_wallet_ledger WHERE #{since} IS NULL OR updated_at>=#{since}
            UNION SELECT user_id FROM nx_deposit_order WHERE #{since} IS NULL OR updated_at>=#{since}
            UNION SELECT user_id FROM nx_payment_record WHERE #{since} IS NULL OR updated_at>=#{since}
            UNION SELECT user_id FROM nx_vietqr_reconciliation WHERE #{since} IS NULL OR updated_at>=#{since}
            UNION SELECT user_id FROM nx_vietqr_intent WHERE #{since} IS NULL OR updated_at>=#{since}
            UNION SELECT i.user_id FROM nx_hdpay_payin_order h JOIN nx_vietqr_intent i ON i.intent_no=h.merchant_order_id
                WHERE #{since} IS NULL OR h.updated_at>=#{since}
            UNION SELECT user_id FROM nx_order WHERE #{since} IS NULL OR updated_at>=#{since}
            UNION SELECT user_id FROM nx_withdrawal_order WHERE #{since} IS NULL OR updated_at>=#{since}
            UNION SELECT user_id FROM nx_purchase_payment_intent WHERE #{since} IS NULL OR updated_at>=#{since}
            ), source_dates AS (
            SELECT u.created_at AS occurred_at FROM nx_user u JOIN changed_users c ON c.user_id=u.id
            UNION SELECT l.created_at FROM nx_wallet_ledger l JOIN changed_users c ON c.user_id=l.user_id
                WHERE l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP','CARD_TOPUP','VIETQR_DEPOSIT')
            UNION SELECT COALESCE(o.paid_at,o.created_at) FROM nx_order o JOIN changed_users c ON c.user_id=o.user_id
            UNION SELECT w.completed_at FROM nx_withdrawal_order w JOIN changed_users c ON c.user_id=w.user_id
            UNION SELECT p.confirmed_at FROM nx_purchase_payment_intent p JOIN changed_users c ON c.user_id=p.user_id
            ) SELECT DISTINCT
            """ + BusinessSummarySql.DATE + " AS date FROM source_dates WHERE occurred_at IS NOT NULL AND occurred_at &lt; #{asOf} ORDER BY date</script>")
    List<LocalDate> dirtyDates(@Param("since") LocalDateTime since, @Param("asOf") LocalDateTime asOf);

    @Delete("<script>DELETE FROM nx_business_summary_daily WHERE biz_date IN "
            + "<foreach collection='dates' item='date' open='(' separator=',' close=')'>#{date}</foreach></script>")
    int deleteDates(@Param("dates") List<LocalDate> dates);

    @Insert("<script>INSERT INTO nx_business_summary_daily(biz_date,metric,value) WITH " + BusinessSummarySql.CTES
            + " SELECT " + BusinessSummarySql.DATE + " AS date,metric,SUM(value) FROM facts WHERE occurred_at &lt; #{asOf} AND "
            + BusinessSummarySql.DATE + " IN <foreach collection='dates' item='date' open='(' separator=',' close=')'>#{date}</foreach>"
            + " GROUP BY date,metric</script>")
    int rebuildDates(@Param("dates") List<LocalDate> dates, @Param("asOf") LocalDateTime asOf);

    @Update("""
            UPDATE nx_business_summary_state SET statistics_start=#{start},as_of=#{asOf},calendar_signature=#{signature} WHERE id=1
            """)
    int saveState(@Param("start") LocalDate start, @Param("asOf") LocalDateTime asOf, @Param("signature") String signature);

    @Select("""
            SELECT COUNT(DISTINCT a.user_id) FROM nx_user_daily_active a
              JOIN nx_user u ON u.id=a.user_id AND u.sandbox=0 AND u.is_deleted=0
             WHERE a.first_seen_at>=#{from} AND a.first_seen_at<#{to}
            """)
    long active(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("SELECT COUNT(*) FROM nx_user WHERE sandbox=0 AND is_deleted=0 AND created_at<#{asOf}")
    long registeredTotal(@Param("asOf") LocalDateTime asOf);

    @Select("""
            SELECT COUNT(*) AS count,COALESCE(SUM(w.amount),0) AS value
              FROM nx_withdrawal_order w WHERE w.is_deleted=0 AND w.asset='USDT'
               AND w.status IN ('SUBMITTED','PENDING','REVIEW_PENDING','REVIEWING','EXTENDED_HOLD','DELAYED',
                   'REVIEW_PASSED','PENDING_CHAIN','PROCESSING','SENT','CHAIN_SUBMITTED','FROZEN','TX_ORPHANED','DEAD')
               AND (w.d5_payout_source IS NULL OR w.d5_payout_source NOT IN ('mock','dev-simulator'))
               AND NOT EXISTS (SELECT 1 FROM nx_user u WHERE u.id=w.user_id AND u.sandbox=1)
            """)
    Stock inTransit();

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN
            """ + DeviceOpsMapper.E5_RUNTIME_ONLINE + """
                THEN 1 ELSE 0 END),0) AS online,
                   COALESCE(SUM(CASE WHEN
            """ + DeviceOpsMapper.E5_RUNTIME_ABNORMAL + """
                THEN 1 ELSE 0 END),0) AS abnormal
              FROM nx_user_device d LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0
             WHERE d.is_deleted=0 AND d.source_environment='PRODUCTION' AND d.run_id=''
               AND
            """ + DeviceOpsMapper.E5_ACTIVATED_OWNED + """
               AND NOT EXISTS (SELECT 1 FROM nx_user u WHERE u.id=d.user_id AND u.sandbox=1)
            """)
    Devices devices();

    @Select("SELECT biz_date AS date,value FROM nx_business_summary_daily WHERE metric='registered' AND biz_date>=#{from} AND biz_date<#{to} ORDER BY biz_date")
    List<Trend> rolledUpRegistrations(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("SELECT " + BusinessSummarySql.DATE + " AS date,COUNT(*) AS value FROM ("
            + " SELECT created_at AS occurred_at FROM nx_user WHERE is_deleted=0 AND sandbox=0 AND created_at>=#{from} AND created_at<#{to}"
            + ") registrations GROUP BY date ORDER BY date")
    List<Trend> registrations(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    record Metric(String metric, BigDecimal value) {}
    record State(LocalDate statisticsStart, LocalDateTime asOf, String calendarSignature) {}
    record Stock(long count, BigDecimal value) {}
    record Devices(long online, long abnormal) {}
    record Trend(LocalDate date, BigDecimal value) {}
}
