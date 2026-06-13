package ffdd.team.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class TeamAmbassadorApplicationCreateRequest {
    private String applicantName;
    private String region;
    private String city;
    private LocalDate eventDate;
    private String contactMethod;
    private String applicationReason;
    private String eventPlan;
    private Integer expectedAttendees;
    private BigDecimal requestedBudgetUsdt;

    public String getApplicantName() { return applicantName; }
    public void setApplicantName(String applicantName) { this.applicantName = applicantName; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }
    public String getContactMethod() { return contactMethod; }
    public void setContactMethod(String contactMethod) { this.contactMethod = contactMethod; }
    public String getApplicationReason() { return applicationReason; }
    public void setApplicationReason(String applicationReason) { this.applicationReason = applicationReason; }
    public String getEventPlan() { return eventPlan; }
    public void setEventPlan(String eventPlan) { this.eventPlan = eventPlan; }
    public Integer getExpectedAttendees() { return expectedAttendees; }
    public void setExpectedAttendees(Integer expectedAttendees) { this.expectedAttendees = expectedAttendees; }
    public BigDecimal getRequestedBudgetUsdt() { return requestedBudgetUsdt; }
    public void setRequestedBudgetUsdt(BigDecimal requestedBudgetUsdt) { this.requestedBudgetUsdt = requestedBudgetUsdt; }
}
