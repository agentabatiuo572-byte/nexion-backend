package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_notification_campaign")
public class NotificationCampaignEntity extends BaseEntity {
    private String campaignNo;
    private String name;
    private String kind;
    private String tier;
    private String audience;
    private String reachLabel;
    private String status;
    private String scheduleText;
    private String sentLabel;
    private String readLabel;
    private String bodyEn;
    private String bodyZh;
    private String bodyVi;
    private String swipeTo;
    private String ctaLabel;
    private String ctaHref;
    private BigDecimal budgetUsd;
    private String createdBy;
    private String lastOperator;
    private Long revision;
}
