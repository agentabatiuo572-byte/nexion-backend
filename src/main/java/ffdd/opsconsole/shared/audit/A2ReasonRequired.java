package ffdd.opsconsole.shared.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks an admin command whose A2 reason is mandatory even when its DTO has no reason field. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface A2ReasonRequired {
}
