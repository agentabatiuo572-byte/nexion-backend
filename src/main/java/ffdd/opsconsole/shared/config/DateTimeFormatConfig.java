package ffdd.opsconsole.shared.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

@Configuration
public class DateTimeFormatConfig {
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer nexionDateTimeJacksonCustomizer() {
        return builder -> builder
                .timeZone(TimeZone.getTimeZone("Asia/Shanghai"))
                .simpleDateFormat(DATE_TIME_PATTERN)
                .serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER))
                .deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
    }

    @Bean
    public Converter<String, LocalDateTime> stringToLocalDateTimeConverter() {
        return new Converter<String, LocalDateTime>() {
            @Override
            public LocalDateTime convert(String source) {
                return source == null || source.isBlank() ? null : LocalDateTime.parse(source, DATE_TIME_FORMATTER);
            }
        };
    }

    @Bean
    public Converter<LocalDateTime, String> localDateTimeToStringConverter() {
        return new Converter<LocalDateTime, String>() {
            @Override
            public String convert(LocalDateTime source) {
                return source == null ? null : DATE_TIME_FORMATTER.format(source);
            }
        };
    }
}
