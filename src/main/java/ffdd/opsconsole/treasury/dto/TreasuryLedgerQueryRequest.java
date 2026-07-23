package ffdd.opsconsole.treasury.dto;

public record TreasuryLedgerQueryRequest(
        String type,
        Long userId,
        String keyword,
        String bizNo,
        String status,
        String from,
        String to,
        Integer pageNum,
        Integer pageSize) {

    public TreasuryLedgerQueryRequest(String type, Long userId, String keyword, Integer pageNum, Integer pageSize) {
        this(type, userId, keyword, null, null, null, null, pageNum, pageSize);
    }

    public TreasuryLedgerQueryRequest(String type, Long userId, String keyword, String bizNo,
                                      Integer pageNum, Integer pageSize) {
        this(type, userId, keyword, bizNo, null, null, null, pageNum, pageSize);
    }
}
