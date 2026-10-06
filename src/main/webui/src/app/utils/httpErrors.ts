import axios from 'axios';

/**
 * Whether a failed request means the resource doesn't exist: the API answers an unknown claim or image with an
 * RFC 9457 Problem Details `404`. Network errors, other statuses and aborted requests are not "not found".
 */
export const isNotFound = (error: unknown): boolean =>
    axios.isAxiosError(error) && (error.response?.status === 404);
