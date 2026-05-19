package ai.scoring.conversation;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

/**
 * Marks a method or class as a conversational boundary within an application.
 *
 * This annotation is used to signify that the annotated method should handle conversational contexts.
 * It typically associates operations with a specific conversation ID,
 * enabling tracing, monitoring, or contextual behavior during the execution
 * of methods or classes related to conversation processing.
 * <p>
 *   Eventually this would be implementation independent (websocket, REST endpoint, CLI, etc). It would be up to the implementation
 *   to figure out what the best way to handle conversational boundaries is for that implementation.
 * </p>
 */
@Inherited
@InterceptorBinding
@Target({ METHOD, TYPE })
@Retention(RUNTIME)
public @interface Conversational {
	@Nonbinding
	ConversationMode mode() default ConversationMode.STARTED;

	enum ConversationMode {
		STARTED, COMPLETED
	}
}
