package org.parasol.claim.rest;

import java.util.List;

import jakarta.ws.rs.core.UriBuilder;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;
import org.parasol.claim.model.ClaimImage;

/**
 * Maps {@link ClaimImage} entities to the {@link ClaimImageMetadata} returned by the API. It only reads metadata
 * fields, so the lazily loaded image bytes are never fetched.
 */
@Mapper(componentModel = ComponentModel.JAKARTA_CDI)
interface ClaimImageMapper {
	@Mapping(target = "contentType", expression = "java(image.contentType.mediaType())")
	@Mapping(target = "url", expression = "java(imageUrl(claimId, image.id))")
	ClaimImageMetadata toMetadata(ClaimImage image, @Context long claimId);

	List<ClaimImageMetadata> toMetadata(List<ClaimImage> images, @Context long claimId);

	default String imageUrl(long claimId, long imageId) {
		return UriBuilder.fromResource(ClaimImageResource.class)
			.path(ClaimImageResource.class, "getImage")
			.build(claimId, imageId)
			.toString();
	}
}