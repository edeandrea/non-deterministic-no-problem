import * as React from 'react';
import ImageGallery from "react-image-gallery";


interface Image {
    file_name: string;
    url: string;
}

interface ImageCarouselProps {
    images: Image[];
}

const ImageCarousel: React.FunctionComponent<ImageCarouselProps> = ({ images }) => {
    const transformedImages = images.map(image => ({
        original: image.url,
        thumbnail: image.url,
        thumbnailClass: "image-gallery-thumbnail",
        originalClass: "image-gallery-original",
        originalAlt: image.file_name,
        thumbnailAlt: image.file_name,
    }));
    return (
        <ImageGallery items={transformedImages} />
    );
}

export { ImageCarousel };
