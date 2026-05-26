package ffdd.commerce.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class PaymentOpsResult {
    private int scanned;
    private int expired;
    private int reconciled;
    private int paid;
    private int failed;
    private int skipped;
    private int errors;
    private final List<String> paymentNos = new ArrayList<>();

    public void incrementScanned() {
        scanned++;
    }

    public void incrementExpired(String paymentNo) {
        expired++;
        paymentNos.add(paymentNo);
    }

    public void incrementReconciled(String paymentNo) {
        reconciled++;
        paymentNos.add(paymentNo);
    }

    public void incrementPaid() {
        paid++;
    }

    public void incrementFailed() {
        failed++;
    }

    public void incrementSkipped() {
        skipped++;
    }

    public void incrementErrors() {
        errors++;
    }
}
