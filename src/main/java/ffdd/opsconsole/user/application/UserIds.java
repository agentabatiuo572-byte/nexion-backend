package ffdd.opsconsole.user.application;

import java.security.SecureRandom;
import java.util.function.LongPredicate;

/**
 * Primary keys of new nx_user rows, which the App shows as the UID: random, unused and at least six digits,
 * so they reveal neither the registration order nor the user count. Six-digit ids come first; once
 * {@link #ATTEMPTS_PER_LENGTH} picks in a row are taken, one more digit is used. The primary key settles a
 * concurrent pick of the same id. Ids no longer follow registration order: sort by created_at for that.
 */
public final class UserIds {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MIN_DIGITS = 6;
    /** User numbers are shown as "U" + 8 digits, and some displays keep only the last 8 digits. */
    private static final int MAX_DIGITS = 8;
    private static final int ATTEMPTS_PER_LENGTH = 8;

    private UserIds() {}

    public static long next(LongPredicate taken) {
        for (int digits = MIN_DIGITS; digits <= MAX_DIGITS; digits++) {
            long low = (long) Math.pow(10, digits - 1);
            for (int attempt = 0; attempt < ATTEMPTS_PER_LENGTH; attempt++) {
                long id = RANDOM.nextLong(low, low * 10);
                if (!taken.test(id)) return id;
            }
        }
        throw new IllegalStateException("USER_ID_SPACE_EXHAUSTED");
    }
}
