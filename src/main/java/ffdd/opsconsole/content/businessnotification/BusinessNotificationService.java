package ffdd.opsconsole.content.businessnotification;

import ffdd.opsconsole.content.businessnotification.mapper.BusinessNotificationMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Projects committed facts only. Notification retries never touch financial or device state. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name="nexion.business-notifications.enabled",havingValue="true")
public class BusinessNotificationService {
    private final BusinessNotificationMapper mapper;

    @Scheduled(fixedDelayString="${nexion.business-notifications.delay-ms:30000}")
    // INSERT ... SELECT must read business sources without retaining shared locks until the scan commits.
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void scan() {
        if (mapper.lockControl()==null) throw new IllegalStateException("BUSINESS_NOTIFICATION_MIGRATION_REQUIRED");
        repairWithdrawals(null);
        mapper.seedDevices();
        // UPDATE JOIN would still retain source device/runtime locks at READ_COMMITTED.
        // The control row serializes state-only writes; alert reads recheck current business facts.
        mapper.recoveredDeviceIds().forEach(mapper::resetRecoveredDevice);
        mapper.abnormalDevices().forEach(device->mapper.startAbnormalDevice(device.deviceId(),device.userId()));
        mapper.facts().forEach(this::emit);
        mapper.withdrawalFacts(null,false).forEach(this::emit);
        mapper.trialDeadlines().forEach(this::emit);
        for (var fact : mapper.deviceAlerts()) {
            emit(fact);
            mapper.markDeviceAlert(fact.factKey());
        }
    }

    /** Account-scoped station recovery; it never inserts an outbox event or a native delivery. */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void collectWithdrawalAttention(Long userId) {
        if(userId==null || userId<=0) throw new IllegalArgumentException("NOTIFICATION_RECIPIENT_INVALID");
        if(mapper.control()==null) throw new IllegalStateException("BUSINESS_NOTIFICATION_MIGRATION_REQUIRED");
        repairWithdrawals(userId);
        mapper.withdrawalFacts(userId,true).forEach(fact->emit(fact,true));
        mapper.withdrawalStateFacts(userId).forEach(fact->emit(fact,true));
    }

    private void repairWithdrawals(Long userId) {
        for(var repair:mapper.withdrawalRepairs(userId)) {
            mapper.repairWithdrawalNotification(repair);
            mapper.repairWithdrawalActionRoute(repair);
        }
    }

    void emit(BusinessNotificationFact fact) {
        emit(fact,false);
    }

    private void emit(BusinessNotificationFact fact,boolean stationOnly) {
        if (fact.userId()==null || fact.userId()<=0) throw new IllegalArgumentException("NOTIFICATION_RECIPIENT_INVALID");
        Text text = text(fact.messageKey());
        String existing = "deposit".equals(fact.messageKey()) ? mapper.existingTopup(fact.factKey(),fact.userId()) : null;
        if("payment_method".equals(fact.messageKey())) existing=mapper.existingPaymentMethod(fact.factKey(),fact.userId());
        String bizNo = existing==null ? (stationOnly ? "STATION1:"+bizNo(fact.factKey()).substring(8) : bizNo(fact.factKey())) : existing;
        if (mapper.receipt(fact.factKey(),bizNo,fact.userId())==0) return;
        mapper.notify(fact,bizNo,text.zh(),text.en(),text.vi());
    }

    static String bizNo(String key) {
        try { return "NATIVE1:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    record Text(String zh,String en,String vi) {}
    static Text text(String key) {
        Text text=TEXT.get(key);
        if(text==null) throw new IllegalArgumentException("UNKNOWN_BUSINESS_NOTIFICATION_MESSAGE");
        return text;
    }
    private static final Map<String,Text> TEXT=Map.ofEntries(
        Map.entry("purchase_delivered",new Text("付款成功，设备已开通，请查看订单。","Payment received and your device is ready. View your order.","Đã nhận thanh toán và thiết bị đã sẵn sàng. Xem đơn hàng.")),
        Map.entry("purchase_timeout",new Text("付款超时，订单已取消。如已付款，请勿重复付款。","Payment timed out and your order was cancelled. If you already paid, do not pay again.","Thanh toán hết hạn, đơn hàng đã hủy. Nếu đã thanh toán, vui lòng không thanh toán lại.")),
        Map.entry("purchase_review",new Text("付款状态已更新，请查看订单处理进度。","Your payment status changed. View your order progress.","Trạng thái thanh toán đã thay đổi. Xem tiến độ đơn hàng.")),
        Map.entry("deposit",new Text("充值已到账，请查看钱包记录。","Your deposit has been credited. View your wallet history.","Tiền nạp đã được ghi có. Vui lòng xem lịch sử ví.")),
        Map.entry("withdrawal_completed",new Text("提现已完成，请查看该笔提现详情。","Your withdrawal is complete. View this withdrawal's details.","Rút tiền đã hoàn tất. Xem chi tiết giao dịch rút tiền này.")),
        Map.entry("withdrawal_returned",new Text("提现未完成，资金已退回，请查看该笔提现详情。","Your withdrawal was not completed and funds were returned. View this withdrawal's details.","Rút tiền chưa hoàn tất và tiền đã được hoàn lại. Xem chi tiết giao dịch này.")),
        Map.entry("withdrawal_failed",new Text("提现失败，请查看该笔提现的状态、原因与处理指引。","Your withdrawal failed. View its status, reason and next steps.","Rút tiền không thành công. Xem trạng thái, lý do và hướng dẫn xử lý.")),
        Map.entry("withdrawal_rejected",new Text("提现审核未通过，请查看该笔提现详情。","Your withdrawal review was not approved. View this withdrawal's details.","Yêu cầu rút tiền không được duyệt. Xem chi tiết giao dịch này.")),
        Map.entry("withdrawal_frozen",new Text("提现已冻结，请查看该笔提现的状态与处理指引。","Your withdrawal was frozen. View its status and next steps.","Yêu cầu rút tiền đã bị đóng băng. Xem trạng thái và hướng dẫn xử lý.")),
        Map.entry("withdrawal_delayed",new Text("提现处理已延后，请查看该笔提现详情。","Your withdrawal processing was delayed. View this withdrawal's details.","Việc xử lý rút tiền đã bị hoãn. Xem chi tiết giao dịch này.")),
        Map.entry("withdrawal_review_required",new Text("提现需要复核，请查看该笔提现的状态与处理指引。","Your withdrawal requires review. View its status and next steps.","Yêu cầu rút tiền cần được xem xét. Xem trạng thái và hướng dẫn xử lý.")),
        Map.entry("withdrawal_state",new Text("提现状态提醒：该笔提现失败或需要关注，请查看原单状态、原因与处理指引。","Withdrawal status reminder: this withdrawal failed or needs attention. View its current details and next steps.","Nhắc trạng thái rút tiền: giao dịch thất bại hoặc cần chú ý. Xem chi tiết hiện tại và hướng dẫn xử lý.")),
        Map.entry("refund",new Text("订单退款已完成，请查看钱包记录。","Your order refund is complete. View your wallet history.","Hoàn tiền đơn hàng đã hoàn tất. Vui lòng xem lịch sử ví.")),
        Map.entry("password",new Text("您的登录密码已更新。如非本人操作，请及时检查账号安全。","Your sign-in password was updated. Review account security if this was not you.","Mật khẩu đăng nhập đã được cập nhật. Kiểm tra bảo mật nếu không phải bạn thực hiện.")),
        Map.entry("payment_method",new Text("您的收付款信息已变更。如非本人操作，请及时检查账号安全。","Your payment details changed. Review account security if this was not you.","Thông tin thanh toán đã thay đổi. Kiểm tra bảo mật nếu không phải bạn thực hiện.")),
        Map.entry("security",new Text("您的账号安全状态已变更，请查看详情。","Your account security status changed. View details.","Trạng thái bảo mật tài khoản đã thay đổi. Vui lòng xem chi tiết.")),
        Map.entry("new_signin",new Text("您的账号出现新的登录环境，请检查账号安全。","A new sign-in environment was detected. Review account security.","Phát hiện môi trường đăng nhập mới. Vui lòng kiểm tra bảo mật tài khoản.")),
        Map.entry("support",new Text("客服有新回复，请打开会话查看。","Support sent a new reply. Open your conversation to view it.","Bộ phận hỗ trợ có phản hồi mới. Mở cuộc trò chuyện để xem.")),
        Map.entry("device_alert",new Text("您的设备持续异常，请查看设备状态。","Your device has a persistent issue. Review its status.","Thiết bị đang gặp sự cố kéo dài. Vui lòng kiểm tra trạng thái.")),
        Map.entry("trial_24h",new Text("设备试用将在24小时内结束，请查看试用详情。","Your device trial ends within 24 hours. View trial details.","Dùng thử thiết bị sẽ kết thúc trong 24 giờ. Vui lòng xem chi tiết.")),
        Map.entry("trial_1h",new Text("设备试用将在1小时内结束，请查看试用详情。","Your device trial ends within 1 hour. View trial details.","Dùng thử thiết bị sẽ kết thúc trong 1 giờ. Vui lòng xem chi tiết.")),
        Map.entry("trial_end",new Text("设备试用期已结束，请查看抵扣保留期详情。","Your device trial has ended. View the deduction retention details.","Thời gian dùng thử thiết bị đã kết thúc. Xem thời hạn giữ khấu trừ.")),
        Map.entry("retention_24h",new Text("试用抵扣保留期将在24小时内结束，请查看详情。","Your trial deduction retention ends within 24 hours. View details.","Thời hạn giữ khấu trừ dùng thử sẽ kết thúc trong 24 giờ. Xem chi tiết.")),
        Map.entry("retention_1h",new Text("试用抵扣保留期将在1小时内结束，请查看详情。","Your trial deduction retention ends within 1 hour. View details.","Thời hạn giữ khấu trừ dùng thử sẽ kết thúc trong 1 giờ. Xem chi tiết.")),
        Map.entry("retention_end",new Text("试用抵扣保留期已结束，请查看试用详情。","Your trial deduction retention has ended. View trial details.","Thời hạn giữ khấu trừ dùng thử đã kết thúc. Vui lòng xem chi tiết."))
    );
}
