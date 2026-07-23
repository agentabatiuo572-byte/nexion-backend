package ffdd.opsconsole.treasury.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.dto.TreasuryLedgerQueryRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/bills")
@RequiredArgsConstructor
public class OpsBillsController {
    private final OpsTreasuryService treasuryService;

    @GetMapping
    @PreAuthorize("hasAuthority('finance_d4_read')")
    public ApiResult<PageResult<TreasuryLedgerBillView>> bills(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String bizNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return treasuryService.ledgerBills(query(type, userId, keyword, bizNo, status, from, to, pageNum, pageSize));
    }

    @GetMapping("/users/{userId}")
    @PreAuthorize("hasAnyAuthority('finance_d4_read','finance_d4_user_read')")
    public ApiResult<Map<String, Object>> userLedger(@PathVariable Long userId) {
        return treasuryService.userLedger(userId);
    }

    @GetMapping("/running-balance")
    @PreAuthorize("hasAnyAuthority('finance_d4_read','finance_d4_user_read')")
    public ApiResult<Map<String, Object>> runningBalance(@RequestParam Long userId) {
        return treasuryService.runningBalance(userId);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasAuthority('finance_d4_export')")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String bizNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam String reason) {
        byte[] body = treasuryService.ledgerBillsCsv(
                query(type, userId, keyword, bizNo, status, from, to, 1, 100), reason);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"d4-bills-masked.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }

    private TreasuryLedgerQueryRequest query(String type, Long userId, String keyword, String bizNo,
                                              String status, String from, String to,
                                              Integer pageNum, Integer pageSize) {
        return new TreasuryLedgerQueryRequest(type, userId, keyword, bizNo, status, from, to, pageNum, pageSize);
    }
}
