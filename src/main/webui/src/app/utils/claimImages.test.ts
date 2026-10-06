import { ClaimImage, imageSource, imagesToDisplay } from '@app/utils/claimImages';

const image = (id: number, kind: ClaimImage['kind']): ClaimImage => ({
    id,
    kind,
    file_name: `${kind.toLowerCase()}-${id}.jpg`,
    content_type: 'image/jpeg',
    url: `/api/db/claims/1/images/${id}`,
});

describe('imagesToDisplay', () => {
    it('shows originals in the Documents tab and processed images in the panel', () => {
        const original = image(1, 'ORIGINAL');
        const processed = image(2, 'PROCESSED');

        expect(imagesToDisplay([original, processed])).toEqual({
            originalImages: [original],
            panelImages: [processed],
        });
    });

    it('falls back to the originals in the panel when there are no processed images', () => {
        const original = image(1, 'ORIGINAL');

        expect(imagesToDisplay([original])).toEqual({
            originalImages: [original],
            panelImages: [original],
        });
    });

    it('shows processed images in the panel even without originals', () => {
        const processed = image(2, 'PROCESSED');

        expect(imagesToDisplay([processed])).toEqual({
            originalImages: [],
            panelImages: [processed],
        });
    });

    it('shows nothing for a claim without images', () => {
        expect(imagesToDisplay([])).toEqual({
            originalImages: [],
            panelImages: [],
        });
    });
});

describe('imageSource', () => {
    it.each([
        ['http://localhost:8081/api', 'http://localhost:8081/api/db/claims/1/images/5'],
        ['https://parasol.example.com/api', 'https://parasol.example.com/api/db/claims/1/images/5'],
        ['https://parasol.example.com:8443/api/', 'https://parasol.example.com:8443/api/db/claims/1/images/5'],
    ])('resolves against the origin of %s', (backendApiUrl, expected) => {
        expect(imageSource('/api/db/claims/1/images/5', backendApiUrl)).toBe(expected);
    });

    it.each(['', '/api', 'not a url'])('leaves the URL unchanged for the backend URL %p', (backendApiUrl) => {
        expect(imageSource('/api/db/claims/1/images/5', backendApiUrl)).toBe('/api/db/claims/1/images/5');
    });
});
