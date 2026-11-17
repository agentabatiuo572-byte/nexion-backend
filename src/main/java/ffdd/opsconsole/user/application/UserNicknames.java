package ffdd.opsconsole.user.application;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * System-generated display names, the only nicknames an account can hold: "{Adjective} {Noun} {suffix}",
 * e.g. "Azure Falcon K7". The suffix always mixes letters and digits, so a name never reads as a serial
 * number, and keeps names unique together with uk_user_nickname. Pools avoid product terms (Node, Box,
 * Rack, Share, Grid), official-sounding words (UVEL, Support, Nova) and money words.
 */
public final class UserNicknames {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> ADJECTIVES = List.of(
            "Swift", "Cosmic", "Lunar", "Solar", "Stellar", "Astro", "Hyper", "Turbo",
            "Photon", "Quantum", "Nebula", "Zenith", "Vector", "Ember", "Atlas", "Radiant",
            "Polar", "Arctic", "Amber", "Azure", "Cobalt", "Crimson", "Silver", "Indigo",
            "Violet", "Scarlet", "Jade", "Onyx", "Neon", "Sonic", "Rapid", "Bold",
            "Brave", "Calm", "Clever", "Bright", "Silent", "Steady", "Nimble", "Agile",
            "Keen", "Vivid", "Misty", "Frosty", "Sunny", "Velvet", "Crystal", "Mellow");
    private static final List<String> NOUNS = List.of(
            "Rover", "Pilot", "Falcon", "Orbit", "Beacon", "Vertex", "Ranger", "Comet",
            "Spark", "Drift", "Harbor", "Summit", "Voyager", "Nomad", "Scout", "Sailor",
            "Glider", "Hawk", "Eagle", "Raven", "Fox", "Wolf", "Lynx", "Otter",
            "Panda", "Tiger", "Condor", "Heron", "Owl", "Phoenix", "Meteor", "Quasar",
            "Pulsar", "Galaxy", "Aurora", "Horizon", "Canyon", "River", "Forest", "Island",
            "Glacier", "Breeze", "Thunder", "Cascade", "Lantern", "Compass", "Anchor", "Arrow");
    private static final Set<String> ALLOWED_ADJECTIVES = Set.copyOf(ADJECTIVES);
    private static final Set<String> ALLOWED_NOUNS = Set.copyOf(NOUNS);
    /** Letters without I and O, digits without 0 and 1: nothing that reads as another symbol. */
    private static final String SYMBOLS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern SUFFIX = Pattern.compile("(?=.*[A-Z])(?=.*[2-9])[A-HJ-NP-Z2-9]{2,3}");
    /** 2-character suffixes give about 880k names; after this many taken picks a 3-character one is used. */
    private static final int SHORT_SUFFIX_ATTEMPTS = 8;
    private static final int MAX_ATTEMPTS = 16;

    private UserNicknames() {}

    /** A name {@code taken} reports free; the unique index settles a concurrent pick of the same name. */
    public static String next(Predicate<String> taken) {
        return next(taken, Set.of());
    }

    /** {@code count} distinct free names. */
    public static List<String> candidates(int count, Predicate<String> taken) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        while (names.size() < count) names.add(next(taken, names));
        return List.copyOf(names);
    }

    /** Whether {@code value} has the generated shape, i.e. a user may pick it. */
    public static boolean isGenerated(String value) {
        if (value == null) return false;
        String[] parts = value.split(" ");
        return parts.length == 3 && ALLOWED_ADJECTIVES.contains(parts[0]) && ALLOWED_NOUNS.contains(parts[1])
                && SUFFIX.matcher(parts[2]).matches();
    }

    private static String next(Predicate<String> taken, Set<String> excluded) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String name = ADJECTIVES.get(RANDOM.nextInt(ADJECTIVES.size())) + " "
                    + NOUNS.get(RANDOM.nextInt(NOUNS.size())) + " "
                    + suffix(attempt < SHORT_SUFFIX_ATTEMPTS ? 2 : 3);
            if (!excluded.contains(name) && !taken.test(name)) return name;
        }
        throw new IllegalStateException("USER_NICKNAME_SPACE_EXHAUSTED");
    }

    private static String suffix(int length) {
        while (true) {
            StringBuilder suffix = new StringBuilder(length);
            for (int i = 0; i < length; i++) suffix.append(SYMBOLS.charAt(RANDOM.nextInt(SYMBOLS.length())));
            if (SUFFIX.matcher(suffix).matches()) return suffix.toString();
        }
    }
}
