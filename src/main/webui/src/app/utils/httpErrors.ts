import axios from 'axios';

/**
 * Whether a failed request means the resource doesn't exist: the API answers an unknown claim or image with an
 * RFC 9457 Problem Details `404`. Network errors, other statuses and aborted requests are not "not found".
 */
export const isNotFound = (error: unknown): boolean =>
    axios.isAxiosError(error) && (error.response?.status === 404);

/**
 * Whether a failed request lost an optimistic lock: the review decision endpoint answers a stale version, a claim no
 * longer open for review, or a run that isn't waiting with an RFC 9457 Problem Details `409`.
 */
export const isConflict = (error: unknown): boolean =>
    axios.isAxiosError(error) && (error.response?.status === 409);
