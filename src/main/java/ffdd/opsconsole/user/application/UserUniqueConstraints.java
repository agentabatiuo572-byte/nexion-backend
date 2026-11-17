package ffdd.opsconsole.user.application;

import java.sql.SQLException;
import org.springframework.dao.DuplicateKeyException;

public final class UserUniqueConstraints {
    private UserUniqueConstraints() {}

    public static boolean isGeneratedIdentityCollision(DuplicateKeyException collision) {
        return isDuplicateKey(collision, "PRIMARY") || isNicknameCollision(collision);
    }

    public static boolean isNicknameCollision(DuplicateKeyException collision) {
        return isDuplicateKey(collision, "uk_user_nickname");
    }

    private static boolean isDuplicateKey(DuplicateKeyException collision, String key) {
        if (!(collision.getMostSpecificCause() instanceof SQLException sql) || sql.getErrorCode() != 1062) {
            return false;
        }
        // MySQL's named duplicate error quotes the value, followed by the key name.
        String message = sql.getMessage();
        if (message == null) return false;
        int keyEnd = message.lastIndexOf('\'');
        int keyStart = message.lastIndexOf('\'', keyEnd - 1);
        if (keyStart <= message.indexOf('\'')) return false;
        String keyName = message.substring(keyStart + 1, keyEnd);
        return keyName.equals(key) || keyName.equals("nx_user." + key);
    }
}
