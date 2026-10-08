package org.parasol.intake.reply;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.parasol.intake.MissingItem;
import org.parasol.intake.mailbox.SkippedAttachment;

import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.qute.CheckedTemplate;

/**
 * The fixed intake replies, as type-safe Qute mail templates. Each has an HTML and a text variant under
 * {@code templates/IntakeTemplates/}, and every one signs off with {@code parasol.claims-department.*}. Send them with
 * {@link IntakeReplySender#sendTemplateReply}.
 * <p>
 *   The photos note and the skipped-attachments list come from the shared {@code attachmentNotes} fragment, so the
 *   templates that take {@code hasPhotos} and {@code skippedAttachments} word them the same way.
 * </p>
 */
@CheckedTemplate(basePath = "IntakeTemplates")
public class IntakeTemplates {
	/**
	 * The claim is complete and waiting for the reviewer's final check.
	 *
	 * @param customerName Who to greet, from {@link CustomerName#forReply}. Without one the reply greets "Customer"
	 * @param claimNumber The claim's number
	 * @param summary What we recorded about the incident
	 * @param isReplyDuringReview Whether this answers a reply to a claim already in {@code Pending Review}: it then says
	 *   the latest information was added, rather than that the claim was created
	 * @param hasPhotos Whether the claim has any photos. Without any, the reply asks for them
	 * @param skippedAttachments The attachments of the customer's email that weren't kept, each with why
	 * @return The reply
	 */
	public static native MailTemplateInstance receivedFinalReview(
		Optional<String> customerName,
		String claimNumber,
		String summary,
		boolean isReplyDuringReview,
		boolean hasPhotos,
		List<SkippedAttachment> skippedAttachments);

	/**
	 * Thanks the customer once the reviewer marks the claim ready.
	 *
	 * @param customerName Who to greet, from {@link CustomerName#forReply}. Without one the reply greets "Customer"
	 * @param claimNumber The claim's number
	 * @return The reply
	 */
	public static native MailTemplateInstance claimInProcess(Optional<String> customerName, String claimNumber);

	/**
	 * Asks for the details a new claim is missing, or the ones the reviewer asked for.
	 *
	 * @param customerName Who to greet, from {@link CustomerName#forReply}. Without one the reply greets "Customer"
	 * @param claimNumber The claim's number
	 * @param missingItems Exactly the items to ask for, in the order to list them
	 * @param hasPhotos Whether the claim has any photos. Without any, the reply asks for them
	 * @param skippedAttachments The attachments of the customer's email that weren't kept, each with why
	 * @return The reply
	 */
	public static native MailTemplateInstance missingInformation(
		Optional<String> customerName,
		String claimNumber,
		Collection<MissingItem> missingItems,
		boolean hasPhotos,
		List<SkippedAttachment> skippedAttachments);

	/**
	 * Asks again for what a customer's reply still didn't supply.
	 *
	 * @param customerName Who to greet, from {@link CustomerName#forReply}. Without one the reply greets "Customer"
	 * @param claimNumber The claim's number
	 * @param missingItems Exactly the items still missing, in the order to list them
	 * @param hasPhotos Whether the claim has any photos. Without any, the reply asks for them
	 * @param skippedAttachments The attachments of the customer's email that weren't kept, each with why
	 * @return The reply
	 */
	public static native MailTemplateInstance stillMissingInformation(
		Optional<String> customerName,
		String claimNumber,
		Collection<MissingItem> missingItems,
		boolean hasPhotos,
		List<SkippedAttachment> skippedAttachments);

	/**
	 * The email couldn't be processed automatically. It says nothing about why.
	 *
	 * @param customerName Who to greet, from {@link CustomerName#forReply}. Without one the reply greets "Customer"
	 * @return The reply
	 */
	public static native MailTemplateInstance processingProblem(Optional<String> customerName);

	/**
	 * The policy details didn't match. It says nothing about what didn't match.
	 *
	 * @param customerName Who to greet, from {@link CustomerName#forReply}. Without one the reply greets "Customer"
	 * @return The reply
	 */
	public static native MailTemplateInstance policyInconsistency(Optional<String> customerName);

	/**
	 * The email isn't about an insurance claim.
	 *
	 * @param customerName Who to greet, from {@link CustomerName#forReply}. Without one the reply greets "Customer"
	 * @return The reply
	 */
	public static native MailTemplateInstance notAClaim(Optional<String> customerName);

	/**
	 * The email matched no claim for its sender: a wrong-sender reply, or a follow-up that matches nothing. It discloses
	 * nothing about any claim, so the reply has no claim-number subject prefix either.
	 *
	 * @param customerName Who to greet, from {@link CustomerName#forReply}. Without one the reply greets "Customer"
	 * @return The reply
	 */
	public static native MailTemplateInstance noMatchingClaim(Optional<String> customerName);

	/**
	 * The sender has several pending claims and the email doesn't say which one it's about. Send it only to the address
	 * already on those claims.
	 *
	 * @param customerName Who to greet, from {@link CustomerName#forReply}. Without one the reply greets "Customer"
	 * @param pendingClaimNumbers The sender's own pending claim numbers
	 * @return The reply
	 */
	public static native MailTemplateInstance whichClaim(Optional<String> customerName, Collection<String> pendingClaimNumbers);
}
