package org.parasol.intake.reply;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.parasol.claim.model.Claim;
import org.parasol.intake.IntakeTestProfile;
import org.parasol.intake.MissingItem;
import org.parasol.intake.mailbox.SkippedAttachment;
import org.parasol.intake.mailbox.SkippedAttachment.Reason;

import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.Variant;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

// Renders each template's text and HTML variants the way the mailer does (selecting the variant on the template
// instance), without sending anything. The HTML variant is checked through its visible text (jsoup), so both variants
// get the same content assertions. Sending, the subject and the headers are IntakeReplySenderTests.
@QuarkusTest
@TestProfile(IntakeTestProfile.class)
class IntakeTemplatesTests {
	private static final Optional<String> NO_NAME = Optional.empty();
	private static final String CLAIM_NUMBER = "CLM09999999";
	private static final Pattern ANY_CLAIM_NUMBER = Pattern.compile("CLM\\d{8}");
	private static final Pattern ANY_MESSAGE_ID = Pattern.compile("<[^>\\s]+@[^>\\s]+>");
	private static final String DEPARTMENT = "Parasol Insurance Claims Department";
	private static final String PHONE = "Phone: 1-800-CAR-SAFE";

	@Test
	void everyTemplateGreetsTheCustomerByName() {
		var name = Optional.of("Marty McFly");

		allTemplates(name)
			.flatMap(template -> renderBoth(template).stream())
			.forEach(text -> assertThat(text)
				.startsWith("Dear Marty McFly,"));
	}

	@Test
	void everyTemplateFallsBackToDearCustomer() {
		allTemplates(NO_NAME)
			.flatMap(template -> renderBoth(template).stream())
			.forEach(text -> assertThat(text)
				.startsWith("Dear Customer,"));
	}

	// The name can come from the customer's own From header, so the HTML variant must escape it
	@Test
	void customerNameIsEscapedInHtml() {
		var html = render(IntakeTemplates.notAClaim(Optional.of("<b>Biff</b>")), Variant.TEXT_HTML);

		assertThat(html)
			.doesNotContain("<b>Biff</b>")
			.contains("Dear &lt;b&gt;Biff&lt;/b&gt;,");
	}

	@Test
	void receivedFinalReviewShowsPendingReviewAndTheNextStep() {
		renderBoth(IntakeTemplates.receivedFinalReview(NO_NAME, CLAIM_NUMBER, "A rear-end collision on Route 9.", false, true, List.of()))
			.forEach(text -> assertThat(text)
				.contains(
					"created claim %s".formatted(CLAIM_NUMBER),
					"Status: Pending Review",
					"A rear-end collision on Route 9.",
					"A claims processor will do a final check of your claim, and we'll be in touch.",
					DEPARTMENT,
					PHONE)
				.doesNotContain("We didn't receive any photos", "We couldn't use these attachments", "latest information"));
	}

	@Test
	void receivedFinalReviewDuringReviewSaysTheLatestInformationWasAdded() {
		renderBoth(IntakeTemplates.receivedFinalReview(NO_NAME, CLAIM_NUMBER, "Summary", true, true, List.of()))
			.forEach(text -> assertThat(text)
				.contains("We've added your latest information to claim %s, which is still in final review.".formatted(CLAIM_NUMBER))
				.doesNotContain("created claim"));
	}

	@Test
	void claimInProcessThanksTheCustomer() {
		renderBoth(IntakeTemplates.claimInProcess(NO_NAME, CLAIM_NUMBER))
			.forEach(text -> assertThat(text)
				.contains("Your claim %s has been processed and is now In Process.".formatted(CLAIM_NUMBER), DEPARTMENT, PHONE));
	}

	@Test
	void missingInformationListsExactlyTheMissingItems() {
		var missingItems = List.of(MissingItem.INCIDENT_DATE, MissingItem.LOCATION);

		assertThat(listItems(IntakeTemplates.missingInformation(NO_NAME, CLAIM_NUMBER, missingItems, true, List.of())))
			.containsExactly("incident date", "location");

		renderBoth(IntakeTemplates.missingInformation(NO_NAME, CLAIM_NUMBER, missingItems, true, List.of()))
			.forEach(text -> assertThat(text)
				.contains(
					"claim %s (Status: Pending Information)".formatted(CLAIM_NUMBER),
					"Please reply directly to this email with the requested information.",
					DEPARTMENT,
					PHONE)
				.doesNotContain("incident description", "category"));
	}

	@Test
	void stillMissingInformationListsOnlyWhatIsStillMissing() {
		var missingItems = List.of(MissingItem.INCIDENT_DESCRIPTION, MissingItem.CATEGORY);

		assertThat(listItems(IntakeTemplates.stillMissingInformation(NO_NAME, CLAIM_NUMBER, missingItems, true, List.of())))
			.containsExactly("incident description", "category");

		renderBoth(IntakeTemplates.stillMissingInformation(NO_NAME, CLAIM_NUMBER, missingItems, true, List.of()))
			.forEach(text -> assertThat(text)
				.contains(
					"claim %s (Status: Pending Information)".formatted(CLAIM_NUMBER),
					"We still need the following details",
					"Please reply directly to this email with the remaining items.")
				.doesNotContain("incident date", "location"));
	}

	@Test
	void noPhotosAddsThePhotosNote() {
		Stream.of(
				IntakeTemplates.receivedFinalReview(NO_NAME, CLAIM_NUMBER, "Summary", false, false, List.of()),
				IntakeTemplates.missingInformation(NO_NAME, CLAIM_NUMBER, List.of(MissingItem.LOCATION), false, List.of()),
				IntakeTemplates.stillMissingInformation(NO_NAME, CLAIM_NUMBER, List.of(MissingItem.LOCATION), false, List.of()))
			.flatMap(template -> renderBoth(template).stream())
			.forEach(text -> assertThat(text)
				.contains("We didn't receive any photos of the damage. If you have some, please reply to this email with them attached."));
	}

	@Test
	void skippedAttachmentsAreListedWithEveryReason() {
		var skipped = List.of(
			new SkippedAttachment("policy.pdf", "application/pdf", Reason.NOT_AN_IMAGE),
			new SkippedAttachment("huge.jpg", "image/jpeg", Reason.TOO_LARGE),
			new SkippedAttachment("photo-11.png", "image/png", Reason.TOO_MANY),
			new SkippedAttachment("blank.gif", "image/gif", Reason.EMPTY));

		Stream.of(
				IntakeTemplates.receivedFinalReview(NO_NAME, CLAIM_NUMBER, "Summary", false, true, skipped),
				IntakeTemplates.missingInformation(NO_NAME, CLAIM_NUMBER, List.of(MissingItem.LOCATION), true, skipped),
				IntakeTemplates.stillMissingInformation(NO_NAME, CLAIM_NUMBER, List.of(MissingItem.LOCATION), true, skipped))
			.flatMap(template -> renderBoth(template).stream())
			.forEach(text -> assertThat(text)
				.contains(
					"We couldn't use these attachments:",
					"policy.pdf: it isn't a photo we can accept (we accept JPEG, PNG, GIF and WebP images).",
					"huge.jpg: it's larger than we can accept.",
					"photo-11.png: it's over the number of photos we accept in one email.",
					"blank.gif: it was empty."));
	}

	// File names come from the customer's email, so the HTML variant must escape them
	@Test
	void skippedAttachmentFileNamesAreEscapedInHtml() {
		var skipped = List.of(new SkippedAttachment("<script>alert(1)</script>.pdf", "application/pdf", Reason.NOT_AN_IMAGE));
		var html = render(IntakeTemplates.missingInformation(NO_NAME, CLAIM_NUMBER, List.of(MissingItem.LOCATION), true, skipped), Variant.TEXT_HTML);

		assertThat(html)
			.doesNotContain("<script>")
			.contains("&lt;script&gt;alert(1)&lt;/script&gt;.pdf");
	}

	@Test
	void policyInconsistencyGivesNoPolicyOrCustomerDetails() {
		var claims = seededClaims();

		renderBoth(IntakeTemplates.policyInconsistency(NO_NAME))
			.forEach(text -> assertThat(text)
				.contains("There is an inconsistency with the policy information provided.", "1-800-CAR-SAFE", DEPARTMENT)
				.doesNotContain(policyNumbers(claims))
				.doesNotContain(clientNames(claims))
				.doesNotContain(emailAddresses(claims))
				.doesNotContainPattern(ANY_CLAIM_NUMBER));
	}

	@Test
	void processingProblemLeaksNoDetails() {
		var claims = seededClaims();

		renderBoth(IntakeTemplates.processingProblem(NO_NAME))
			.forEach(text -> assertThat(text)
				.contains("encountered a problem processing it automatically", "1-800-CAR-SAFE", DEPARTMENT)
				.doesNotContainIgnoringCase("exception")
				.doesNotContainPattern(ANY_MESSAGE_ID)
				.doesNotContainPattern(ANY_CLAIM_NUMBER)
				.doesNotContain(clientNames(claims))
				.doesNotContain(policyNumbers(claims)));
	}

	@Test
	void notAClaimSaysTheInboxIsOnlyForClaims() {
		renderBoth(IntakeTemplates.notAClaim(NO_NAME))
			.forEach(text -> assertThat(text)
				.contains("This inbox is dedicated solely to handling insurance claims.", DEPARTMENT, PHONE)
				.doesNotContainPattern(ANY_CLAIM_NUMBER));
	}

	@Test
	void noMatchingClaimDisclosesNoClaimAndAsksForTheAddressOrNumber() {
		var claims = seededClaims();

		renderBoth(IntakeTemplates.noMatchingClaim(NO_NAME))
			.forEach(text -> assertThat(text)
				.contains("write from the email address registered on your claim", "include your claim number")
				.doesNotContainPattern(ANY_CLAIM_NUMBER)
				.doesNotContain(statuses(claims))
				.doesNotContain(clientNames(claims))
				.doesNotContain(emailAddresses(claims)));
	}

	@Test
	void whichClaimListsExactlyTheGivenClaimNumbers() {
		var claims = seededClaims();
		var pending = claims.subList(0, 2);
		var pendingNumbers = pending.stream()
			.map(claim -> claim.claimNumber)
			.toList();

		assertThat(listItems(IntakeTemplates.whichClaim(NO_NAME, pendingNumbers)))
			.containsExactlyElementsOf(pendingNumbers);

		renderBoth(IntakeTemplates.whichClaim(NO_NAME, pendingNumbers))
			.forEach(text -> assertThat(text)
				.satisfies(rendered -> assertThat(claimNumbersIn(rendered))
					.containsExactlyElementsOf(pendingNumbers))
				.contains("include the specific claim number")
				.doesNotContain(statuses(pending))
				.doesNotContain(clientNames(pending))
				.doesNotContain(policyNumbers(pending))
				.doesNotContain(emailAddresses(pending)));
	}

	private static Stream<MailTemplateInstance> allTemplates(Optional<String> customerName) {
		var missingItems = List.of(MissingItem.LOCATION);

		return Stream.of(
			IntakeTemplates.receivedFinalReview(customerName, CLAIM_NUMBER, "Summary", false, true, List.of()),
			IntakeTemplates.claimInProcess(customerName, CLAIM_NUMBER),
			IntakeTemplates.missingInformation(customerName, CLAIM_NUMBER, missingItems, true, List.of()),
			IntakeTemplates.stillMissingInformation(customerName, CLAIM_NUMBER, missingItems, true, List.of()),
			IntakeTemplates.processingProblem(customerName),
			IntakeTemplates.policyInconsistency(customerName),
			IntakeTemplates.notAClaim(customerName),
			IntakeTemplates.noMatchingClaim(customerName),
			IntakeTemplates.whichClaim(customerName, List.of(CLAIM_NUMBER)));
	}

	// The text variant as is, and the HTML variant's visible text
	private static List<String> renderBoth(MailTemplateInstance template) {
		var text = render(template, Variant.TEXT_PLAIN);
		var html = Jsoup.parse(render(template, Variant.TEXT_HTML)).text();

		return List.of(text, html);
	}

	private static List<String> claimNumbersIn(String text) {
		return ANY_CLAIM_NUMBER.matcher(text)
			.results()
			.map(MatchResult::group)
			.toList();
	}

	// The <li> items of the HTML variant
	private static List<String> listItems(MailTemplateInstance template) {
		return Jsoup.parse(render(template, Variant.TEXT_HTML))
			.select("li")
			.stream()
			.map(Element::text)
			.toList();
	}

	private static String render(MailTemplateInstance template, String contentType) {
		var instance = template.templateInstance();
		var variant = ((List<?>) instance.getAttribute(TemplateInstance.VARIANTS)).stream()
			.map(Variant.class::cast)
			.filter(candidate -> candidate.getContentType().equals(contentType))
			.findFirst()
			.orElseThrow();

		return instance.setAttribute(TemplateInstance.SELECTED_VARIANT, variant)
			.render();
	}

	// A lambda, not Claim::listAll: the method reference binds to PanacheEntityBase.listAll, which Panache doesn't enhance
	private static List<Claim> seededClaims() {
		return QuarkusTransaction.requiringNew()
			.call(() -> Claim.listAll());
	}

	private static String[] policyNumbers(List<Claim> claims) {
		return claims.stream()
			.map(claim -> claim.policyNumber)
			.toArray(String[]::new);
	}

	private static String[] clientNames(List<Claim> claims) {
		return claims.stream()
			.map(claim -> claim.clientName)
			.toArray(String[]::new);
	}

	private static String[] emailAddresses(List<Claim> claims) {
		return claims.stream()
			.map(claim -> claim.emailAddress)
			.toArray(String[]::new);
	}

	private static String[] statuses(List<Claim> claims) {
		return claims.stream()
			.map(claim -> claim.status)
			.distinct()
			.toArray(String[]::new);
	}
}
