import config from '@app/config';
import { ClaimImage, imageSource } from '@app/utils/claimImages';
import * as React from 'react';
import ImageGallery from "react-image-gallery";


interface ImageCarouselProps {
    images: ClaimImage[];
}

const ImageCarousel: React.FunctionComponent<ImageCarouselProps> = ({ images }) => {
    const transformedImages = images.map(image => {
        const source = imageSource(image.url, config.backend_api_url);

        return {
            original: source,
            thumbnail: source,
            thumbnailClass: "image-gallery-thumbnail",
            originalClass: "image-gallery-original",
            originalAlt: image.file_name,
            thumbnailAlt: image.file_name,
        };
    });

    return (
        <ImageGallery items={transformedImages} />
    );
}

export { ImageCarousel };
