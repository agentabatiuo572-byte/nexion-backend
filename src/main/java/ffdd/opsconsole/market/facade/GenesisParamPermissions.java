package ffdd.opsconsole.market.facade;

import java.util.Map;

/** Exact G4 parameter grants shared by direct writes and A2 approval. */
public final class GenesisParamPermissions {
    private static final Map<String, String> BY_PARAM = Map.ofEntries(
            Map.entry("supply", "finprod_g4_write"),
            Map.entry("price", "finprod_g4_price_write"),
            Map.entry("dividend", "finprod_g4_dividend_rate_write"),
            Map.entry("royalty", "finprod_g4_royalty_write"),
            Map.entry("airdropPct", "finprod_g4_airdrop_pct_write"),
            Map.entry("emissionCurve", "finprod_g4_emission_curve_write"),
            Map.entry("airdropLockDays", "finprod_g4_airdrop_lock_days_write"),
            Map.entry("showcaseEnabled", "finprod_g4_write"),
            Map.entry("divBase", "finprod_g4_write"));

    private GenesisParamPermissions() {
    }

    public static String forKey(String key) {
        return BY_PARAM.get(key);
    }
}
