package org.parasol.claim.model;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.hibernate.annotations.ValueGenerationType;

/**
 * Marks the field that holds a claim's database-generated claim number.
 * <p>
 * Hibernate only accepts {@link ValueGenerationType} on annotation types, so this annotation is how
 * {@link ClaimNumberGenerator} gets attached to a field. Use it together with
 * {@code @ColumnDefault(ClaimNumberGenerator.COLUMN_DEFAULT)}.
 */
@ValueGenerationType(generatedBy = ClaimNumberGenerator.class)
@Retention(RUNTIME)
@Target(FIELD)
public @interface ClaimNumber {
}
