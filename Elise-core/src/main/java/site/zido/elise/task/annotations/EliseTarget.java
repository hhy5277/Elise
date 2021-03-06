package site.zido.elise.task.annotations;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface EliseTarget {
    String matchUrl() default "";

    String matchStatusCode() default "200";
}
