package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.infrastructure.WalletBankCardEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** App-side, self-scoped payment-card projection. Never exposes a PAN or CVV. */
public interface AppPaymentMethodMapper extends BaseMapper<WalletBankCardEntity> {
    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND is_deleted=0 LIMIT 1")
    Long activeUser(@Param("userId") Long userId);

    @Select("""
            SELECT id,card_token cardToken,brand,last4,cardholder_name holder,is_default isDefault,
                   created_at createdAt
              FROM nx_wallet_bank_card
             WHERE user_id=#{userId} AND status IN ('BOUND','ACTIVE') AND is_deleted=0
             ORDER BY is_default DESC,updated_at DESC,id DESC
            """)
    List<CardRow> list(@Param("userId") Long userId);

    @Select("""
            SELECT id,card_token cardToken,brand,last4,cardholder_name holder,is_default isDefault,
                   created_at createdAt
              FROM nx_wallet_bank_card
             WHERE user_id=#{userId} AND card_token=#{token} AND is_deleted=0 LIMIT 1
            """)
    CardRow findByToken(@Param("userId") Long userId, @Param("token") String token);

    @Select("""
            SELECT id,card_token cardToken,brand,last4,cardholder_name holder,is_default isDefault,
                   created_at createdAt
              FROM nx_wallet_bank_card
             WHERE user_id=#{userId} AND card_token=#{token} AND status IN ('BOUND','ACTIVE') AND is_deleted=0 LIMIT 1
            """)
    CardRow findActiveByToken(@Param("userId") Long userId, @Param("token") String token);

    @Select("SELECT user_id FROM nx_wallet_bank_card WHERE card_token=#{token} LIMIT 1")
    Long tokenOwnerIncludingDeleted(@Param("token") String token);

    @Update("""
            UPDATE nx_wallet_bank_card SET brand=#{brand},last4=#{last4},cardholder_name=#{holder},
              status='BOUND',is_default=#{isDefault},unbound_reason=NULL,unbound_by=NULL,unbound_at=NULL,
              version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND card_token=#{token} AND status='UNBOUND' AND is_deleted=0
            """)
    int reactivate(@Param("userId") Long userId, @Param("token") String token, @Param("brand") String brand,
                   @Param("last4") String last4, @Param("holder") String holder, @Param("isDefault") boolean isDefault);

    @Update("UPDATE nx_wallet_bank_card SET is_default=0,version=version+1,updated_at=NOW() WHERE user_id=#{userId} AND is_deleted=0 AND status IN ('BOUND','ACTIVE')")
    int clearDefault(@Param("userId") Long userId);

    @Insert("""
            INSERT IGNORE INTO nx_wallet_bank_card
              (user_id,card_token,cardholder_name,brand,last4,status,is_default,version,created_at,updated_at,is_deleted)
            VALUES (#{userId},#{token},#{holder},#{brand},#{last4},'BOUND',#{isDefault},0,NOW(),NOW(),0)
            """)
    int insert(CardRow row);

    record CardRow(Long id, Long userId, String cardToken, String brand, String last4, String holder,
                   boolean isDefault, LocalDateTime createdAt) { }
}
