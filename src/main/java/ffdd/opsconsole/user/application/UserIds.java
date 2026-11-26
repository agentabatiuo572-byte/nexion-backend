package ffdd.opsconsole.user.application;

import java.security.SecureRandom;
import java.util.function.LongPredicate;

/**
 * Primary keys of new nx_user rows, which the App shows as the UID, are random and unused. Allocation starts
 * at nine digits and grows by one digit after {@link #ATTEMPTS_PER_LENGTH} consecutive occupied picks,
 * up to twelve digits. The primary key settles a concurrent pick of the same id. Sort by created_at to get
 * registration order.
 */
public final class UserIds {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MIN_DIGITS = 9;
    private static final int MAX_DIGITS = 12;
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
