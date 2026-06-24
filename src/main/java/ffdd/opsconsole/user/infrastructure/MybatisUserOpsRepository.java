package ffdd.opsconsole.user.infrastructure;


import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.user.domain.UserAccountListEntryView;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentView;
import ffdd.opsconsole.user.domain.User360Seed;
import ffdd.opsconsole.user.domain.UserImpersonationSessionView;
import ffdd.opsconsole.user.domain.UserNotificationView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.domain.UserSecurityUserRow;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.domain.UserTeamMemberView;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentQueryRequest;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisUserOpsRepository implements UserOpsRepository {
    private final UserOpsMapper mapper;
    private static final List<User360Seed> ACCOUNT_ACTION_SEEDS = List.of(
            accountActionSeed("usr_8807", "usr_8807", "Marcus Ray", "1", "2025558807", "marcus.ray@example.invalid",
                    "PENDING", "L3", "V4", "RESTRICTED", "US", "4080.50", "186000.00", "920.00",
                    "14820.00", "6200.00", "1340.00", "17.20", "860.00", 5, 12, 82, true),
            accountActionSeed("usr_6201", "usr_6201", "Yuki Chen", "81", "0905556201", "yuki.chen@example.invalid",
                    "APPROVED", "L4", "V6", "FROZEN", "JP", "1280.00", "72000.00", "0.00",
                    "18400.00", "9800.00", "2100.00", "0.00", "0.00", 3, 18, 78, true),
            accountActionSeed("usr_2231", "usr_2231", "Sofia Park", "82", "0105552231", "sofia.park@example.invalid",
                    "APPROVED", "L2", "V2", "ACTIVE", "KR", "760.20", "34000.00", "120.00",
                    "5200.00", "2100.00", "640.00", "6.30", "320.00", 2, 5, 35, false),
            accountActionSeed("usr_55B1", "usr_55B1", "Noah White", "44", "0715555501", "noah.white@example.invalid",
                    "PENDING", "L2", "V3", "FROZEN", "UK", "520.00", "41000.00", "300.00",
                    "6200.00", "2500.00", "890.00", "0.00", "0.00", 2, 7, 74, true),
            accountActionSeed("usr_31E8", "usr_31E8", "Ava Miller", "1", "2025553188", "ava.miller@example.invalid",
                    "APPROVED", "L5", "V8", "ACTIVE", "US", "9320.10", "420000.00", "0.00",
                    "32800.00", "18000.00", "2400.00", "24.10", "1180.00", 6, 32, 24, true),
            accountActionSeed("usr_4410", "usr_4410", "Ethan Lin", "65", "81234410", "ethan.lin@example.invalid",
                    "APPROVED", "L4", "V7", "ACTIVE", "SG", "7420.00", "256000.00", "0.00",
                    "21800.00", "15800.00", "1600.00", "18.40", "930.00", 4, 24, 28, true),
            accountActionSeed("usr_0099", "usr_0099", "Omar Khan", "971", "0555550099", "omar.khan@example.invalid",
                    "REJECTED", "L1", "V1", "BANNED", "AE", "90.00", "1500.00", "0.00",
                    "420.00", "300.00", "120.00", "0.00", "0.00", 1, 0, 91, false),
            accountActionSeed("usr_90F0", "usr_90F0", "Mia Costa", "55", "1195559000", "mia.costa@example.invalid",
                    "APPROVED", "L3", "V5", "ACTIVE", "BR", "1780.00", "98000.00", "0.00",
                    "11200.00", "7600.00", "900.00", "11.20", "570.00", 3, 11, 31, true));
    private static final List<User360Seed> ASSET_ADJUSTMENT_EXTRA_ACCOUNT_SEEDS = List.of(
            accountActionSeed("usr_84F2", "usr_84F2", "Lina Zhao", "1", "2025557741", "lina.zhao@example.invalid",
                    "APPROVED", "L4", "V6", "ACTIVE", "US", "2840.00", "132000.00", "0.00",
                    "19600.00", "9800.00", "1400.00", "14.20", "720.00", 4, 18, 42, true),
            accountActionSeed("usr_02A9", "usr_02A9", "Iris Wang", "86", "1385550029", "iris.wang@example.invalid",
                    "APPROVED", "L2", "V3", "ACTIVE", "CN", "680.00", "24000.00", "0.00",
                    "4200.00", "1800.00", "320.00", "4.80", "230.00", 2, 6, 38, false));
    private static final List<KycLedgerSeed> KYC_LEDGER_SEEDS = List.of(
            new KycLedgerSeed(accountActionSeed("usr_77D4", "usr_77D4", "Harper Stone", "1", "2025557704", "harper.stone@example.invalid",
                    "PENDING", "L2", "V3", "ACTIVE", "US", "980.00", "56000.00", "80.00",
                    "7200.00", "3180.00", "900.00", "7.40", "370.00", 2, 9, 48, true),
                    "TBn8SeedKycAddress000000000000000001p"),
            new KycLedgerSeed(accountActionSeed("usr_31E8", "usr_31E8", "Ava Miller", "1", "2025553188", "ava.miller@example.invalid",
                    "APPROVED", "L5", "V8", "ACTIVE", "US", "9320.10", "420000.00", "0.00",
                    "32800.00", "18000.00", "2400.00", "24.10", "1180.00", 6, 32, 24, true),
                    "TR7NSeedKycAddress00000000000000000f2"),
            new KycLedgerSeed(accountActionSeed("usr_2231", "usr_2231", "Sofia Park", "82", "0105552231", "sofia.park@example.invalid",
                    "APPROVED", "L2", "V2", "ACTIVE", "KR", "760.20", "34000.00", "120.00",
                    "5200.00", "2100.00", "640.00", "6.30", "320.00", 2, 5, 35, false),
                    "bc1qseedkycaddress0000000000000000007e"),
            new KycLedgerSeed(accountActionSeed("usr_55B1", "usr_55B1", "Noah White", "44", "0715555501", "noah.white@example.invalid",
                    "NONE", "L2", "V3", "ACTIVE", "UK", "520.00", "41000.00", "300.00",
                    "6200.00", "2500.00", "890.00", "0.00", "0.00", 2, 7, 74, true),
                    null),
            new KycLedgerSeed(accountActionSeed("usr_90F0", "usr_90F0", "Mia Costa", "55", "1195559000", "mia.costa@example.invalid",
                    "APPROVED", "L3", "V5", "ACTIVE", "BR", "1780.00", "98000.00", "0.00",
                    "11200.00", "7600.00", "900.00", "11.20", "570.00", 3, 11, 31, true),
                    "TQxmSeedKycAddress000000000000000009c"));
    private static final List<User360Seed> SECURITY_SESSION_SEEDS = List.of(
            accountActionSeed("usr_2231", "usr_2231", "Sofia Park", "82", "0105552231", "sofia.park@example.invalid",
                    "APPROVED", "L2", "V2", "ACTIVE", "KR", "760.20", "34000.00", "120.00",
                    "5200.00", "2100.00", "640.00", "6.30", "320.00", 2, 5, 35, true),
            accountActionSeed("usr_8807", "usr_8807", "Marcus Ray", "1", "2025558807", "marcus.ray@example.invalid",
                    "PENDING", "L3", "V4", "RESTRICTED", "US", "4080.50", "186000.00", "920.00",
                    "14820.00", "6200.00", "1340.00", "17.20", "860.00", 5, 12, 82, true),
            accountActionSeed("usr_3315", "usr_3315", "Elena Novak", "49", "0155553315", "elena.novak@example.invalid",
                    "APPROVED", "L3", "V5", "ACTIVE", "DE", "2380.00", "118000.00", "260.00",
                    "13400.00", "8100.00", "940.00", "13.60", "690.00", 4, 15, 68, false));

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalUsers", mapper.countUsers());
        overview.put("activeUsers", mapper.countActiveUsers());
        overview.put("kycPending", mapper.countKycPending());
        overview.put("frozenUsers", mapper.countFrozenUsers());
        overview.put("activeSessions", mapper.countActiveSessions());
        return overview;
    }

    @Override
    public List<UserAccountView> search(String keyword, String status, String kycStatus, int limit) {
        return mapper.pageUsers(trim(keyword), statusList(status), normalize(kycStatus), null, 0, cappedLimit(limit));
    }

    @Override
    public PageResult<UserAccountView> pageProfiles(UserQueryRequest request) {
        String keyword = trim(request == null ? null : request.keyword());
        List<String> statuses = statusList(request == null ? null : request.status());
        String kycStatus = normalize(request == null ? null : request.kycStatus());
        Integer riskMin = normalizeRiskMin(request == null ? null : request.riskMin());
        int pageNum = page(request == null ? null : request.pageNum());
        int pageSize = pageSize(request == null ? null : request.pageSize());
        int offset = (pageNum - 1) * pageSize;
        long total = mapper.countUsersByQuery(keyword, statuses, kycStatus, riskMin);
        List<UserAccountView> records = total == 0
                ? List.of()
                : mapper.pageUsers(keyword, statuses, kycStatus, riskMin, offset, pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public long countByKycStatus(String kycStatus) {
        return mapper.countByKycStatus(kycStatus);
    }

    @Override
    public Optional<UserAccountView> findById(Long userId) {
        return Optional.ofNullable(mapper.findById(userId));
    }

    @Override
    public Optional<String> findWalletAddressByUserId(Long userId) {
        return Optional.ofNullable(mapper.findWalletAddressByUserId(userId)).filter(value -> !value.isBlank());
    }

    @Override
    public Optional<Long> findUserIdByLookupKey(String lookupKey) {
        return Optional.ofNullable(mapper.findUserIdByLookupKey(trim(lookupKey)));
    }

    @Override
    public void upsertUser360Seed(User360Seed seed) {
        mapper.createSeedRiskScoreUserTable();
        mapper.createSeedRiskScoreContributionTable();
        mapper.upsertSeedUser(seed);
        Long userId = mapper.findUserIdByLookupKey(seed.referralCode());
        if (userId == null) {
            throw new IllegalStateException("C1_USER360_SEED_NOT_FOUND_AFTER_UPSERT");
        }
        String userNo = userNo(userId);
        mapper.upsertSeedProfile(userId, seed);
        mapper.upsertSeedSecurity(userId, seed);
        mapper.upsertSeedWallet(userId, seed);
        mapper.upsertSeedSession(userId, seed);
        mapper.upsertSeedDeposit(userId, seed);
        if (positive(seed.withdrawnUsd())) {
            mapper.upsertSeedWithdrawal(userId, seed);
        }
        mapper.upsertSeedLedger(userId, seed, "SEED-LEDGER-" + seed.lookupKey() + "-DEP", "DEPOSIT", "USDT",
                "IN", money(seed.depositedUsd()), money(seed.walletUsdt()), "C1 seed deposit");
        if (positive(seed.withdrawnUsd())) {
            mapper.upsertSeedLedger(userId, seed, "SEED-LEDGER-" + seed.lookupKey() + "-WD", "WITHDRAWAL", "USDT",
                    "OUT", money(seed.withdrawnUsd()), money(seed.walletUsdt()), "C1 seed withdrawal");
        }
        mapper.upsertSeedLedger(userId, seed, "SEED-LEDGER-" + seed.lookupKey() + "-EARN", "EARNING", "NEX",
                "IN", money(seed.dailyNex()).max(BigDecimal.ONE), money(seed.walletNex()), "C1 seed compute earning");
        int deviceCount = Math.max(0, Math.min(seed.deviceCount() == null ? 0 : seed.deviceCount(), 12));
        BigDecimal deviceDivisor = BigDecimal.valueOf(Math.max(deviceCount, 1));
        for (int i = 1; i <= deviceCount; i++) {
            String instanceNo = "SEED-" + seed.lookupKey() + "-D" + i;
            BigDecimal dailyUsdt = money(seed.dailyUsdt()).divide(deviceDivisor, 6, RoundingMode.HALF_UP);
            BigDecimal dailyNex = money(seed.dailyNex()).divide(deviceDivisor, 6, RoundingMode.HALF_UP);
            mapper.upsertSeedDevice(userId, seed, i, instanceNo, dailyUsdt, dailyNex,
                    BigDecimal.valueOf(80L + (long) i * 7L));
            mapper.upsertSeedDeviceRuntime(instanceNo, seed.region());
        }
        int teamSize = Math.max(0, Math.min(seed.teamSize() == null ? 0 : seed.teamSize(), 50));
        long memberBase = memberBase(seed.lookupKey());
        if (teamSize > 0) {
            mapper.deleteSeedTeamMembers(userId, memberBase + 1L, memberBase + teamSize);
        }
        for (int i = 1; i <= teamSize; i++) {
            long memberUserId = memberBase + i;
            mapper.upsertSeedTeamMember(
                    userId,
                    memberUserId,
                    userNo(memberUserId),
                    seed.nickname() + " Team " + i,
                    seed.vRank(),
                    i <= Math.max(1, teamSize / 3) ? 1 : 2,
                    money(seed.depositedUsd()).divide(BigDecimal.valueOf(Math.max(teamSize, 1)), 2, RoundingMode.HALF_UP));
        }
        mapper.upsertSeedNotification(userId, seed);
        mapper.upsertSeedRiskScore(userNo, seed.riskScore() == null ? 0 : seed.riskScore());
        mapper.upsertSeedRiskScoreContribution(userNo, "账户行为", "C1 seed user profile", Math.min(seed.riskScore() == null ? 0 : seed.riskScore(), 45), 10);
    }

    @Override
    public void upsertAccountActionSeeds() {
        ACCOUNT_ACTION_SEEDS.forEach(this::upsertUser360Seed);

        Long flaggedUserId = requireSeedUserId("usr_8807");
        Long frozenBatchUserId = requireSeedUserId("usr_6201");
        Long activeSessionUserId = requireSeedUserId("usr_2231");
        Long manualFrozenUserId = requireSeedUserId("usr_55B1");
        Long trustUserId = requireSeedUserId("usr_31E8");
        Long longTrustUserId = requireSeedUserId("usr_4410");
        Long blockedUserId = requireSeedUserId("usr_0099");
        Long pastImpersonationUserId = requireSeedUserId("usr_90F0");

        mapper.upsertSeedUserSession(flaggedUserId, "SEED-SESSION-usr_8807", "Chrome Windows · Risk desk replay", "10.31.8.7", 7, 8);
        mapper.upsertSeedUserSession(flaggedUserId, "C2-SESSION-usr_8807-02", "iPhone 15 · Nexion App", "10.31.8.19", 3, 34);
        mapper.upsertSeedUserSession(activeSessionUserId, "SEED-SESSION-usr_2231", "Chrome macOS · Seoul", "10.22.3.31", 7, 4);
        mapper.upsertSeedUserSession(activeSessionUserId, "C2-SESSION-usr_2231-02", "Android · Pixel 8", "10.22.3.42", 5, 22);
        mapper.upsertSeedUserSession(activeSessionUserId, "C2-SESSION-usr_2231-03", "Safari iPad · Support case", "10.22.3.58", 2, 47);

        mapper.revokeUserSessions(frozenBatchUserId);
        mapper.revokeUserSessions(manualFrozenUserId);
        mapper.revokeUserSessions(blockedUserId);

        mapper.upsertAccountList(trustUserId, "ALLOW", "KYC and source-of-funds review passed for C2 smoke data", "superadmin",
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        mapper.upsertAccountList(longTrustUserId, "ALLOW", "Enterprise partner account reviewed by compliance", "superadmin", null);
        mapper.upsertAccountList(blockedUserId, "BLOCK", "Judicial assistance and signup/login block required", "superadmin", null);

        mapper.upsertSeedImpersonationSession("IMP-204", activeSessionUserId, "ACTIVE", 30, "cs_amy",
                "Support troubleshooting with read-only impersonation", LocalDateTime.now().plusMinutes(14),
                null, null, null, 16);
        mapper.upsertSeedImpersonationSession("IMP-198", pastImpersonationUserId, "TERMINATED", 30, "risklead_h",
                "Historical KYC reproduction session", LocalDateTime.now().minusMinutes(8),
                "risklead_h", "Support issue resolved", LocalDateTime.now().minusMinutes(8), 30);
    }

    @Override
    public void upsertKycLedgerSeeds() {
        KYC_LEDGER_SEEDS.forEach(seed -> {
            upsertUser360Seed(seed.user());
            Long userId = requireSeedUserId(seed.user().lookupKey());
            mapper.updateSeedProfileWalletAddress(userId, seed.walletAddress());
        });
    }

    @Override
    public void upsertAssetAdjustmentSeeds() {
        ACCOUNT_ACTION_SEEDS.forEach(this::upsertUser360Seed);
        ASSET_ADJUSTMENT_EXTRA_ACCOUNT_SEEDS.forEach(this::upsertUser360Seed);

        Long overCapUserId = requireSeedUserId("usr_84F2");
        Long trustedUserId = requireSeedUserId("usr_31E8");
        Long debitUserId = requireSeedUserId("usr_02A9");
        Long suspendedUserId = requireSeedUserId("usr_8807");
        Long historyUserId = requireSeedUserId("usr_2231");
        Long debitHistoryUserId = requireSeedUserId("usr_90F0");

        mapper.upsertSeedAssetAdjustment(
                "ADJ-7741", overCapUserId, "USDT", "CREDIT", new BigDecimal("1200.00"),
                "OPS_USER_ADJUSTMENT", "客服补偿 · 工单 #88213", "support_zhang",
                "PENDING_REVIEW", null, null, null, 12);
        mapper.upsertSeedAssetAdjustment(
                "ADJ-3188", trustedUserId, "USDT", "CREDIT", new BigDecimal("480.00"),
                "OPS_USER_ADJUSTMENT", "活动补发 · 工单 #88106", "growth_lee",
                "PENDING_REVIEW", null, null, null, 38);
        mapper.upsertSeedAssetAdjustment(
                "ADJ-0029", debitUserId, "USDT", "DEBIT", new BigDecimal("260.00"),
                "OPS_USER_ADJUSTMENT", "系统纠错 · 重复入账红冲", "finance_lin",
                "PENDING_REVIEW", null, null, null, 64);
        mapper.upsertSeedAssetAdjustment(
                "ADJ-1182", suspendedUserId, "USDT", "CREDIT", new BigDecimal("380.00"),
                "OPS_USER_ADJUSTMENT", "客服补偿 · 覆盖率红线挂起", "support_amy",
                "SUSPENDED", "risklead_h", "覆盖率低于红线,等待恢复后重审",
                LocalDateTime.now().minusDays(1), 140);
        mapper.upsertSeedAssetAdjustment(
                "ADJ-1183", historyUserId, "USDT", "CREDIT", new BigDecimal("120.00"),
                "OPS_USER_ADJUSTMENT", "客服补偿 · 充值延迟补偿", "support_amy",
                "APPROVED", "finance_wu", "凭证匹配,同意补偿",
                LocalDateTime.now().minusDays(2), 2880);
        mapper.upsertSeedAssetAdjustment(
                "ADJ-1179", debitHistoryUserId, "USDT", "DEBIT", new BigDecimal("35.00"),
                "OPS_USER_ADJUSTMENT", "系统纠错 · 小额重复入账", "finance_lin",
                "APPROVED", "finance_wu", "账单核对通过,执行红冲",
                LocalDateTime.now().minusDays(3), 4320);
        mapper.upsertSeedAssetAdjustment(
                "ADJ-1175", trustedUserId, "NEX", "CREDIT", new BigDecimal("1200.00"),
                "OPS_USER_ADJUSTMENT", "活动补发 · NEX 奖励补发", "growth_lee",
                "APPROVED", "finance_wu", "活动名单复核通过",
                LocalDateTime.now().minusDays(5), 7200);
    }

    @Override
    public void upsertSecuritySessionSeeds() {
        SECURITY_SESSION_SEEDS.forEach(this::upsertUser360Seed);

        Long activeUserId = requireSeedUserId("usr_2231");
        Long shortLockUserId = requireSeedUserId("usr_8807");
        Long longLockUserId = requireSeedUserId("usr_3315");

        mapper.updateSeedSecurityState(activeUserId, true, 1, 6);
        mapper.updateSeedSecurityState(shortLockUserId, true, 5, 18);
        mapper.updateSeedSecurityState(longLockUserId, false, 10, 42);
        mapper.markPasswordResetRequired(longLockUserId, "RESET_REQUIRED$C5_SECURITY_SEED");

        mapper.upsertSeedUserSession(activeUserId, "C5-SESSION-usr_2231-IOS", "iPhone 15 Pro · Nexion App", "10.22.3.31", 21, 4);
        mapper.upsertSeedUserSession(activeUserId, "C5-SESSION-usr_2231-WEB", "Chrome macOS · Seoul", "10.22.3.42", 14, 22);
        mapper.upsertSeedUserSession(activeUserId, "C5-SESSION-usr_2231-ANDROID", "Android Pixel 8 · Backup", "10.22.3.58", 5, 47);
        mapper.upsertSeedUserSession(shortLockUserId, "C5-SESSION-usr_8807-WEB", "Chrome Windows · Risk desk replay", "10.31.8.7", 3, 8);
        mapper.upsertSeedUserSession(longLockUserId, "C5-SESSION-usr_3315-IOS", "iPhone 14 · Berlin", "10.33.1.15", 2, 36);
    }

    @Override
    public Optional<UserSecurityStatusView> securityStatus(Long userId) {
        return Optional.ofNullable(mapper.securityStatus(userId));
    }

    @Override
    public List<UserSecurityUserRow> lockedSecurityUsers(
            int shortLockThreshold,
            int longLockThreshold,
            int shortLockMinutes,
            int longLockHours,
            int limit) {
        int normalizedShortThreshold = Math.max(shortLockThreshold, 1);
        int normalizedLongThreshold = Math.max(longLockThreshold, normalizedShortThreshold);
        return mapper.lockedSecurityUsers(
                normalizedShortThreshold,
                normalizedLongThreshold,
                Math.max(shortLockMinutes, 1),
                Math.max(longLockHours, 1),
                cappedLimit(limit));
    }

    @Override
    public List<UserSessionView> sessions(Long userId, int limit) {
        return mapper.sessions(userId, limit);
    }

    @Override
    public PageResult<UserSessionView> pageSessions(Long userId, int pageNum, int pageSize) {
        int normalizedPageNum = page(pageNum);
        int normalizedPageSize = pageSize(pageSize);
        long total = mapper.countSessions(userId);
        List<UserSessionView> records = total == 0
                ? List.of()
                : mapper.pageSessions(userId, (normalizedPageNum - 1) * normalizedPageSize, normalizedPageSize);
        return new PageResult<>(total, normalizedPageNum, normalizedPageSize, records);
    }

    @Override
    public List<UserTeamMemberView> teamMembers(Long userId, int limit) {
        return mapper.teamMembers(userId, cappedLimit(limit));
    }

    @Override
    public long countTeamMembers(Long userId) {
        return mapper.countTeamMembers(userId);
    }

    @Override
    public long countDirectTeamMembers(Long userId) {
        return mapper.countDirectTeamMembers(userId);
    }

    @Override
    public BigDecimal sumTeamVolume(Long userId) {
        BigDecimal volume = mapper.sumTeamVolume(userId);
        return volume == null ? BigDecimal.ZERO : volume;
    }

    @Override
    public List<UserNotificationView> notifications(Long userId, int limit) {
        return mapper.notifications(userId, cappedLimit(limit));
    }

    @Override
    public long countUnreadNotifications(Long userId) {
        return mapper.countUnreadNotifications(userId);
    }

    @Override
    public long countPendingNotifications(Long userId) {
        return mapper.countPendingNotifications(userId);
    }

    @Override
    public long countFailedNotifications(Long userId) {
        return mapper.countFailedNotifications(userId);
    }

    @Override
    public List<UserAccountListEntryView> accountLists(String status, int limit) {
        return mapper.accountLists(normalize(status), limit);
    }

    @Override
    public Optional<UserAccountListEntryView> findAccountList(Long userId) {
        return Optional.ofNullable(mapper.findAccountList(userId));
    }

    @Override
    public void upsertAccountList(Long userId, String kind, String reason, String operator, LocalDateTime expiresAt) {
        mapper.upsertAccountList(userId, kind, reason, operator, expiresAt);
    }

    @Override
    public void removeAccountList(Long userId, String reason, String operator) {
        mapper.removeAccountList(userId, reason, operator);
    }

    @Override
    public List<UserImpersonationSessionView> impersonations(int limit) {
        return mapper.impersonations(limit);
    }

    @Override
    public Optional<UserImpersonationSessionView> findImpersonation(String sessionNo) {
        return Optional.ofNullable(mapper.findImpersonation(trim(sessionNo)));
    }

    @Override
    public void terminateImpersonation(String sessionNo, String reason, String operator) {
        mapper.terminateImpersonation(trim(sessionNo), reason, operator);
    }

    @Override
    public void updateUserStatus(Long userId, String status, String reason) {
        mapper.updateUserStatus(userId, status);
    }

    @Override
    public void updateKycStatus(Long userId, String kycStatus, String reason) {
        mapper.updateKycStatus(userId, kycStatus);
    }

    @Override
    public Optional<UserSessionView> findSession(String refreshTokenId) {
        return Optional.ofNullable(mapper.findSession(refreshTokenId));
    }

    @Override
    public void revokeSession(String refreshTokenId, String reason) {
        mapper.revokeSession(refreshTokenId);
    }

    @Override
    public void revokeUserSessions(Long userId, String reason) {
        mapper.revokeUserSessions(userId);
    }

    @Override
    public void disableTwoFactor(Long userId) {
        mapper.disableTwoFactor(userId);
    }

    @Override
    public void markPasswordResetRequired(Long userId, String resetMarker) {
        mapper.markPasswordResetRequired(userId, resetMarker);
    }

    @Override
    public void resetLoginFailures(Long userId) {
        mapper.resetLoginFailures(userId);
    }

    @Override
    public void recordImpersonationSession(String sessionNo, Long userId, int ttlMinutes, String operator, String reason, LocalDateTime expiresAt) {
        mapper.insertImpersonationSession(sessionNo, userId, ttlMinutes, operator, reason, expiresAt);
    }

    @Override
    public void createAssetAdjustment(String adjustmentNo, Long userId, String asset, String direction, BigDecimal amount, String reason, String operator) {
        mapper.insertAssetAdjustment(adjustmentNo, userId, asset, direction, amount, reason, operator);
    }

    @Override
    public PageResult<UserAssetAdjustmentView> pageAssetAdjustments(UserAssetAdjustmentQueryRequest request) {
        String status = normalize(request == null ? null : request.status());
        String asset = normalize(request == null ? null : request.asset());
        Long userId = request == null ? null : request.userId();
        String keyword = trim(request == null ? null : request.keyword());
        Boolean historyOnly = request == null ? null : request.historyOnly();
        int pageNum = page(request == null ? null : request.pageNum());
        int pageSize = pageSize(request == null ? null : request.pageSize());
        int offset = (pageNum - 1) * pageSize;
        long total = mapper.countAssetAdjustments(status, asset, userId, keyword, historyOnly);
        List<UserAssetAdjustmentView> records = total == 0
                ? List.of()
                : mapper.pageAssetAdjustments(status, asset, userId, keyword, historyOnly, offset, pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<UserAssetAdjustmentView> findAssetAdjustment(String adjustmentNo) {
        return Optional.ofNullable(mapper.findAssetAdjustment(trim(adjustmentNo)));
    }

    @Override
    public void reviewAssetAdjustment(String adjustmentNo, String status, String checker, String reason) {
        mapper.reviewAssetAdjustment(adjustmentNo, status, checker, reason);
    }

    private String normalize(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private String userNo(Long userId) {
        return "U" + String.format("%08d", userId);
    }

    private Long requireSeedUserId(String lookupKey) {
        Long userId = mapper.findUserIdByLookupKey(lookupKey);
        if (userId == null) {
            throw new IllegalStateException("C2_ACCOUNT_ACTION_SEED_NOT_FOUND_AFTER_UPSERT:" + lookupKey);
        }
        return userId;
    }

    private static User360Seed accountActionSeed(
            String lookupKey,
            String referralCode,
            String nickname,
            String countryCode,
            String phone,
            String email,
            String kycStatus,
            String userLevel,
            String vRank,
            String accountStatus,
            String region,
            String walletUsdt,
            String walletNex,
            String pendingWithdraw,
            String lifetimeEarned,
            String depositedUsd,
            String withdrawnUsd,
            String dailyUsdt,
            String dailyNex,
            Integer deviceCount,
            Integer teamSize,
            Integer riskScore,
            Boolean twoFactorEnabled) {
        return new User360Seed(
                lookupKey,
                referralCode,
                nickname,
                countryCode,
                phone,
                email,
                kycStatus,
                userLevel,
                vRank,
                accountStatus,
                "zh-CN",
                region,
                new BigDecimal(walletUsdt),
                new BigDecimal(walletNex),
                new BigDecimal(pendingWithdraw),
                new BigDecimal(lifetimeEarned),
                new BigDecimal(depositedUsd),
                new BigDecimal(withdrawnUsd),
                new BigDecimal(dailyUsdt),
                new BigDecimal(dailyNex),
                deviceCount,
                teamSize,
                riskScore,
                twoFactorEnabled);
    }

    private long memberBase(String lookupKey) {
        int hash = Math.abs((lookupKey == null ? "seed" : lookupKey).hashCode() % 100_000);
        return 7_000_000L + hash * 100L;
    }

    private record KycLedgerSeed(User360Seed user, String walletAddress) {
    }

    private List<String> statusList(String value) {
        String trimmed = trim(value);
        if (trimmed == null) {
            return List.of();
        }
        return Arrays.stream(trimmed.split(","))
                .map(this::normalize)
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
    }

    private Integer normalizeRiskMin(Integer value) {
        if (value == null) {
            return null;
        }
        return Math.max(0, Math.min(value, 100));
    }

    private String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int page(Integer value) {
        return value == null || value < 1 ? 1 : value;
    }

    private int pageSize(Integer value) {
        int normalized = value == null || value < 1 ? 20 : value;
        return Math.min(normalized, 100);
    }

    private int cappedLimit(int limit) {
        return Math.min(Math.max(limit, 1), 100);
    }
}
