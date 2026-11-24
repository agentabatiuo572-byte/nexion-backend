package ffdd.opsconsole.team.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import ffdd.opsconsole.shared.config.StorageTime;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminCommandBusinessRejection;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class VRankSalaryService {
    public static final String POLICY_FIELD = "monthlySalaryUsdt";
    public static final String KIND = "vrank_salary";
    private static final BusinessCalendar.Track CALENDAR = BusinessCalendar.growthTeam();

    private final JdbcTemplate db;
    private final AdminIdempotencyService idempotency;
    private final TeamCommissionRepository ranks;
    private final TreasuryCoverageFacade coverage;
    private final TreasuryLedgerPostingFacade ledger;

    public record ClaimRequest(String month, String amountUsdt) {}
    public record Receipt(String status, String month, String rankCode, String amountUsdt,
                          String claimNo, String rejectionCode, int httpStatus) {
        static Receipt state(String status) {
            return new Receipt(status, null, null, null, null, null, 0);
        }

        static Receipt rejected(YearMonth month, int httpStatus, String code, String currentAmount) {
            return new Receipt("REJECTED", month.toString(), null, currentAmount, null, code, httpStatus);
        }
    }

    public record Salary(LocalDate businessDate, List<MonthView> months, Upcoming upcoming,
                         Instant nextClaimableFrom) {}
    public record MonthView(YearMonth month, String rankCode, String amountUsdt, String status,
                            Instant claimableFrom, Instant expiresAt, String claimNo, Instant claimedAt) {}
    public record Upcoming(YearMonth month, String rankCode, String amountUsdt, Instant claimableFrom) {}
    public record AdminClaim(long cursor, long userId, String userNo, String nickname, String month, String rankCode,
                             String amountUsdt, String claimNo, String billId, Instant claimedAt) {}
    public record AdminClaims(List<AdminClaim> items, Long nextCursor) {}

    private record Reached(String rank, BigDecimal amount, LocalDateTime opens) {}
    private record Month(Reached open, Reached upcoming, LocalDateTime expires) {}
    private record Paid(String rank, BigDecimal amount, String number, LocalDateTime opens, LocalDateTime paidAt) {}

    public static BigDecimal parseAmount(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,12}(\\.[0-9]{1,6})?")) {
            throw new BizException(422, "VRANK_SALARY_AMOUNT_INVALID");
        }
        return new BigDecimal(raw);
    }

    public ApiResult<Receipt> claim(long user, String key, ClaimRequest request) {
        key = requireKey(key);
        if (request == null) throw new BizException(422, "VRANK_SALARY_REQUEST_REQUIRED");
        YearMonth month = parseMonth(request.month());
        BigDecimal confirmed = parseAmount(request.amountUsdt());
        // The confirmed amount may change on a new key. The original month alone binds this key.
        Receipt receipt;
        try {
            receipt = idempotency.executeRetainedRepeatableRead(scope(user), key, month.toString(),
                    Receipt.class, () -> executeClaim(user, month, confirmed));
        } catch (AdminCommandBusinessRejection failure) {
            receipt = (Receipt) failure.response();
        }
        return "REJECTED".equals(receipt.status())
                ? ApiResult.fail(receipt.httpStatus(), receipt.rejectionCode(), receipt)
                : ApiResult.ok(receipt);
    }

    public Receipt receipt(long user, String key) {
        String scope = scope(user);
        key = requireKey(key);
        String hash = idempotency.retainedRequestHash(scope, key);
        if (hash == null) return Receipt.state("NOT_FOUND");
        var found = idempotency.recoveryResult(scope, key, hash, Receipt.class);
        return switch (found.status()) {
            case SUCCEEDED -> found.response();
            case FAILED -> {
                if (found.response() != null) yield found.response();
                throw new BizException(503, "SALARY_RECEIPT_UNAVAILABLE");
            }
            case PROCESSING -> Receipt.state("PROCESSING");
            case NOT_FOUND -> Receipt.state("NOT_FOUND");
            default -> throw new BizException(503, "SALARY_RECEIPT_UNAVAILABLE");
        };
    }

    private Receipt executeClaim(long user, YearMonth month, BigDecimal confirmed) {
        // Lock before taking the repeatable-read snapshot. Different keys for the same user serialize.
        db.queryForObject("SELECT id FROM nx_user WHERE id=? FOR UPDATE", Long.class, user);
        LocalDateTime now = LocalDateTime.now(StorageTime.ZONE);
        LocalDate today = CALENDAR.dateAtStorage(now);
        YearMonth current = YearMonth.from(today);
        if (!month.equals(current) && !(today.getDayOfMonth() == 1 && month.equals(current.minusMonths(1)))) {
            return Receipt.rejected(month, 409, "SALARY_NOT_CLAIMABLE", null);
        }
        if (paid(user, month) != null) return Receipt.rejected(month, 409, "SALARY_ALREADY_CLAIMED", null);
        Month period = eligibility(user, month, now, activation());
        Reached open = period == null ? null : period.open();
        if (open == null || open.amount().signum() <= 0 || !now.isBefore(period.expires())) {
            return Receipt.rejected(month, 409, "SALARY_NOT_CLAIMABLE", null);
        }
        if (confirmed.compareTo(open.amount()) != 0) {
            return Receipt.rejected(month, 409, "SALARY_AMOUNT_CHANGED", amount(open.amount()));
        }
        var error = coverageError(coverage);
        if (error != null) {
            return Receipt.rejected(month, error.httpStatus(), error.name(), null);
        }
        String number = "SAL-" + UUID.randomUUID();
        try {
            Long event = ranks.insertCommissionEvent(user, KIND, null, "USDT", open.amount(), BigDecimal.ZERO,
                    "UNLOCKED", 0, "V-Rank monthly salary | " + number);
            if (event == null) throw new IllegalStateException("VRANK_SALARY_EVENT_INSERT_FAILED");
            ledger.releaseCommissionFunds(event);
            db.update("""
                    INSERT INTO nx_v_rank_salary_claim
                      (user_id,salary_month,rank_code,amount_usdt,claim_no,commission_event_id,claimable_from,claimed_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, user, month.atDay(1), open.rank(), open.amount(), number, event, open.opens(), now);
        } catch (DataAccessException | IllegalStateException failure) {
            log.warn("Monthly salary credit failed for user {} and month {}", user, month, failure);
            Receipt rejected = Receipt.rejected(month, 503, "SALARY_CREDIT_UNAVAILABLE", null);
            throw new AdminCommandBusinessRejection(rejected.httpStatus(), rejected.rejectionCode(), rejected);
        }
        return new Receipt("SUCCEEDED", month.toString(), open.rank(), amount(open.amount()), number, null, 0);
    }

    static OpsErrorCode coverageError(TreasuryCoverageFacade coverage) {
        try {
            return OpsTeamService.fundPolicyCoverageError(coverage.snapshotIsolated());
        } catch (DataAccessException | CannotCreateTransactionException failure) {
            log.warn("Could not read coverage for monthly salary", failure);
            return OpsErrorCode.COVERAGE_UNAVAILABLE;
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Salary salary(long user, boolean sandbox) {
        LocalDateTime now = LocalDateTime.now(StorageTime.ZONE);
        LocalDate today = CALENDAR.dateAtStorage(now);
        if (sandbox) return new Salary(today, List.of(), null, null);
        LocalDateTime active = activation();
        YearMonth current = YearMonth.from(today);
        List<MonthView> months = new ArrayList<>(2);
        if (today.getDayOfMonth() == 1) {
            YearMonth previousMonth = current.minusMonths(1);
            Paid previousPayment = paid(user, previousMonth);
            MonthView previous = monthView(previousMonth, now, previousPayment,
                    previousPayment == null ? eligibility(user, previousMonth, now, active) : null);
            if (previous != null) months.add(previous);
        }
        Paid currentPayment = paid(user, current);
        Month period = currentPayment == null ? eligibility(user, current, now, active) : null;
        MonthView view = monthView(current, now, currentPayment, period);
        if (view != null) months.add(view);
        Reached next = period == null ? null : period.upcoming();
        Upcoming upcoming = next == null ? null
                : new Upcoming(current, next.rank(), amount(next.amount()), instant(next.opens()));
        Instant nextClaimableFrom = next != null && next.amount().signum() > 0 ? instant(next.opens()) : null;
        return new Salary(today, months, upcoming, nextClaimableFrom);
    }

    private MonthView monthView(YearMonth month, LocalDateTime now, Paid paid, Month period) {
        LocalDateTime standardEnd = CALENDAR.storageStart(month.plusMonths(1));
        if (paid != null) {
            // A claim made before the ordinary cutoff already used this month's entitlement.
            LocalDateTime end = paid.paidAt().isBefore(standardEnd) ? standardEnd
                    : CALENDAR.storageStart(month.plusMonths(1).atDay(2));
            return now.isBefore(end) ? new MonthView(month, paid.rank(), amount(paid.amount()), "CLAIMED",
                    instant(paid.opens()), instant(end), paid.number(), instant(paid.paidAt())) : null;
        }
        if (period == null || !now.isBefore(period.expires())) return null;
        boolean openPositive = period.open() != null && period.open().amount().signum() > 0;
        Reached rank = openPositive ? period.open() : period.upcoming();
        if (rank == null || rank.amount().signum() <= 0) return null;
        return new MonthView(month, rank.rank(), amount(rank.amount()), openPositive ? "CLAIMABLE" : "PENDING",
                instant(rank.opens()), instant(period.expires()), null, null);
    }

    private Month eligibility(long user, YearMonth month, LocalDateTime now, LocalDateTime active) {
        LocalDateTime start = CALENDAR.storageStart(month);
        LocalDateTime end = CALENDAR.storageStart(month.plusMonths(1));
        if (active == null || !active.isBefore(end) || now.isBefore(active) || now.isBefore(start)) return null;
        LocalDateTime anchor = start.isBefore(active) ? active : start;
        LocalDate anchorDate = CALENDAR.dateAtStorage(anchor);
        // The first subsequent transition gives the held rank just before the anchor, even after a downgrade.
        String held = db.query("""
                SELECT COALESCE(NULLIF(TRIM(UPPER(from_code)), ''), 'V0') FROM nx_user_level_log
                 WHERE user_id=? AND level_type='VRANK' AND is_deleted=0 AND created_at>=?
                 ORDER BY created_at,id LIMIT 1
                """, (rs, row) -> rs.getString(1), user, anchor).stream().findFirst()
                .orElseGet(() -> db.query("""
                        SELECT COALESCE(NULLIF(TRIM(UPPER(v_rank)), ''), 'V0')
                          FROM nx_team_member WHERE user_id=? AND member_user_id=? AND is_deleted=0
                         ORDER BY id LIMIT 1
                        """, (rs, row) -> rs.getString(1), user, user).stream().findFirst().orElse("V0"));
        List<Reached> reached = new ArrayList<>();
        if (salaryRank(held)) {
            BigDecimal rate = db.query("""
                    SELECT amount_usdt FROM nx_v_rank_salary_rate
                     WHERE rank_code=? AND effective_at<=? ORDER BY effective_at DESC LIMIT 1
                    """, (rs, row) -> rs.getBigDecimal(1), held, anchor).stream().findFirst()
                    .orElseThrow(() -> new DataRetrievalFailureException("VRANK_SALARY_RATE_MISSING"));
            LocalDateTime dayStart = CALENDAR.storageStart(anchorDate);
            boolean sameDayArrival = start.isBefore(active) && Boolean.TRUE.equals(db.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM nx_user_level_log
                     WHERE user_id=? AND level_type='VRANK' AND is_deleted=0 AND to_code=?
                       AND created_at>=? AND created_at<?)
                    """, Boolean.class, user, held, dayStart, anchor));
            reached.add(new Reached(held, rate, sameDayArrival ? CALENDAR.storageStart(anchorDate.plusDays(1)) : anchor));
        }
        reached.addAll(db.query("""
                SELECT l.to_code,COALESCE(l.salary_usdt,(
                    SELECT r.amount_usdt FROM nx_v_rank_salary_rate r
                     WHERE r.rank_code=l.to_code AND r.effective_at<=l.created_at
                     ORDER BY r.effective_at DESC LIMIT 1
                )),l.created_at FROM nx_user_level_log l
                 WHERE l.user_id=? AND l.level_type='VRANK' AND l.is_deleted=0
                   AND l.created_at>=? AND l.created_at<? AND l.created_at<=?
                   AND l.to_code IN ('V2','V3','V4','V5','V6','V7','V8')
                 ORDER BY l.created_at,l.id
                """, (rs, row) -> new Reached(rs.getString(1), rs.getBigDecimal(2),
                CALENDAR.storageStart(CALENDAR.dateAtStorage(rs.getObject(3, LocalDateTime.class)).plusDays(1))),
                user, anchor, end, now));
        Reached open = null;
        Reached upcoming = null;
        String highest = "V1";
        for (Reached candidate : reached) {
            if (!salaryRank(candidate.rank()) || candidate.rank().compareTo(highest) <= 0) continue;
            highest = candidate.rank();
            if (!candidate.opens().isBefore(end)) end = CALENDAR.storageStart(month.plusMonths(1).atDay(2));
            if (now.isBefore(candidate.opens())) upcoming = candidate;
            else open = candidate;
        }
        return new Month(open, upcoming, end);
    }

    private Paid paid(long user, YearMonth month) {
        return db.query("""
                SELECT rank_code,amount_usdt,claim_no,claimable_from,claimed_at
                  FROM nx_v_rank_salary_claim WHERE user_id=? AND salary_month=?
                """, (rs, row) -> new Paid(rs.getString(1), rs.getBigDecimal(2), rs.getString(3),
                rs.getObject(4, LocalDateTime.class), rs.getObject(5, LocalDateTime.class)),
                user, month.atDay(1)).stream().findFirst().orElse(null);
    }

    private LocalDateTime activation() {
        return db.queryForObject("SELECT MIN(effective_at) FROM nx_v_rank_salary_rate", LocalDateTime.class);
    }

    public AdminClaims claims(String user, String month, String rank, Long cursor) {
        Long userId = null;
        if (user != null) {
            try {
                userId = Long.valueOf(user.startsWith("U") ? user.substring(1) : user);
            } catch (NumberFormatException invalid) {
                throw new BizException(422, "VRANK_SALARY_FILTER_INVALID");
            }
        }
        LocalDate monthStart = month == null ? null : parseMonth(month).atDay(1);
        if (rank != null && !rank.matches("V[0-8]") || userId != null && userId <= 0 || cursor != null && cursor <= 0) {
            throw new BizException(422, "VRANK_SALARY_FILTER_INVALID");
        }
        List<AdminClaim> rows = db.query("""
                SELECT s.id,s.user_id,u.nickname,s.salary_month,s.rank_code,s.amount_usdt,s.claim_no,
                       s.commission_event_id,s.claimed_at
                  FROM nx_v_rank_salary_claim s LEFT JOIN nx_user u ON u.id=s.user_id
                 WHERE (? IS NULL OR s.user_id=?) AND (? IS NULL OR s.salary_month=?)
                   AND (? IS NULL OR s.rank_code=?)
                   AND (? IS NULL OR (s.claimed_at,s.id) <
                       (SELECT c.claimed_at,c.id FROM nx_v_rank_salary_claim c WHERE c.id=?))
                 ORDER BY s.claimed_at DESC,s.id DESC LIMIT 101
                """, (rs, row) -> new AdminClaim(rs.getLong(1), rs.getLong(2),
                String.format(java.util.Locale.ROOT, "U%08d", rs.getLong(2)), rs.getString(3),
                YearMonth.from(rs.getObject(4, LocalDate.class)).toString(),
                rs.getString(5), amount(rs.getBigDecimal(6)), rs.getString(7),
                "F5-COMMISSION-" + rs.getLong(8) + "-RELEASE", instant(rs.getObject(9, LocalDateTime.class))),
                userId, userId, monthStart, monthStart, rank, rank, cursor, cursor);
        return rows.size() <= 100 ? new AdminClaims(rows, null)
                : new AdminClaims(rows.subList(0, 100), rows.get(99).cursor());
    }

    private static String requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,95}")) {
            throw new BizException(422, "SALARY_IDEMPOTENCY_KEY_INVALID");
        }
        return key;
    }

    private static YearMonth parseMonth(String month) {
        if (month == null || !month.matches("[1-9][0-9]{3}-(0[1-9]|1[0-2])")) {
            throw new BizException(422, "VRANK_SALARY_MONTH_INVALID");
        }
        return YearMonth.parse(month);
    }

    private static boolean salaryRank(String value) {
        return value != null && value.matches("V[2-8]");
    }

    private static String scope(long user) {
        return "APP:VRANK_SALARY:USER:" + user;
    }

    private static Instant instant(LocalDateTime value) {
        return value.atZone(StorageTime.ZONE).toInstant();
    }

    private static String amount(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
