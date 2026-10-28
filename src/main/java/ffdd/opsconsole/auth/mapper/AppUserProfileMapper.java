package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.Map;

public interface AppUserProfileMapper extends BaseMapper<UserEntity> {
    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Long activeUser(@Param("userId") Long userId);

    @Select("SELECT nickname FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1 FOR UPDATE")
    String currentNicknameForUpdate(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user
               SET nickname=#{nickname},nickname_changed_at=NOW(),updated_at=NOW()
             WHERE id=#{userId} AND nickname=#{expectedNickname}
               AND status='ACTIVE' AND is_deleted=0
            """)
    int updateNickname(@Param("userId") Long userId,
                       @Param("nickname") String nickname,
                       @Param("expectedNickname") String expectedNickname);

    @Select("""
            SELECT nickname,avatar_url AS avatarObjectKey,language,updated_at AS updatedAt,
                   nickname_changed_at AS nicknameChangedAt,avatar_changed_at AS avatarChangedAt
              FROM nx_user
             WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0
             LIMIT 1
            """)
    Map<String, Object> profile(@Param("userId") Long userId);

    /** Read after the row lock taken by currentNicknameForUpdate / currentAvatarForUpdate in the same transaction. */
    @Select("SELECT nickname_changed_at FROM nx_user WHERE id=#{userId} AND is_deleted=0 LIMIT 1")
    java.time.LocalDateTime nicknameChangedAt(@Param("userId") Long userId);

    @Select("SELECT avatar_changed_at FROM nx_user WHERE id=#{userId} AND is_deleted=0 LIMIT 1")
    java.time.LocalDateTime avatarChangedAt(@Param("userId") Long userId);

    @Select("SELECT avatar_url FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1 FOR UPDATE")
    String currentAvatarForUpdate(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user
               SET avatar_url=#{avatarObjectKey},avatar_changed_at=NOW(),updated_at=NOW()
             WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0
               AND avatar_url <=> #{expectedAvatarObjectKey}
            """)
    int updateAvatarObjectKey(
            @Param("userId") Long userId,
            @Param("avatarObjectKey") String avatarObjectKey,
            @Param("expectedAvatarObjectKey") String expectedAvatarObjectKey);

    @Update("""
            UPDATE nx_user
               SET language=#{language},updated_at=NOW()
             WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0
            """)
    int updateLanguage(@Param("userId") Long userId, @Param("language") String language);
}
