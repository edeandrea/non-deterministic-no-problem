package org.parasol.claim.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ClaimImageContentTypeTests {
	@ParameterizedTest(name = "[{index}] {arguments}")
	@CsvSource(delimiter = '|', useHeadersInDisplayName = true, textBlock = """
		MEDIA_TYPE                    | EXPECTED
		image/jpeg                    | JPEG
		image/jpg                     | JPEG
		image/pjpeg                   | JPEG
		IMAGE/JPEG                    | JPEG
		'  image/png  '               | PNG
		image/png; name=photo.png     | PNG
		image/gif                     | GIF
		image/webp                    | WEBP
		""")
	void findsAllowedImageTypes(String mediaType, ClaimImageContentType expected) {
		assertThat(ClaimImageContentType.find(mediaType))
			.hasValue(expected);
	}

	@ParameterizedTest(name = "[{index}] {arguments}")
	@NullAndEmptySource
	@ValueSource(strings = { "text/html", "image/svg+xml", "application/xhtml+xml", "application/octet-stream", "image/*", "image", "jpeg" })
	void rejectsAnythingElse(String mediaType) {
		assertThat(ClaimImageContentType.find(mediaType))
			.isEmpty();

		assertThatThrownBy(() -> ClaimImageContentType.fromMediaType(mediaType))
			.isInstanceOf(UnsupportedClaimImageContentTypeException.class)
			.hasMessage("Unsupported claim image content type: %s", mediaType);
	}

	@ParameterizedTest(name = "[{index}] {arguments}")
	@CsvSource(delimiter = '|', textBlock = """
		JPEG | image/jpeg
		PNG  | image/png
		GIF  | image/gif
		WEBP | image/webp
		""")
	void mediaTypeIsCanonical(ClaimImageContentType contentType, String expectedMediaType) {
		assertThat(contentType.mediaType())
			.isEqualTo(expectedMediaType);
	}
}