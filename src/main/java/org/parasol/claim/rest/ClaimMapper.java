package org.parasol.claim.rest;

import java.util.List;

import jakarta.ws.rs.core.UriBuilder;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;
import org.mapstruct.ReportingPolicy;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimImage;

/**
 * Maps the claim aggregate's entities ({@link Claim}, {@link ClaimImage}) to the DTOs the REST API returns, so the JSON
 * contract doesn't follow the database schema.
 * <p>
 * A new entity column stays out of the API until a DTO field is added for it (unmapped source properties are ignored),
 * and a DTO field nothing maps to fails the build ({@code unmappedTargetPolicy = ERROR}). The image mapping only reads
 * metadata fields, so the lazily loaded image bytes are never fetched.
 */
@Mapper(componentModel = ComponentModel.JAKARTA_CDI, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ClaimMapper {
	ClaimDetails toDetails(Claim claim);

	List<ClaimDetails> toDetails(List<Claim> claims);

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