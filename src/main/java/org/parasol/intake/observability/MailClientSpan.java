package org.parasol.intake.observability;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

/**
 * Runs a method inside a {@code CLIENT} span named {@code <protocol> <operation>} (e.g. {@code imap list}, {@code smtp send}).
 * Parameters marked {@code @SpanAttribute} become span attributes, so mark only a folder, never an address, subject or {@code Message-ID}.
 */
@Inherited
@InterceptorBinding
@Target({ METHOD, TYPE })
@Retention(RUNTIME)
public @interface MailClientSpan {
	/**
	 * The protocol the method speaks, the first word of the span's name and the value of {@code messaging.system}.
	 *
	 * @return the protocol
	 */
	@Nonbinding
	Protocol protocol();

	/**
	 * What the method does, the second word of the span's name, e.g. {@code list} or {@code send}.
	 *
	 * @return the operation
	 */
	@Nonbinding
	String operation();

	/**
	 * The mail protocols the intake speaks to the mail server.
	 */
	enum Protocol {
		/**
		 * Reading and moving mail in the claims mailbox.
		 */
		IMAP,

		/**
		 * Sending a reply.
		 */
		SMTP
	}
}
