package org.parasol.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import io.quarkiverse.quinoa.testing.QuinoaTestProfiles;

@QuarkusTest
@TestProfile(QuinoaTestProfiles.EnableAndRunTests.class)
public class ClaimsListPageTests extends PlaywrightTests {
	private static final int NB_CLAIMS = 6;

	@Test
	void pageLoads() {
		var page = loadPage("%s_pageLoads".formatted(getClass().getSimpleName()));

		PlaywrightAssertions.assertThat(page)
			.hasTitle("Claims List");
	}

	@Test
	void correctTable() {
		var table = getAndVerifyClaimsTable(NB_CLAIMS, "%s_correctTable".formatted(getClass().getSimpleName()));
		var tableColumns = table.getByRole(AriaRole.COLUMNHEADER).all();

		assertThat(tableColumns)
			.isNotNull()
			.hasSize(5)
			.extracting(Locator::textContent)
			.containsExactly(
"Claim Number",
				"Category",
				"Client Name",
				"Policy Number",
				"Status"
			);

		var rows = getTableBodyRows(table);
		assertThat(rows)
			.isNotNull()
			.hasSize(NB_CLAIMS);

		var claim = Claim.<Claim>findById(1L);
		var firstRow = rows.get(0).getByRole(AriaRole.GRIDCELL).all();
		assertThat(firstRow)
			.isNotNull()
			.hasSize(5);

		assertThat(firstRow.get(0))
			.isNotNull()
			.extracting(
				Locator::textContent,
				l -> l.getByRole(AriaRole.LINK).getAttribute("href")
			)
			.containsExactly(
				claim.claimNumber,
				"/ClaimDetail/%d".formatted(claim.id)
			);

		assertThat(firstRow.get(1))
			.isNotNull()
			.extracting(Locator::textContent)
			.isEqualTo(claim.category.label());

		assertThat(firstRow.get(2))
			.isNotNull()
			.extracting(Locator::textContent)
			.isEqualTo(claim.clientName);

		assertThat(firstRow.get(3))
			.isNotNull()
			.extracting(Locator::textContent)
			.isEqualTo(claim.policyNumber);

		assertThat(firstRow.get(4))
			.isNotNull()
			.extracting(Locator::textContent)
			.isEqualTo(claim.status);
	}

	@ParameterizedTest
	@EnumSource(value = ClaimCategory.class, names = { "SINGLE_VEHICLE", "MULTIPLE_VEHICLE" })
	void filterBySeededCategory(ClaimCategory category) {
		var expectedRows = QuarkusTransaction.requiringNew().call(() -> Claim.count("category", category));
		var page = loadPage("%s_filterBy_%s".formatted(getClass().getSimpleName(), category));

		page.getByLabel("Filter by category").selectOption(category.label());

		PlaywrightAssertions.assertThat(getTableBodyRowsLocator(page))
			.hasCount(expectedRows.intValue());

		assertThat(getCategoryCells(page))
			.isNotEmpty()
			.hasSize(expectedRows.intValue())
			.allMatch(category.label()::equals);
	}

	@Test
	void filterByOther() {
		// No seeded claim is "Other", so create one. The browser only sees committed data, so it must be committed and
		// deleted afterwards (correctTable expects exactly NB_CLAIMS rows).
		var otherClaim = QuarkusTransaction.requiringNew().call(() -> {
			var claim = new Claim();
			claim.category = ClaimCategory.OTHER;
			claim.clientName = "Other Category Client";
			claim.policyNumber = "OT-123456";
			claim.inceptionDate = LocalDate.of(2020, 1, 1);
			claim.incidentDate = LocalDate.of(2024, 6, 1);
			claim.status = "New";
			claim.persistAndFlush();

			return claim;
		});

		try {
			var page = loadPage("%s_filterByOther".formatted(getClass().getSimpleName()));

			PlaywrightAssertions.assertThat(getTableBodyRowsLocator(page))
				.hasCount(NB_CLAIMS + 1);

			assertThat(getTableBodyRows(page.getByRole(AriaRole.GRID)))
				.hasSize(NB_CLAIMS + 1);

			page.getByLabel("Filter by category").selectOption("Other");

			PlaywrightAssertions.assertThat(getTableBodyRowsLocator(page))
				.hasCount(1);

			var rows = getTableBodyRows(page.getByRole(AriaRole.GRID));
			assertThat(rows)
				.singleElement()
				.extracting(row -> row.getByRole(AriaRole.GRIDCELL).all())
				.extracting(
					cells -> cells.get(0).textContent(),
					cells -> cells.get(1).textContent(),
					cells -> cells.get(2).textContent()
				)
				.containsExactly(otherClaim.claimNumber, "Other", otherClaim.clientName);
		}
		finally {
			QuarkusTransaction.requiringNew().run(() -> Claim.deleteById(otherClaim.id));
		}
	}

	private List<String> getCategoryCells(Page page) {
		return getTableBodyRows(page.getByRole(AriaRole.GRID)).stream()
			.map(row -> row.getByRole(AriaRole.GRIDCELL).all().get(1).textContent())
			.toList();
	}

	// A live locator for the body rows, for retrying Playwright assertions (getTableBodyRows takes a one-shot snapshot)
	private Locator getTableBodyRowsLocator(Page page) {
		return page.getByRole(AriaRole.GRID)
			.getByRole(AriaRole.ROWGROUP)
			.nth(1)
			.getByRole(AriaRole.ROW);
	}

	private List<Locator> getTableBodyRows(Locator table) {
		// Rowgroup 1 is the header row
		// Rowgroup 2 is the body rows
		var rowGroups = table.getByRole(AriaRole.ROWGROUP).all();
		assertThat(rowGroups)
			.isNotNull()
			.hasSize(2);

		return rowGroups.get(1).getByRole(AriaRole.ROW).all();
	}

	private Locator getAndVerifyClaimsTable(Page page, int expectedNumRows) {
		var table = page.getByRole(AriaRole.GRID);
		assertThat(table).isNotNull();

		var tableBodyRows = getTableBodyRows(table);
		assertThat(tableBodyRows)
			.isNotNull()
			.hasSize(expectedNumRows);

		return table;
	}

	private Locator getAndVerifyClaimsTable(int expectedNumRows, String testName) {
		return getAndVerifyClaimsTable(loadPage(testName), expectedNumRows);
	}

	private Page loadPage(String testName) {
		return loadPage("ClaimsList", testName);
	}
}
