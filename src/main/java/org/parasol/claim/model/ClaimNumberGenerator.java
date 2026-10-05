package org.parasol.claim.model;

import java.util.EnumSet;

import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.relational.Database;
import org.hibernate.boot.model.relational.ExportableProducer;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.dialect.Dialect;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;
import org.hibernate.generator.OnExecutionGenerator;

/**
 * Lets PostgreSQL generate claim numbers ({@code CLM} + 8 digits) from {@value #SEQUENCE_NAME}.
 * <ul>
 *   <li>The column's default ({@link #COLUMN_DEFAULT}) produces the value. This generator only tells Hibernate to leave the
 *   column out of the {@code INSERT} and read it back ({@code insert ... returning claim_number}).</li>
 *   <li>As an {@link ExportableProducer} it registers the sequence in Hibernate's relational model. Schema management
 *   therefore creates it before the tables and drops it on drop-and-create. A {@code @SequenceGenerator} can't be used:
 *   on its own it takes over the entity's id generation, and an unused one is never created.</li>
 *   <li>The sequence has {@code maxvalue 99999999}. Past that, {@code lpad} would silently truncate to 8 digits and
 *   produce wrong, possibly colliding numbers; the maximum turns that into a clear {@code nextval} error instead.</li>
 * </ul>
 */
public class ClaimNumberGenerator implements OnExecutionGenerator, ExportableProducer {
	/**
	 * The name of the database sequence that backs claim numbers.
	 */
	public static final String SEQUENCE_NAME = "claim_number_seq";

	/**
	 * The SQL column default that produces a claim number.
	 */
	public static final String COLUMN_DEFAULT = "'CLM' || lpad(nextval('" + SEQUENCE_NAME + "')::text, 8, '0')";

	/**
	 * The first sequence value, giving {@code CLM01000000}.
	 */
	public static final int START_WITH = 1_000_000;

	/**
	 * The step between consecutive claim numbers (the first prime above 1000).
	 */
	public static final int INCREMENT_BY = 1009;

	private static final String SEQUENCE_OPTIONS = "maxvalue 99999999";

	@Override
	public EnumSet<EventType> getEventTypes() {
		return EventTypeSets.INSERT_ONLY;
	}

	@Override
	public boolean referenceColumnsInSql(Dialect dialect) {
		// Leave the column out of the INSERT so the column default applies
		return false;
	}

	@Override
	public boolean writePropertyValue() {
		// Never bind the entity's value, so a caller-set number is ignored
		return false;
	}

	@Override
	public String[] getReferencedColumnValues(Dialect dialect) {
		// Only consulted when referenceColumnsInSql() returns true
		return null;
	}

	@Override
	public void registerExportables(Database database) {
		var namespace = database.getDefaultNamespace();
		var logicalName = Identifier.toIdentifier(SEQUENCE_NAME);

		if (namespace.locateSequence(logicalName) == null) {
			namespace.createSequence(
				logicalName,
				physicalName -> new Sequence(
					"orm",
					namespace.getPhysicalName().catalog(),
					namespace.getPhysicalName().schema(),
					physicalName,
					START_WITH,
					INCREMENT_BY,
					SEQUENCE_OPTIONS
				)
			);
		}
	}
}
