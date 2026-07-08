package io.quarkus.test.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

import io.quarkus.test.junit.EnabledIfApplicationProperty.EnabledIfApplicationProperties;

@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(EnabledIfApplicationProperties.class)
@ExtendWith(EnabledIfApplicationPropertyCondition.class)
public @interface EnabledIfApplicationProperty {
	/**
	 * Specifies the name of the application property that will be used
	 * as a condition for enabling the annotated element.
	 *
	 * @return the name of the application property to be evaluated for conditional activation
	 */
	String named();

	/**
	 * A regular expression that will be used to match against the retrieved
	 * value of the {@link #named} property.
	 *
	 * @return the regular expression; never <em>blank</em>
	 * @see String#matches(String)
	 * @see java.util.regex.Pattern
	 */
	String matches();

	/**
	 * Custom reason to provide if the test or container is disabled.
	 *
	 * <p>If a custom reason is supplied, it will be combined with the default
	 * reason for this annotation. If a custom reason is not supplied, the default
	 * reason will be used.
	 *
	 * @since 5.7
	 */
	String disabledReason() default "";

	@Target({ ElementType.TYPE, ElementType.METHOD })
	@Retention(RetentionPolicy.RUNTIME)
	@Documented
	@interface EnabledIfApplicationProperties {
		/**
		 * An array of {@code EnabledIfApplicationProperty} annotations that are used
		 * to specify conditional activation criteria based on application properties.
		 */
		EnabledIfApplicationProperty[] value();
	}
}
