package ffdd.opsconsole.team.mapper;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
// Fixed-scope ordinary relationship SELECTs only; no entity or generic CRUD surface.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportInvitationReadMapper {
    @Select("""
        <script>
        SELECT id customerId,sponsor_user_id sponsorCustomerId,sandbox,is_deleted deleted,status
          FROM nx_user
         WHERE id IN
         <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
         ORDER BY id
        </script>
        """)
    List<Row> readUsers(@Param("customerIds") Collection<Long> customerIds);

    @Select("""
        <script>
        SELECT id customerId,sponsor_user_id sponsorCustomerId,sandbox,is_deleted deleted,status
          FROM nx_user
         WHERE sponsor_user_id IN
         <foreach collection="sponsorCustomerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
         ORDER BY sponsor_user_id,id
        </script>
        """)
    List<Row> children(@Param("sponsorCustomerIds") Collection<Long> sponsorCustomerIds);

    record Row(Long customerId, Long sponsorCustomerId, Integer sandbox, Integer deleted, String status) { }
}
