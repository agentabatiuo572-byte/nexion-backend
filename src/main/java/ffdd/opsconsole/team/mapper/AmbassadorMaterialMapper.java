package ffdd.opsconsole.team.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AmbassadorMaterialMapper {
    String APPLICATION = "SELECT id,user_id userId,UPPER(status) status,application_reason bucket,event_date eventDate,"
            + "requested_budget_usdt budget,source_environment sourceEnvironment,run_id runId FROM nx_team_ambassador_application WHERE id=#{id} AND is_deleted=0";
    @Select(APPLICATION) Application application(long id);
    @Select(APPLICATION + " FOR UPDATE") Application lockApplication(long id);
    @Select("SELECT sandbox FROM nx_user WHERE id=#{id} AND status='ACTIVE' AND is_deleted=0") Integer userSandbox(long id);
    @Select("SELECT revision,bucket,budget_usdt budget,statement,attachments_json attachmentsJson FROM nx_team_ambassador_material_revision WHERE application_id=#{id} ORDER BY revision DESC LIMIT 1")
    Revision current(long id);
    @Insert("INSERT INTO nx_team_ambassador_material_revision(application_id,revision,bucket,budget_usdt,statement,attachments_json) VALUES(#{id},#{revision},#{bucket},#{budget},#{statement},#{attachmentsJson})")
    int insertRevision(@Param("id") long id,@Param("revision") long revision,@Param("bucket") String bucket,@Param("budget") BigDecimal budget,@Param("statement") String statement,@Param("attachmentsJson") String attachmentsJson);
    @Update("UPDATE nx_team_ambassador_application SET application_reason=#{bucket},requested_budget_usdt=#{budget},updated_at=NOW() WHERE id=#{id} AND UPPER(status)='PENDING' AND is_deleted=0")
    int updateApplication(@Param("id") long id,@Param("bucket") String bucket,@Param("budget") BigDecimal budget);
    @Insert("INSERT INTO nx_team_ambassador_material_file(file_id,application_id,user_id,kind,name,content_type,size_bytes,object_key) VALUES(#{fileId},#{applicationId},#{userId},#{kind},#{name},#{contentType},#{sizeBytes},#{objectKey})")
    int insertFile(FileRow row);
    @Select("SELECT file_id fileId,application_id applicationId,user_id userId,kind,name,content_type contentType,size_bytes sizeBytes,object_key objectKey FROM nx_team_ambassador_material_file WHERE file_id=#{fileId} AND application_id=#{applicationId}")
    FileRow file(@Param("applicationId") long applicationId,@Param("fileId") String fileId);
    @Select("SELECT revision,stage,status,reviewer_id reviewerId,reviewer_label reviewerLabel,reviewed_at reviewedAt,reason,verified_attendees verifiedAttendees,recognized_budget_usdt recognizedBudgetUsdt,missing_kinds_json missingKindsJson FROM nx_team_ambassador_material_review WHERE application_id=#{id} ORDER BY id DESC")
    List<Review> reviews(long id);
    @Insert("INSERT INTO nx_team_ambassador_material_review(application_id,revision,stage,status,reviewer_id,reviewer_label,reason,verified_attendees,recognized_budget_usdt,missing_kinds_json) VALUES(#{id},#{revision},#{stage},#{status},#{reviewerId},#{reviewerLabel},#{reason},#{verifiedAttendees},#{recognizedBudgetUsdt},#{missingKindsJson})")
    int insertReview(@Param("id") long id,@Param("revision") long revision,@Param("stage") String stage,@Param("status") String status,@Param("reviewerId") long reviewerId,@Param("reviewerLabel") String reviewerLabel,@Param("reason") String reason,@Param("verifiedAttendees") Integer verifiedAttendees,@Param("recognizedBudgetUsdt") BigDecimal recognizedBudgetUsdt,@Param("missingKindsJson") String missingKindsJson);
    record Application(long id,long userId,String status,String bucket,LocalDate eventDate,BigDecimal budget,String sourceEnvironment,String runId) {}
    record Revision(long revision,String bucket,BigDecimal budget,String statement,String attachmentsJson) {}
    record FileRow(String fileId,long applicationId,long userId,String kind,String name,String contentType,long sizeBytes,String objectKey) {}
    record Review(long revision,String stage,String status,long reviewerId,String reviewerLabel,LocalDateTime reviewedAt,String reason,Integer verifiedAttendees,BigDecimal recognizedBudgetUsdt,String missingKindsJson) {}
}
