package ai.scoring.conversation;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.interceptor.InterceptorBinding;

/**
 * Marks a method or class as a conversational boundary within an application.
 *
 * This annotation is used to signify that the annotated method should handle conversational contexts.
 * It typically associates operations with a specific conversation ID,
 * enabling tracing, monitoring, or contextual behavior during the execution
 * of methods or classes related to conversation processing.
 * <p>
 *   On a WebSocket class, this annotation can only be placed on methods with the following annotations:
 *   <ul>
 *   <li>{@link io.quarkus.websockets.next.OnOpen}</li>
 *   <li>{@link io.quarkus.websockets.next.OnClose}</li>
 *   <li>{@link io.quarkus.websockets.next.OnTextMessage}</li>
 *   <li>{@link io.quarkus.websockets.next.OnError}</li>
 * </ul>
 * </p>
 */
@Inherited
@InterceptorBinding
@Target({ METHOD, TYPE })
@Retention(RUNTIME)
public @interface Conversational {
}
