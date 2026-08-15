package ffdd.opsconsole.finance.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppPayoutAddressMapper {
    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Long activeUser(@Param("userId") Long userId);

    @Select("SELECT 1 FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 AND COALESCE(sandbox,0)=1 LIMIT 1")
    Integer isSandboxUser(@Param("userId") Long userId);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("SELECT country_code countryCode,phone FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    UserContact userContact(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM nx_user_otp_challenge WHERE user_id=#{userId} AND challenge_no LIKE 'PAYOUT-%' AND created_at>=DATE_SUB(NOW(),INTERVAL 60 SECOND) AND is_deleted=0")
    int recentOtpCount(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM nx_user_otp_challenge WHERE user_id=#{userId} AND challenge_no LIKE 'PAYOUT-%' AND created_at>=CURRENT_DATE AND is_deleted=0")
    int todayOtpCount(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_user_otp_challenge
              (challenge_no,user_id,code_hash,expires_at,attempts,created_at,updated_at,is_deleted)
            VALUES(#{challengeNo},#{userId},SHA2(CONCAT(#{code},':',#{challengeNo}),256),
                   DATE_ADD(NOW(),INTERVAL 5 MINUTE),0,NOW(),NOW(),0)
            """)
    int insertOtp(@Param("userId") Long userId, @Param("challengeNo") String challengeNo,
                  @Param("code") String code);

    @Update("""
            UPDATE nx_user_otp_challenge
               SET consumed_at=NOW(),attempts=attempts+1,updated_at=NOW()
             WHERE user_id=#{userId} AND challenge_no=#{challengeNo}
               AND challenge_no LIKE 'PAYOUT-%'
               AND code_hash=SHA2(CONCAT(#{code},':',challenge_no),256)
               AND consumed_at IS NULL AND expires_at>=NOW() AND attempts<5 AND is_deleted=0
            """)
    int consumeOtp(@Param("userId") Long userId, @Param("challengeNo") String challengeNo,
                   @Param("code") String code);

    @Update("""
            UPDATE nx_user_otp_challenge SET attempts=attempts+1,updated_at=NOW()
             WHERE user_id=#{userId} AND challenge_no=#{challengeNo} AND challenge_no LIKE 'PAYOUT-%'
               AND consumed_at IS NULL AND expires_at>=NOW() AND attempts<5 AND is_deleted=0
            """)
    int incrementOtpFailure(@Param("userId") Long userId, @Param("challengeNo") String challengeNo);

    @Select("""
            SELECT network,address,status,effective_at effectiveAt,created_at createdAt,
                   next_change_allowed_at nextChangeAllowedAt,version
              FROM nx_user_payout_address
             WHERE user_id=#{userId} AND is_deleted=0 ORDER BY network
            """)
    List<PayoutAddressRow> list(@Param("userId") Long userId);

    @Select("""
            SELECT network,address,status,effective_at effectiveAt,created_at createdAt,
                   next_change_allowed_at nextChangeAllowedAt,version
              FROM nx_user_payout_address
             WHERE user_id=#{userId} AND network=#{network} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    PayoutAddressRow lock(@Param("userId") Long userId, @Param("network") String network);

    @Select("""
            SELECT COUNT(*) FROM nx_withdrawal_order
             WHERE user_id=#{userId} AND UPPER(status) NOT IN ('COMPLETED','REJECTED','CANCELLED','FAILED')
               AND is_deleted=0
            """)
    int unsettledWithdrawalCount(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_user_payout_address
              (user_id,network,address,status,effective_at,next_change_allowed_at,version,created_at,updated_at,is_deleted)
            VALUES(#{userId},#{network},#{address},'ACTIVE',DATE_ADD(NOW(),INTERVAL 24 HOUR),
                   DATE_ADD(NOW(),INTERVAL 7 DAY),0,NOW(),NOW(),0)
            """)
    int insert(@Param("userId") Long userId, @Param("network") String network,
               @Param("address") String address);

    @Update("""
            UPDATE nx_user_payout_address
               SET address=#{address},status='ACTIVE',effective_at=DATE_ADD(NOW(),INTERVAL 24 HOUR),
                   next_change_allowed_at=DATE_ADD(NOW(),INTERVAL 7 DAY),version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND network=#{network} AND version=#{version} AND is_deleted=0
            """)
    int update(@Param("userId") Long userId, @Param("network") String network,
               @Param("address") String address, @Param("version") Long version);

    @Insert("""
            INSERT INTO nx_user_payout_address_history
              (user_id,network,previous_address,new_address,change_type,created_at)
            VALUES(#{userId},#{network},#{previousAddress},#{newAddress},#{changeType},NOW())
            """)
    int insertHistory(@Param("userId") Long userId, @Param("network") String network,
                      @Param("previousAddress") String previousAddress, @Param("newAddress") String newAddress,
                      @Param("changeType") String changeType);

    record UserContact(String countryCode, String phone) { }
    record PayoutAddressRow(String network, String address, String status, LocalDateTime effectiveAt,
                            LocalDateTime createdAt,
                            LocalDateTime nextChangeAllowedAt, Long version) { }
}
