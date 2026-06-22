package ffdd.opsconsole.treasury.dto;

public record TreasuryLedgerQueryRequest(
        String type,
        Long userId,
        String keyword,
        Integer pageNum,
        Integer pageSize) {
}
