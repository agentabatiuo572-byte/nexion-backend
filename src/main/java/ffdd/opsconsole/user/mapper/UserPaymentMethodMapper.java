package ffdd.opsconsole.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface UserPaymentMethodMapper extends BaseMapper<Object> {
    @Select("SELECT COUNT(*) > 0 FROM nx_user WHERE id = #{userId} AND is_deleted = 0")
    boolean userExists(@Param("userId") Long userId);

    @Select("""
            SELECT card.id, card.user_id AS userId, card.brand, card.last4,
                   NULL AS expiryLabel, 'TOKENIZED_CARD' AS provider,
                   card.is_default AS isDefault,
                   CASE WHEN card.status IN ('BOUND','ACTIVE') THEN 'BOUND' ELSE card.status END AS status,
                   EXISTS(
                     SELECT 1 FROM nx_trial_claim trial
                      WHERE trial.user_id = card.user_id AND trial.is_deleted = 0
                        AND UPPER(trial.status) IN ('CLAIMED','ACTIVE','GRACE','EXTENDED')
                        AND (trial.payment_method_id = card.id
                          OR (trial.payment_method_id IS NULL AND card.is_default = 1))
                   ) AS trialGuard,
                   (SELECT trial.claim_no FROM nx_trial_claim trial
                     WHERE trial.user_id = card.user_id AND trial.is_deleted = 0
                       AND UPPER(trial.status) IN ('CLAIMED','ACTIVE','GRACE','EXTENDED')
                       AND (trial.payment_method_id = card.id
                         OR (trial.payment_method_id IS NULL AND card.is_default = 1))
                     ORDER BY trial.claimed_at DESC, trial.id DESC LIMIT 1) AS trialRefId,
                   card.version, card.unbound_at AS unboundAt, card.psp_revoke_status AS pspRevokeStatus
              FROM nx_wallet_bank_card card
             WHERE card.user_id = #{userId} AND card.id = #{methodId} AND card.is_deleted = 0
             LIMIT 1
            """)
    PaymentMethodRow findMethod(@Param("userId") Long userId, @Param("methodId") Long methodId);

    @Select("""
            <script>
            SELECT card.id, card.user_id AS userId, card.brand, card.last4,
                   NULL AS expiryLabel, 'TOKENIZED_CARD' AS provider,
                   card.is_default AS isDefault,
                   CASE WHEN card.status IN ('BOUND','ACTIVE') THEN 'BOUND' ELSE card.status END AS status,
                   EXISTS(
                     SELECT 1 FROM nx_trial_claim trial
                      WHERE trial.user_id = card.user_id AND trial.is_deleted = 0
                        AND UPPER(trial.status) IN ('CLAIMED','ACTIVE','GRACE','EXTENDED')
                        AND (trial.payment_method_id = card.id
                          OR (trial.payment_method_id IS NULL AND card.is_default = 1))
                   ) AS trialGuard,
                   (SELECT trial.claim_no FROM nx_trial_claim trial
                     WHERE trial.user_id = card.user_id AND trial.is_deleted = 0
                       AND UPPER(trial.status) IN ('CLAIMED','ACTIVE','GRACE','EXTENDED')
                       AND (trial.payment_method_id = card.id
                         OR (trial.payment_method_id IS NULL AND card.is_default = 1))
                     ORDER BY trial.claimed_at DESC, trial.id DESC LIMIT 1) AS trialRefId,
                   card.version, card.unbound_at AS unboundAt, card.psp_revoke_status AS pspRevokeStatus
              FROM nx_wallet_bank_card card
             WHERE card.user_id = #{userId} AND card.is_deleted = 0
             <if test="includeUnbound == false">AND card.status IN ('BOUND','ACTIVE')</if>
             ORDER BY card.is_default DESC, card.status IN ('BOUND','ACTIVE') DESC, card.updated_at DESC, card.id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<PaymentMethodRow> listMethods(@Param("userId") Long userId,
                                       @Param("includeUnbound") boolean includeUnbound,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_wallet_bank_card
             WHERE user_id = #{userId} AND is_deleted = 0
             <if test="includeUnbound == false">AND status IN ('BOUND','ACTIVE')</if>
            </script>
            """)
    long countMethods(@Param("userId") Long userId, @Param("includeUnbound") boolean includeUnbound);

    @Update("""
            UPDATE nx_wallet_bank_card card
               SET status = 'UNBOUND', is_default = 0, psp_revoke_status = 'NOT_REQUIRED',
                   unbound_reason = #{reason}, unbound_by = #{operator}, unbound_at = NOW(),
                   version = version + 1, updated_at = NOW()
             WHERE user_id = #{userId} AND id = #{methodId} AND version = #{expectedVersion}
               AND status IN ('BOUND','ACTIVE') AND is_deleted = 0
               AND NOT EXISTS (
                 SELECT 1 FROM nx_trial_claim trial
                  WHERE trial.user_id = card.user_id AND trial.is_deleted = 0
                    AND UPPER(trial.status) IN ('CLAIMED','ACTIVE','GRACE','EXTENDED')
                    AND (trial.payment_method_id = card.id
                      OR (trial.payment_method_id IS NULL AND card.is_default = 1))
               )
            """)
    int unbind(@Param("userId") Long userId, @Param("methodId") Long methodId,
               @Param("expectedVersion") Long expectedVersion, @Param("reason") String reason,
               @Param("operator") String operator);

    @Update("""
            UPDATE nx_wallet_bank_card
               SET is_default = 1, version = version + 1, updated_at = NOW()
             WHERE id = (
               SELECT fallback_id FROM (
                 SELECT id AS fallback_id FROM nx_wallet_bank_card
                  WHERE user_id = #{userId} AND status IN ('BOUND','ACTIVE') AND is_deleted = 0
                  ORDER BY created_at ASC, id ASC LIMIT 1
               ) fallback
             )
            """)
    int promoteFallbackDefault(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_wallet_bank_card card
               SET last_rebind_notified_at = NOW(), version = version + 1, updated_at = NOW()
             WHERE user_id = #{userId} AND id = #{methodId} AND version = #{expectedVersion}
               AND status IN ('BOUND','ACTIVE') AND is_deleted = 0
               AND EXISTS (
                 SELECT 1 FROM nx_trial_claim trial
                  WHERE trial.user_id = card.user_id AND trial.is_deleted = 0
                    AND UPPER(trial.status) IN ('CLAIMED','ACTIVE','GRACE','EXTENDED')
                    AND (trial.payment_method_id = card.id
                      OR (trial.payment_method_id IS NULL AND card.is_default = 1))
               )
            """)
    int markRebindNotified(@Param("userId") Long userId, @Param("methodId") Long methodId,
                           @Param("expectedVersion") Long expectedVersion);

    @Insert("""
            INSERT IGNORE INTO nx_notification (
              biz_no, user_id, type, priority, title, body, cta_label, cta_href,
              read_flag, push_status, push_attempts, next_push_at, created_at, updated_at, is_deleted
            ) VALUES (#{bizNo}, #{userId}, 'PAYMENT_METHOD', 'high', #{title}, #{body},
                      '查看支付方式', #{href}, 0, 'PENDING', 0, NOW(), NOW(), NOW(), 0)
            """)
    int queueNotification(@Param("userId") Long userId, @Param("bizNo") String bizNo,
                          @Param("title") String title, @Param("body") String body,
                          @Param("href") String href);

    @Update("UPDATE nx_user SET nickname = #{nickname}, updated_at = NOW() WHERE id = #{userId} AND is_deleted = 0")
    int resetNickname(@Param("userId") Long userId, @Param("nickname") String nickname);

    @Select("SELECT nickname FROM nx_user WHERE id = #{userId} AND is_deleted = 0 LIMIT 1")
    String currentNickname(@Param("userId") Long userId);

    record PaymentMethodRow(Long id, Long userId, String brand, String last4, String expiryLabel,
                            String provider, boolean isDefault, String status, boolean trialGuard,
                            String trialRefId, Long version, java.time.LocalDateTime unboundAt,
                            String pspRevokeStatus) {}
}
