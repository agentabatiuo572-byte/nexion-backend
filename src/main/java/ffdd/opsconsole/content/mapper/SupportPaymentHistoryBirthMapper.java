package ffdd.opsconsole.content.mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportPaymentHistoryBirthMapper {
    @Select("SELECT id,sandbox,is_deleted isDeleted FROM nx_user WHERE id=#{customer} FOR UPDATE")
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    Customer customer(@Param("customer") long customer);

    @Select("SELECT customer_id customerId,capture_protocol captureProtocol,birth_origin birthOrigin,birth_db_utc birthDbUtc,sandbox_at_birth sandboxAtBirth,environment_status environmentStatus FROM nx_support_payment_history_birth WHERE customer_id=#{customer} FOR SHARE")
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    Birth find(@Param("customer") long customer);

    /** Plain SELECT deliberately shares the caller's RR snapshot, not the writer's current read. */
    @Select("<script>SELECT customer_id customerId,capture_protocol captureProtocol,birth_origin birthOrigin,birth_db_utc birthDbUtc,sandbox_at_birth sandboxAtBirth,environment_status environmentStatus FROM nx_support_payment_history_birth WHERE customer_id IN <foreach collection='customerIds' item='customer' open='(' separator=',' close=')'>#{customer}</foreach> ORDER BY customer_id</script>")
    List<Birth> readBirths(@Param("customerIds") Collection<Long> customerIds);

    @Insert("INSERT INTO nx_support_payment_history_birth(customer_id,capture_protocol,birth_origin,birth_db_utc,sandbox_at_birth,environment_status) VALUES(#{customer},#{protocol},'AUTH_NEW_ACCOUNT_REGISTRATION',UTC_TIMESTAMP(6),#{sandbox},#{environment})")
    int insert(@Param("customer") long customer,@Param("protocol") String protocol,
        @Param("sandbox") Integer sandbox,@Param("environment") String environment);

    record Customer(Long id,Integer sandbox,Integer isDeleted) { }
    record Birth(Long customerId,String captureProtocol,String birthOrigin,LocalDateTime birthDbUtc,
        Integer sandboxAtBirth,String environmentStatus) { }
}
