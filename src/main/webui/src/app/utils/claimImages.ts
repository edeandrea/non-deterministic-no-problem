/**
 * Image metadata returned by `GET {backend_api_url}/db/claims/{id}/images`. `url` is root-relative
 * (`/api/db/claims/1/images/5`); use {@link imageSource} to turn it into an `<img src>`.
 */
export interface ClaimImage {
    id: number;
    kind: 'ORIGINAL' | 'PROCESSED';
    file_name: string;
    content_type: string;
    url: string;
}

export interface DisplayedImages {
    /** Customer photos, shown in the Documents tab. */
    originalImages: ClaimImage[];
    /** The right-hand panel: processed images if there are any, otherwise the originals. */
    panelImages: ClaimImage[];
}

/**
 * Splits a claim's images into what the Documents tab and the right-hand panel show.
 */
export const imagesToDisplay = (images: ClaimImage[]): DisplayedImages => {
    const originalImages = images.filter(image => image.kind === 'ORIGINAL');
    const processedImages = images.filter(image => image.kind === 'PROCESSED');

    return {
        originalImages,
        panelImages: (processedImages.length > 0) ? processedImages : originalImages,
    };
};

/**
 * Resolves an image's root-relative URL against the backend API's origin, so images load from the same server as
 * the rest of the API (in tests the UI's `backend_api_url` is baked in and can point at another port). Relative or
 * malformed backend URLs leave the image URL unchanged, which resolves against the page's own origin.
 */
export const imageSource = (imageUrl: string, backendApiUrl: string): string => {
    try {
        return new URL(imageUrl, new URL(backendApiUrl).origin).toString();
    }
    catch {
        return imageUrl;
    }
};
