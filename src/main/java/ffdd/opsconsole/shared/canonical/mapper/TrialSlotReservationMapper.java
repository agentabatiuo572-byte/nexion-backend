package ffdd.opsconsole.shared.canonical.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** A trial is a reserved physical slot, never an extra purchased device. */
public interface TrialSlotReservationMapper {
    String RESERVED_TRIAL = """
            SELECT tc.id FROM nx_trial_claim tc
              JOIN nx_user u ON u.id=tc.user_id
             WHERE tc.user_id=#{userId} AND tc.is_deleted=0 AND COALESCE(u.sandbox,0)=0
               AND UPPER(tc.status) IN ('CLAIMED','ACTIVE','GRACE','EXTENDED')
            """;

    @Select("SELECT EXISTS(" + RESERVED_TRIAL + ")")
    int reservedTrialSlotCount(@Param("userId") Long userId);

    // The command already holds the user lock; read this block against current state too.
    @Select("SELECT EXISTS(" + RESERVED_TRIAL + " FOR SHARE)")
    int lockReservedTrialSlotCount(@Param("userId") Long userId);
}
