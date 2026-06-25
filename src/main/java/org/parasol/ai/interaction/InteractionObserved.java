package org.parasol.ai.interaction;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

@Inherited
@InterceptorBinding
@Target({ METHOD, TYPE })
@Retention(RUNTIME)
public @interface InteractionObserved {
	@Nonbinding
	String name() default "";

	@Nonbinding
	String description() default "";

	@Nonbinding
	String unit() default "";
}
