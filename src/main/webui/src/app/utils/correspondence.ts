import { ClaimImage } from '@app/utils/claimImages';

/**
 * One email of a claim's correspondence, returned by `GET {backend_api_url}/db/claims/{id}/correspondence`.
 */
export interface CorrespondenceMessage {
    id: number;
    direction: 'INBOUND' | 'OUTBOUND';
    sender?: string;
    subject?: string;
    date: string;
    text?: string;
    /** Whether this email created the claim. */
    created_claim: boolean;
    /** Left out of the JSON when the email carried no photos. */
    images?: ClaimImage[];
}

/**
 * The customer photos that no message carries (seeded images, or ones stored before emails were linked to them), so the
 * Documents tab can still show them.
 */
export const imagesWithoutMessage = (originalImages: ClaimImage[], messages: CorrespondenceMessage[]): ClaimImage[] => {
    const carried = new Set(messages.flatMap(message => (message.images ?? []).map(image => image.id)));

    return originalImages.filter(image => !carried.has(image.id));
};

/**
 * The ids of the messages that start expanded: the customer's, so their photos are visible when the tab opens. The
 * replies start collapsed.
 */
export const initiallyExpanded = (messages: CorrespondenceMessage[]): string[] =>
    messages
        .filter(message => message.direction === 'INBOUND')
        .map(message => `message-${message.id}`);

/**
 * Whether the claim's own content (subject, body, photos) needs its own item: it does unless an email created the claim. A claim
 * with no emails (seeded) or one an adjustor entered by hand, with only replies recorded, has its content in no message.
 */
export const showsOriginalContent = (messages: CorrespondenceMessage[]): boolean =>
    !messages.some(message => message.created_claim);
