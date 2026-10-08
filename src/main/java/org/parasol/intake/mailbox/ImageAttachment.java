package org.parasol.intake.mailbox;

import java.util.Arrays;
import java.util.Objects;

import org.parasol.claim.model.ClaimImageContentType;

/**
 * An image attachment of an inbound email that can be stored as a claim photo.
 * <p>
 *   The {@code data} array isn't copied, either when the record is created or when it's read, to save memory: images
 *   are large, and a copy would at least double an email's image memory. So the record is only as immutable as its
 *   callers make it: don't modify the array you pass in or the one {@link #data()} returns.
 * </p>
 *
 * @param fileName The attachment's file name, or a generated one if the email didn't give one
 * @param contentType The image format, from the claim image allow-list
 * @param data The image bytes, never empty. Not copied; don't modify it
 */
public record ImageAttachment(String fileName, ClaimImageContentType contentType, byte[] data) {
	// No defensive copies of data, deliberately, to save memory. Images are large: up to 10 MB each and 10 per email at
	// the configured limits (IntakeConfig.Limits), so one copy is up to 100 MB more per email, multiplied by the runs
	// Flow works on in parallel, plus a copy per data() call. That would at least double an email's image memory, to
	// guard against changes no code makes:
	// - a real guarantee needs two clones: one here (against the caller changing the array it passed in) and one in
	//   data() (against a reader changing the one it gets back). One alone guards only one side
	// - MimeMessageParser passes the array from readNBytes and keeps no reference
	// - the intake stores the bytes as-is through ClaimImage.store, whose data field is mutable anyway
	public ImageAttachment {
		Objects.requireNonNull(fileName, "fileName");
		Objects.requireNonNull(contentType, "contentType");
		Objects.requireNonNull(data, "data");
	}

	// The generated equals, hashCode and toString compare and print the array reference, not its bytes

	@Override
	public boolean equals(Object other) {
		return (other instanceof ImageAttachment(var otherFileName, var otherContentType, var otherData))
			&& this.fileName.equals(otherFileName)
			&& (this.contentType == otherContentType)
			&& Arrays.equals(this.data, otherData);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.fileName, this.contentType, Arrays.hashCode(this.data));
	}

	@Override
	public String toString() {
		return "ImageAttachment[fileName=%s, contentType=%s, size=%d]".formatted(this.fileName, this.contentType, this.data.length);
	}
}