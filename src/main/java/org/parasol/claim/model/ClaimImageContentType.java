package org.parasol.claim.model;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The image formats a {@link ClaimImage} may be stored as. This is an allow-list: the image endpoint serves the bytes
 * back with this content type, so anything that a browser would render as active content (HTML, SVG, ...) must never be
 * accepted.
 * <p>
 * The database stores the constant name ({@code JPEG}). JSON uses the {@link #mediaType() media type}
 * ({@code "image/jpeg"}).
 */
public enum ClaimImageContentType {
	JPEG("image/jpeg", "image/jpg", "image/pjpeg"),
	PNG("image/png"),
	GIF("image/gif"),
	WEBP("image/webp");

	private final String mediaType;
	private final Set<String> acceptedMediaTypes;

	ClaimImageContentType(String mediaType, String... aliases) {
		this.mediaType = mediaType;
		this.acceptedMediaTypes = Stream.concat(Stream.of(mediaType), Stream.of(aliases))
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * The canonical media type, used for the {@code Content-Type} header and as the JSON representation.
	 *
	 * @return the media type, e.g. {@code "image/jpeg"}
	 */
	@JsonValue
	public String mediaType() {
		return this.mediaType;
	}

	/**
	 * Finds the content type for a media type, ignoring case, surrounding whitespace and any parameters
	 * ({@code "image/JPEG; name=photo.jpg"} matches {@link #JPEG}). Common non-canonical aliases such as
	 * {@code image/jpg} are accepted.
	 *
	 * @param mediaType a media type, possibly with parameters
	 * @return the matching content type, or empty if the media type isn't an allowed image type
	 */
	public static Optional<ClaimImageContentType> find(String mediaType) {
		return Optional.ofNullable(mediaType)
			.map(type -> type.split(";", 2)[0])
			.map(String::strip)
			.map(type -> type.toLowerCase(Locale.ROOT))
			.flatMap(type -> Stream.of(values())
				.filter(contentType -> contentType.acceptedMediaTypes.contains(type))
				.findFirst());
	}

	/**
	 * Resolves the content type for a media type.
	 *
	 * @param mediaType a media type, possibly with parameters
	 * @return the matching content type
	 * @throws UnsupportedClaimImageContentTypeException if the media type isn't an allowed image type
	 */
	public static ClaimImageContentType fromMediaType(String mediaType) {
		return find(mediaType)
			.orElseThrow(() -> new UnsupportedClaimImageContentTypeException(mediaType));
	}
}
