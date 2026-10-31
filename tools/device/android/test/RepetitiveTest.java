package android.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compatibility annotation missing from Android 14's legacy UIAutomator runtime. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RepetitiveTest {
  int numIterations() default 1;
}
