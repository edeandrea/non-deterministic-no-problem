import { ClaimImage } from '@app/utils/claimImages';
import { CorrespondenceMessage, imagesWithoutMessage, initiallyExpanded, showsOriginalContent, unlinkedImages } from '@app/utils/correspondence';

const image = (id: number): ClaimImage => ({
    id,
    kind: 'ORIGINAL',
    file_name: `original-${id}.jpg`,
    content_type: 'image/jpeg',
    url: `/api/db/claims/1/images/${id}`,
});

const message = (id: number, direction: CorrespondenceMessage['direction'], images: ClaimImage[] = []): CorrespondenceMessage => ({
    id,
    direction,
    sender: (direction === 'INBOUND') ? 'jane.doe@example.com' : 'claims@parasol.com',
    subject: 'Car accident',
    date: '2026-10-30T12:00:00Z',
    text: 'Hello',
    created_claim: (direction === 'INBOUND') && (id === 10),
    images,
});

describe('imagesWithoutMessage', () => {
    it('keeps the originals that no message carries', () => {
        const carried = image(1);
        const orphan = image(2);

        expect(imagesWithoutMessage([carried, orphan], [message(10, 'INBOUND', [carried])])).toEqual([orphan]);
    });

    it('treats a message without an images key as carrying none', () => {
        const orphan = image(2);

        expect(imagesWithoutMessage([orphan], [{ ...message(10, 'OUTBOUND'), images: undefined }])).toEqual([orphan]);
    });

    it('keeps every original when there are no messages', () => {
        expect(imagesWithoutMessage([image(1), image(2)], [])).toEqual([image(1), image(2)]);
    });

    it('keeps nothing when every original is carried', () => {
        const carried = image(1);

        expect(imagesWithoutMessage([carried], [message(10, 'INBOUND', [carried])])).toEqual([]);
    });
});

describe('initiallyExpanded', () => {
    it('expands the customer messages and collapses the replies', () => {
        expect(initiallyExpanded([message(10, 'INBOUND'), message(11, 'OUTBOUND'), message(12, 'INBOUND')])).toEqual(['message-10', 'message-12']);
    });

    it('expands nothing without messages', () => {
        expect(initiallyExpanded([])).toEqual([]);
    });
});

describe('showsOriginalContent', () => {
    it('shows it for a claim without emails', () => {
        expect(showsOriginalContent([])).toBe(true);
    });

    it('shows it for a claim entered by hand, where only a reply is recorded', () => {
        expect(showsOriginalContent([message(11, 'INBOUND'), message(12, 'OUTBOUND')])).toBe(true);
    });

    it('does not repeat the email that created the claim', () => {
        expect(showsOriginalContent([message(10, 'INBOUND'), message(12, 'OUTBOUND')])).toBe(false);
    });
});

describe('unlinkedImages', () => {
    it('shows a claim entered by hand with its own photos, and a reply\'s photos only under the reply', () => {
        const own = image(1);
        const replied = image(2);

        expect(unlinkedImages([own, replied], [message(11, 'INBOUND', [replied])])).toEqual({ original: [own], other: [] });
    });

    it('puts the photos no email carried in their own item when an email created the claim', () => {
        const carried = image(1);
        const orphan = image(2);

        expect(unlinkedImages([carried, orphan], [message(10, 'INBOUND', [carried])])).toEqual({ original: [], other: [orphan] });
    });

    it('shows every photo with the original content of a claim without emails', () => {
        expect(unlinkedImages([image(1), image(2)], [])).toEqual({ original: [image(1), image(2)], other: [] });
    });
});
