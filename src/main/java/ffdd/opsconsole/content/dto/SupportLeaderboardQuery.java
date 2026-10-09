package ffdd.opsconsole.content.dto;

import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.Board;
import ffdd.opsconsole.content.domain.SupportLeaderboard.Scope;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;

/** Raw public resource parameters; a legal query is not a grant of access. */
public record SupportLeaderboardQuery(String board, String month, String currency, String scope, String groupId,
                                     String keyword, String pageNum,
                                     String pageSize, String expectedVersion) {
    public static final Set<String> PARAMETERS = Set.of("board","month","currency","scope","groupId","keyword",
        "pageNum","pageSize","expectedVersion");
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;

    public static SupportLeaderboardQuery fromParameters(Map<String,List<String>> parameters) {
        Objects.requireNonNull(parameters);
        for (var e : parameters.entrySet()) if (!PARAMETERS.contains(e.getKey()) || e.getValue() == null
                || e.getValue().size() != 1 || e.getValue().get(0) == null) throw invalid();
        return new SupportLeaderboardQuery(raw(parameters,"board"),raw(parameters,"month"),raw(parameters,"currency"),
            raw(parameters,"scope"),raw(parameters,"groupId"),raw(parameters,"keyword"),
            raw(parameters,"pageNum"),raw(parameters,"pageSize"),raw(parameters,"expectedVersion"));
    }
    /** Policy and now are server supplied. Covered months are verified source availability, not a client assertion. */
    public Normalized normalize(Instant now, Set<YearMonth> serverCoveredMonths, Set<String> serverCurrencies,
                                String serverDefaultCurrency, Map<Scope,Set<Long>> serverAllowedScopes) {
        Objects.requireNonNull(now); Objects.requireNonNull(serverCoveredMonths); Objects.requireNonNull(serverCurrencies);
        Objects.requireNonNull(serverAllowedScopes);
        if (!serverCurrencies.contains(serverDefaultCurrency)) throw new IllegalArgumentException("Server currency policy required");
        Board b = enumeration(board, Board.class, Board.firstPayment);
        Scope s = enumeration(scope, Scope.class, Scope.all);
        Set<Long> serverApprovedGroups = serverAllowedScopes.get(s);
        if (serverApprovedGroups == null || s != Scope.all && serverApprovedGroups.isEmpty())
            throw new BizException(403,"SUPPORT_LEADERBOARD_SCOPE_DENIED");
        if (s == Scope.all && !serverApprovedGroups.isEmpty() || s == Scope.ownGroup && serverApprovedGroups.size() != 1
                || serverApprovedGroups.stream().anyMatch(id -> id == null || id <= 0 || id > MAX_SAFE_INTEGER))
            throw new IllegalArgumentException("Server scope policy invalid");
        Long g = groupId == null ? null : number(groupId,0);
        if (g != null && (g < 1 || g > MAX_SAFE_INTEGER)) throw invalid();
        if (s == Scope.all && g != null) throw invalid();
        if (g != null && !serverApprovedGroups.contains(g)) throw new BizException(403,"SUPPORT_LEADERBOARD_SCOPE_DENIED");
        YearMonth current = YearMonth.from(now.atZone(SupportLeaderboard.BUSINESS_ZONE));
        YearMonth selected = null;
        if (b == Board.customers) { if (month != null) throw invalid(); }
        else {
            if (month == null) selected = current;
            else {
                if (!month.matches("[1-9][0-9]{3}-(0[1-9]|1[0-2])")) throw invalid();
                try { selected = YearMonth.parse(month); } catch (DateTimeException ex) { throw invalid(); }
            }
            if (selected.isAfter(current) || !serverCoveredMonths.contains(selected))
                throw new BizException(422,"SUPPORT_LEADERBOARD_MONTH_UNAVAILABLE");
        }
        String unit = currency == null ? serverDefaultCurrency : currency;
        if (!unit.matches("[A-Z][A-Z0-9]{1,15}") || !serverCurrencies.contains(unit))
            throw new BizException(422,"SUPPORT_LEADERBOARD_CURRENCY_INVALID");
        if (keyword != null && (keyword.length() > 200 || keyword.codePoints().anyMatch(Character::isISOControl))) throw invalid();
        long page = number(pageNum,1), size = number(pageSize,20);
        if (page < 1 || page > MAX_SAFE_INTEGER || size < 1 || size > 100 || page > Long.MAX_VALUE / size) throw invalid();
        if (expectedVersion != null && !SupportLeaderboard.validVersion(expectedVersion)) throw invalid();
        if (page > 1 && expectedVersion == null) throw new BizException(422,"SUPPORT_LEADERBOARD_VERSION_REQUIRED");
        return new Normalized(b,selected,b == Board.customers ? current : selected,unit,s,g,
            keyword == null || keyword.isBlank() ? null : keyword.strip(),page,(int)size,expectedVersion);
    }
    public record Normalized(Board board, YearMonth rankMonth, YearMonth referenceMonth, String currency,
                             Scope scope, Long groupId, String keyword, long pageNum, int pageSize, String expectedVersion) { }
    private static String raw(Map<String,List<String>> p,String name) {return p.containsKey(name) ? p.get(name).get(0) : null;}
    private static long number(String raw,long fallback) {
        if (raw == null) return fallback;
        if (!raw.matches("[0-9]+")) throw invalid();
        try { return Long.parseLong(raw); } catch (NumberFormatException ex) { throw invalid(); }
    }
    private static <E extends Enum<E>> E enumeration(String raw,Class<E> type,E fallback) {
        if (raw == null) return fallback;
        try { return Enum.valueOf(type,raw); } catch (IllegalArgumentException ex) { throw invalid(); }
    }
    private static BizException invalid() { return new BizException(422,"SUPPORT_LEADERBOARD_QUERY_INVALID"); }
}
