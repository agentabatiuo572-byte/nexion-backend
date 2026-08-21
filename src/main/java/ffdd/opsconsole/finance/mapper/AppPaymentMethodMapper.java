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

    @Select("SELECT sandbox FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Integer userSandbox(@Param("userId") Long userId);

    /**
     * Serializes card mutations for one account.  The user row is the common
     * lock even when the account currently has no default card; without it,
     * two concurrent promotions of different non-default cards could both
     * pass their per-card CAS and leave two defaults.
     */
    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1 FOR UPDATE")
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("""
            SELECT id,card_token cardToken,brand,last4,cardholder_name holder,is_default isDefault,
                   created_at createdAt,COALESCE(source_environment,'PRODUCTION') sourceEnvironment,version
              FROM nx_wallet_bank_card
             WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment}
               AND status IN ('BOUND','ACTIVE') AND is_deleted=0
             ORDER BY is_default DESC,updated_at DESC,id DESC
            """)
    List<CardRow> list(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            SELECT id,card_token cardToken,brand,last4,cardholder_name holder,is_default isDefault,
                   created_at createdAt,COALESCE(source_environment,'PRODUCTION') sourceEnvironment,run_id runId,version
              FROM nx_wallet_bank_card
             WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND status IN ('BOUND','ACTIVE') AND is_deleted=0
             ORDER BY is_default DESC,updated_at DESC,id DESC
            """)
    List<CardRow> listScoped(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                             @Param("runId") String runId);

    @Select("""
            SELECT id,card_token cardToken,brand,last4,cardholder_name holder,is_default isDefault,
                   created_at createdAt,COALESCE(source_environment,'PRODUCTION') sourceEnvironment,version
              FROM nx_wallet_bank_card
             WHERE user_id=#{userId} AND card_token=#{token} AND source_environment=#{sourceEnvironment}
               AND is_deleted=0 LIMIT 1
            """)
    CardRow findByToken(@Param("userId") Long userId, @Param("token") String token,
                        @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            SELECT id,card_token cardToken,brand,last4,cardholder_name holder,is_default isDefault,
                   created_at createdAt,COALESCE(source_environment,'PRODUCTION') sourceEnvironment,run_id runId,version
              FROM nx_wallet_bank_card
             WHERE user_id=#{userId} AND card_token=#{token} AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND is_deleted=0 LIMIT 1
            """)
    CardRow findByTokenScoped(@Param("userId") Long userId, @Param("token") String token,
                              @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);

    @Select("""
            SELECT id,card_token cardToken,brand,last4,cardholder_name holder,is_default isDefault,
                   created_at createdAt,COALESCE(source_environment,'PRODUCTION') sourceEnvironment,version
              FROM nx_wallet_bank_card
             WHERE user_id=#{userId} AND card_token=#{token} AND source_environment=#{sourceEnvironment}
               AND status IN ('BOUND','ACTIVE') AND is_deleted=0 LIMIT 1
            """)
    CardRow findActiveByToken(@Param("userId") Long userId, @Param("token") String token,
                              @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            SELECT id,card_token cardToken,brand,last4,cardholder_name holder,is_default isDefault,
                   created_at createdAt,COALESCE(source_environment,'PRODUCTION') sourceEnvironment,run_id runId,version
              FROM nx_wallet_bank_card
             WHERE user_id=#{userId} AND card_token=#{token} AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND status IN ('BOUND','ACTIVE') AND is_deleted=0 LIMIT 1
            """)
    CardRow findActiveByTokenScoped(@Param("userId") Long userId, @Param("token") String token,
                                    @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);

    @Select("""
            SELECT id,card_token cardToken,brand,last4,cardholder_name holder,is_default isDefault,
                   created_at createdAt,COALESCE(source_environment,'PRODUCTION') sourceEnvironment,version
              FROM nx_wallet_bank_card
             WHERE user_id=#{userId} AND id=#{methodId} AND source_environment=#{sourceEnvironment}
               AND status IN ('BOUND','ACTIVE') AND is_deleted=0 LIMIT 1
            """)
    CardRow findActiveById(@Param("userId") Long userId, @Param("methodId") Long methodId,
                           @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            SELECT id,card_token cardToken,brand,last4,cardholder_name holder,is_default isDefault,
                   created_at createdAt,COALESCE(source_environment,'PRODUCTION') sourceEnvironment,run_id runId,version
              FROM nx_wallet_bank_card
             WHERE user_id=#{userId} AND id=#{methodId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND status IN ('BOUND','ACTIVE') AND is_deleted=0 LIMIT 1
            """)
    CardRow findActiveByIdScoped(@Param("userId") Long userId, @Param("methodId") Long methodId,
                                 @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);

    @Select("SELECT user_id FROM nx_wallet_bank_card WHERE card_token=#{token} AND source_environment=#{sourceEnvironment} LIMIT 1")
    Long tokenOwnerIncludingDeleted(@Param("token") String token,
                                    @Param("sourceEnvironment") String sourceEnvironment);

    @Select("SELECT user_id FROM nx_wallet_bank_card WHERE card_token=#{token} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} LIMIT 1")
    Long tokenOwnerIncludingDeletedScoped(@Param("token") String token, @Param("sourceEnvironment") String sourceEnvironment,
                                          @Param("runId") String runId);

    @Update("""
            UPDATE nx_wallet_bank_card SET brand=#{brand},last4=#{last4},cardholder_name=#{holder},
              status='BOUND',is_default=#{isDefault},unbound_reason=NULL,unbound_by=NULL,unbound_at=NULL,
              version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND card_token=#{token} AND source_environment=#{sourceEnvironment}
               AND status='UNBOUND' AND is_deleted=0
            """)
    int reactivate(@Param("userId") Long userId, @Param("token") String token, @Param("brand") String brand,
                   @Param("last4") String last4, @Param("holder") String holder, @Param("isDefault") boolean isDefault,
                   @Param("sourceEnvironment") String sourceEnvironment);

    @Update("""
            UPDATE nx_wallet_bank_card SET brand=#{brand},last4=#{last4},cardholder_name=#{holder},
              status='BOUND',is_default=#{isDefault},unbound_reason=NULL,unbound_by=NULL,unbound_at=NULL,
              version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND card_token=#{token} AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND status='UNBOUND' AND is_deleted=0
            """)
    int reactivateScoped(@Param("userId") Long userId, @Param("token") String token, @Param("brand") String brand,
                         @Param("last4") String last4, @Param("holder") String holder, @Param("isDefault") boolean isDefault,
                         @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);

    @Update("UPDATE nx_wallet_bank_card SET is_default=0,version=version+1,updated_at=NOW() WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND is_deleted=0 AND status IN ('BOUND','ACTIVE') AND is_default=1")
    int clearDefault(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment);

    @Update("UPDATE nx_wallet_bank_card SET is_default=0,version=version+1,updated_at=NOW() WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND is_deleted=0 AND status IN ('BOUND','ACTIVE') AND is_default=1")
    int clearDefaultScoped(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                           @Param("runId") String runId);

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM nx_trial_claim trial JOIN nx_wallet_bank_card card ON card.user_id=trial.user_id
               WHERE trial.user_id=#{userId} AND trial.is_deleted=0
                 AND UPPER(trial.status) IN ('CLAIMED','ACTIVE','GRACE','EXTENDED')
                 AND card.user_id=#{userId} AND card.is_default=1
                 AND (trial.payment_method_id=card.id OR trial.payment_method_id IS NULL)
                 AND card.source_environment=#{sourceEnvironment} AND card.is_deleted=0
            )
            """)
    boolean defaultTrialGuard(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM nx_trial_claim trial JOIN nx_wallet_bank_card card ON card.user_id=trial.user_id
               WHERE trial.user_id=#{userId} AND trial.is_deleted=0
                 AND UPPER(trial.status) IN ('CLAIMED','ACTIVE','GRACE','EXTENDED')
                 AND card.user_id=#{userId} AND card.is_default=1
                 AND (trial.payment_method_id=card.id OR trial.payment_method_id IS NULL)
                 AND card.source_environment=#{sourceEnvironment} AND card.run_id=#{runId} AND card.is_deleted=0
            )
            """)
    boolean defaultTrialGuardScoped(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                                    @Param("runId") String runId);

    @Update("""
            UPDATE nx_wallet_bank_card SET is_default=1,version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND id=#{methodId} AND version=#{expectedVersion}
               AND source_environment=#{sourceEnvironment} AND status IN ('BOUND','ACTIVE') AND is_deleted=0
            """)
    int setDefault(@Param("userId") Long userId, @Param("methodId") Long methodId,
                   @Param("expectedVersion") Long expectedVersion, @Param("sourceEnvironment") String sourceEnvironment);

    @Update("""
            UPDATE nx_wallet_bank_card SET is_default=1,version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND id=#{methodId} AND version=#{expectedVersion}
               AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND status IN ('BOUND','ACTIVE') AND is_deleted=0
            """)
    int setDefaultScoped(@Param("userId") Long userId, @Param("methodId") Long methodId,
                         @Param("expectedVersion") Long expectedVersion, @Param("sourceEnvironment") String sourceEnvironment,
                         @Param("runId") String runId);

    @Insert("""
            INSERT IGNORE INTO nx_wallet_bank_card
              (user_id,card_token,cardholder_name,brand,last4,status,is_default,source_environment,run_id,version,created_at,updated_at,is_deleted)
            VALUES (#{userId},#{token},#{holder},#{brand},#{last4},'BOUND',#{isDefault},#{sourceEnvironment},#{runId},0,NOW(),NOW(),0)
            """)
    int insert(CardRow row);

    record CardRow(Long id, Long userId, String cardToken, String brand, String last4, String holder,
                   boolean isDefault, LocalDateTime createdAt, String sourceEnvironment, String runId, Long version) {
        /** Compatibility constructor for callers written before CAS versions were exposed. */
        public CardRow(Long id, Long userId, String cardToken, String brand, String last4, String holder,
                       boolean isDefault, LocalDateTime createdAt, String sourceEnvironment) {
            this(id, userId, cardToken, brand, last4, holder, isDefault, createdAt, sourceEnvironment, "", 0L);
        }

        public CardRow(Long id, Long userId, String cardToken, String brand, String last4, String holder,
                       boolean isDefault, LocalDateTime createdAt, String sourceEnvironment, Long version) {
            this(id, userId, cardToken, brand, last4, holder, isDefault, createdAt, sourceEnvironment, "", version);
        }
    }
}
