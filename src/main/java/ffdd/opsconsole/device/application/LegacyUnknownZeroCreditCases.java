package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ffdd.opsconsole.device.dto.LegacyUnknownZeroCreditProof.JournalFingerprint;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** One reviewed incident, deliberately independent of every existing replay whitelist. */
public final class LegacyUnknownZeroCreditCases {
    public static final String OP = "e1_legacy_unknown_zero_credit_cancel";
    public static final long OWNER = 60723153007L;
    public static final String BUDGET_SHA = "9259dc3133ca87335ca12f565f3c1b7e6a4d0a646937e1e00c2d43696d9c2994";
    public record Case(String caseId, long ownerId, long deviceId, String instanceNo, String taskNo,
            String taskConfigId, String executorId, String requestKeyHash, String requestBodyHash, long requestGeneration,
            String inputHash, String modelTag, String modelDigest, String nextKeyHash,
            JournalFingerprint journal) { }
    private static Case incident(long device, String instance, String task, String body,
            String binding, String state, String calls, String content) {
        return new Case("V1-UNKNOWN-20261101-" + device, OWNER, device, instance, task, "E2-20260924-EM", "fleet-" + device,
                sha("BGE-COMPLETE-" + sha(task)), body, 1,
                "400cf267a947b6e8bf374d1ea61c6013391618505a7b934a16b099853a8fcc6a","bge-m3:567m",
                "sha256:7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab",switch ((int)device) {
                    case 1208 -> "d735fe00b9342997186d96eaad70bd794b3885accbbb68c1cde01764135617b7";
                    case 1209 -> "3b356759c54ecd439422730b6e5d7bc4de3b5d6dbf41697b18d3c76ba84c0f4c";
                    case 1212 -> "adb9c5bf453322d0d1e4a19e2d6102fc0d1fd0feae0932fe8cb12206a529b292";
                    case 1214 -> "d8d1fef4c196259f7940a0b6bff7fa3ff4c65829411bfb7d00f6e39bcf83edff";
                    default -> throw new IllegalArgumentException();
                },
                new JournalFingerprint(device, binding, state, calls, content));
    }
    public static final List<Case> ALL = List.of(
        incident(1208,"NEX-ORD-AA102D940A5C447696E538F5","CTA-F55257583F6F4681975DB2EF6C0B3F15",
            "1f038331bb5e0550cf8833955a004091aecc3521588981db0be796f15b31b2f9",
            "ebe5eff5de7bc30daa8fae941e35716f518ba35ece7b2d12a07b2e93884d777c",
            "1f3d1c076fb7ece8a40c873c25d69e11d4a20eb8af53ae22efff63e7e15aeb7d",
            "321026ca9eb28e300a9dc732215e9842ff89e3a89ab0dfaa20473c27b7777968",
            "c591625bad4f297d9ed0cc36a65614ef2a9baf527c0a2eb4d47690dd110d891b"),
        incident(1209,"NEX-ORD-5A61BFA35341348072293238","CTA-2B6C43B79E6A480ABDF932054539AC13",
            "7e0113fd1705d670d629c8f3f35a8ba875cc788d0ea34bb308c4344842ee2ee6",
            "f40d3936ae9fe751a67b116f94d9eeb2693f93557e3e5af80f82bdcfe3f632e1",
            "444eb552b2effed2f55eec5e726287ac7d10c454d5eea8a770a9dc1bbcafe27d",
            "1e80c1199fd2289f70ab035178f89e8299d957804387fcf70388d5a9feb2fbd7",
            "ac4c3b4d73d3a16f47385785fa8727d8b62c04d079e6f73f57b044499d624393"),
        incident(1212,"DEV-TI-ADFCB790173045B89BC7E90F1058F275","CTA-BE61708B7DDA4BFF9F624A4647BD5035",
            "45411e460adff6e23f595f016b227e5f9f89b86063ff57202af7a2ce567d9f80",
            "997846632a82a1da2bf128b8006de786999f2acb97f1b6004018ba6621709167",
            "1ade2cab426f06d5ba6b6603325246f47aacdfc51deaad8b430ea4a15f060225",
            "117615c69cfff4e743a7ac0d55174adafe60a7933442250a9f6f54720d61bc05",
            "263cef4715a4f31461ff838fbebe91db85de30b369266f9f1a88a0936a208b8c"),
        incident(1214,"DEV-CPR-A70B39752516410385509B908766F165","CTA-ED3763A8C79042AE9EC86FA8AACD3FDE",
            "2fa8edcb4ec17ffc67ae91b55751c689e19844cdf8175b07da11905cb5a274ee",
            "c0bcfafe54b8678b463292a1715223d15d71407a936bb728d00500153789695c",
            "f4a0777fa9c53c1401a2252d0ae5daa10001b96e724164b90ade722cbac0dff8",
            "bfb050b7cd8d4d9e45434c06a390159b22300becbba15cf2accf22f5ebe22355",
            "687ab70aa8363626cf0a7f49c8c1e6f82d45e6341138b7a1b9d895a4b20037f7"));
    public static final String MANIFEST_JSON;
    public static final String MANIFEST_SHA;
    static {
        try {
            MANIFEST_JSON = JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                    .build().writeValueAsString(ALL);
            MANIFEST_SHA = sha(MANIFEST_JSON);
        } catch (Exception invalid) { throw new ExceptionInInitializerError(invalid); }
    }
    public static Case require(long device) {
        return ALL.stream().filter(c -> c.deviceId() == device).findFirst()
                .orElseThrow(() -> new BizException(409,"LEGACY_UNKNOWN_CASE_NOT_AUTHORIZED"));
    }
    public static boolean contains(long device) { return ALL.stream().anyMatch(c -> c.deviceId() == device); }
    public static String sha(String value) { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    public static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private LegacyUnknownZeroCreditCases() { }
}
