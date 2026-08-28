package ffdd.opsconsole.finance.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Development-only payout-address fixture persistence in the canonical business tables. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface DevelopmentPayoutAddressMapper {

    @Select("""
            SELECT id FROM nx_user
             WHERE country_code=#{countryCode} AND phone=#{phone}
               AND sandbox=1 AND status='ACTIVE' AND is_deleted=0
             LIMIT 1
            """)
    Long findDevelopmentUserId(@Param("countryCode") String countryCode, @Param("phone") String phone);

    /**
     * Never updates an existing row, including a soft-deleted one. The unique
     * user/network key therefore preserves every user choice and removal.
     */
    @Insert("""
            INSERT IGNORE INTO nx_user_payout_address
              (user_id,network,address,status,effective_at,next_change_allowed_at,version,
               created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{network},#{address},'ACTIVE',DATE_SUB(NOW(),INTERVAL 8 DAY),NOW(),0,
               DATE_SUB(NOW(),INTERVAL 8 DAY),NOW(),0)
            """)
    int insertIfAbsent(DevelopmentPayoutAddress row);

    @Insert("""
            INSERT INTO nx_user_payout_address_history
              (user_id,network,previous_address,new_address,change_type,created_at)
            VALUES (#{userId},#{network},NULL,#{address},'DEV_BOOTSTRAP',NOW())
            """)
    int insertHistory(DevelopmentPayoutAddress row);

    record DevelopmentPayoutAddress(Long userId, String network, String address) { }
}
