package org.parasol.claim.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

// Claim numbers come from a database sequence that every test (and every rollback) advances, so these tests only ever
// assert the format, uniqueness and the increment between two claims they create themselves - never absolute values.
// Most tests run in a @TestTransaction, so the claims they create are rolled back (ClaimsListPageTests expects 6).
// duplicateClaimNumberIsRejected and changingClaimNumberFailsTheFlush run in their own QuarkusTransaction.requiringNew()
// transactions (the duplicate insert aborts its transaction; the claim-number change fails at commit), so they undo any
// leaked change in a finally block in case their assertions fail.
@QuarkusTest
class ClaimTests {
	private static final Pattern CLAIM_NUMBER = Pattern.compile("CLM\\d{8}");
	private static final List<Long> SEEDED_IDS = List.of(1L, 2L, 3L, 4L, 5L, 6L);
	private static final String UNIQUE_VIOLATION = "23505";

	@Test
	@TestTransaction
	void generatesFormattedNumbersWithIncrement() {
		var first = newClaim();
		first.persistAndFlush();

		var second = newClaim();
		second.persistAndFlush();

		assertThat(List.of(first.claimNumber, second.claimNumber))
			.allMatch(number -> CLAIM_NUMBER.matcher(number).matches())
			.doesNotHaveDuplicates();

		assertThat(sequenceValue(second) - sequenceValue(first))
			.isEqualTo(ClaimNumberGenerator.INCREMENT_BY);
	}

	@Test
	@TestTransaction
	void numberIsAssignedAtFlush() {
		var claim = newClaim();
		claim.persist();

		assertThat(claim.claimNumber)
			.isNull();

		Claim.flush();

		assertThat(claim.claimNumber)
			.matches(CLAIM_NUMBER);
	}

	@Test
	@TestTransaction
	void callerSetNumberIsIgnored() {
		var claim = newClaim();
		claim.claimNumber = "CLM-CALLER-SET";
		claim.persistAndFlush();

		assertThat(claim.claimNumber)
			.isNotEqualTo("CLM-CALLER-SET")
			.matches(CLAIM_NUMBER);

		assertThat(storedClaimNumber(claim.id))
			.isEqualTo(claim.claimNumber);
	}

	@Test
	@TestTransaction
	void findByClaimNumber() {
		var claim = newClaim();
		claim.persistAndFlush();
		Claim.getEntityManager().clear();

		assertThat(Claim.findByClaimNumber(claim.claimNumber))
			.get()
			.extracting(c -> c.id, c -> c.clientName)
			.containsExactly(claim.id, claim.clientName);
	}

	@Test
	@TestTransaction
	void findByClaimNumberInSameSessionReturnsManagedInstance() {
		var claim = newClaim();
		claim.persistAndFlush();

		assertThat(Claim.findByClaimNumber(claim.claimNumber))
			.get()
			.isSameAs(claim);
	}

	@Test
	@TestTransaction
	void findByUnknownClaimNumber() {
		assertThat(Claim.findByClaimNumber("CLM99999999"))
			.isEmpty();

		assertThat(Claim.findByClaimNumber("not a claim number"))
			.isEmpty();

		assertThat(Claim.findByClaimNumber(null))
			.isEmpty();
	}

	@Test
	@TestTransaction
	void findExistingReturnsTheClaim() {
		var claim = newClaim();
		claim.persistAndFlush();

		assertThat(Claim.findExisting(claim.id))
			.isSameAs(claim);
	}

	@Test
	@TestTransaction
	void findExistingFailsForAnUnknownId() {
		assertThatThrownBy(() -> Claim.findExisting(-1L))
			.isInstanceOf(ClaimNotFoundException.class)
			.hasMessage("Claim -1 was not found")
			.extracting("claimId")
			.isEqualTo(-1L);
	}

	@Test
	void duplicateClaimNumberIsRejected() {
		var seededNumber = seededClaim(1L).claimNumber;

		try {
			assertThatThrownBy(() ->
				QuarkusTransaction.requiringNew().run(() ->
					Claim.getEntityManager()
						.createNativeQuery("INSERT INTO claims(id, claim_number, client_name) VALUES (-1, :number, 'Duplicate')")
						.setParameter("number", seededNumber)
						.executeUpdate()
				)
			)
				.rootCause()
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("claim_number")
				.satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(UNIQUE_VIOLATION));

			assertThat(Claim.<Claim>findByIdOptional(-1L))
				.isEmpty();
		}
		finally {
			QuarkusTransaction.requiringNew().run(() -> Claim.delete("id", -1L));
		}
	}

	@Test
	void changingClaimNumberFailsTheFlush() {
		var originalNumber = seededClaim(1L).claimNumber;

		try {
			assertThatThrownBy(() ->
				QuarkusTransaction.requiringNew().run(() -> {
					var claim = Claim.<Claim>findById(1L);
					claim.claimNumber = "CLM-CHANGED";
				})
			)
				.hasStackTraceContaining("immutable natural identifier");

			assertThat(seededClaim(1L).claimNumber)
				.isEqualTo(originalNumber);
		}
		finally {
			// A native UPDATE because the entity maps claim_number as updatable = false
			QuarkusTransaction.requiringNew().run(() -> {
				if (!originalNumber.equals(storedClaimNumber(1L))) {
					Claim.getEntityManager()
						.createNativeQuery("UPDATE claims SET claim_number = :original WHERE id = 1")
						.setParameter("original", originalNumber)
						.executeUpdate();
				}
			});
		}
	}

	@Test
	@TestTransaction
	void bodyLongerThan5000CharactersPersists() {
		var longText = "x".repeat(20_000);
		var claim = newClaim();
		claim.subject = longText;
		claim.body = longText;
		claim.location = longText;
		claim.persistAndFlush();
		Claim.getEntityManager().clear();

		assertThat(Claim.<Claim>findByIdOptional(claim.id))
			.get()
			.extracting(c -> c.subject.length(), c -> c.body.length(), c -> c.location.length())
			.containsExactly(20_000, 20_000, 20_000);
	}

	@Test
	@TestTransaction
	void categoryIsStoredAsConstantName() {
		var claim = newClaim();
		claim.category = ClaimCategory.OTHER;
		claim.persistAndFlush();

		var stored = Claim.getEntityManager()
			.createNativeQuery("SELECT category FROM claims WHERE id = :id", String.class)
			.setParameter("id", claim.id)
			.getSingleResult();

		assertThat(stored)
			.isEqualTo("OTHER");
	}

	@Test
	@TestTransaction
	void incidentTimeIsOptional() {
		var claim = newClaim();
		claim.incidentTime = null;
		claim.persistAndFlush();
		Claim.getEntityManager().clear();

		assertThat(Claim.<Claim>findByIdOptional(claim.id))
			.get()
			.extracting(c -> c.incidentDate, c -> c.incidentTime)
			.containsExactly(LocalDate.of(2024, 5, 1), null);
	}

	@Test
	@TestTransaction
	void seededClaimsHaveWellFormedUniqueNumbersAndValidDates() {
		var seeded = Claim.<Claim>list("id in ?1", SEEDED_IDS);

		assertThat(seeded)
			.hasSize(SEEDED_IDS.size())
			.allSatisfy(claim -> {
				assertThat(claim.claimNumber)
					.matches(CLAIM_NUMBER);

				assertThat(claim.incidentDate)
					.isAfter(claim.inceptionDate);

				assertThat(claim.category)
					.isNotNull();
			})
			.extracting(claim -> claim.claimNumber)
			.doesNotHaveDuplicates();
	}

	@Test
	@TestTransaction
	void seededClaimsKeepTypedIncidentDetails() {
		assertThat(seededClaim(1L))
			.extracting(c -> c.category, c -> c.incidentDate, c -> c.incidentTime, c -> c.status)
			.containsExactly(ClaimCategory.MULTIPLE_VEHICLE, LocalDate.of(1955, 1, 2), LocalTime.of(15, 30), "In Process");

		assertThat(seededClaim(3L))
			.extracting(c -> c.summary, c -> c.location, c -> c.sentiment)
			.containsOnlyNulls();

		assertThat(seededClaim(4L))
			.extracting(c -> c.category, c -> c.incidentTime)
			.containsExactly(ClaimCategory.SINGLE_VEHICLE, null);
	}

	@Test
	@TestTransaction
	void newClaimNumberDiffersFromEverySeededNumber() {
		var seededNumbers = Claim.<Claim>list("id in ?1", SEEDED_IDS)
			.stream()
			.map(claim -> claim.claimNumber)
			.toList();

		var claim = newClaim();
		claim.persistAndFlush();

		assertThat(seededNumbers)
			.hasSize(SEEDED_IDS.size())
			.doesNotContain(claim.claimNumber);
	}

	private static Claim newClaim() {
		var claim = new Claim();
		claim.category = ClaimCategory.THEFT;
		claim.policyNumber = "AC-000000";
		claim.inceptionDate = LocalDate.of(2020, 1, 1);
		claim.clientName = "Test Client";
		claim.emailAddress = "test.client@email.com";
		claim.subject = "Test subject";
		claim.body = "Test body";
		claim.incidentDate = LocalDate.of(2024, 5, 1);
		claim.incidentTime = LocalTime.of(9, 15);
		claim.status = "New";

		return claim;
	}

	private static long sequenceValue(Claim claim) {
		return Long.parseLong(claim.claimNumber.substring("CLM".length()));
	}

	private static Claim seededClaim(long id) {
		return QuarkusTransaction.requiringNew().call(() ->
			Claim.<Claim>findByIdOptional(id)
				.orElseThrow(() -> new IllegalStateException("Seeded claim %d not found".formatted(id)))
		);
	}

	private static String storedClaimNumber(long id) {
		return (String) Claim.getEntityManager()
			.createNativeQuery("SELECT claim_number FROM claims WHERE id = :id", String.class)
			.setParameter("id", id)
			.getSingleResult();
	}
}
