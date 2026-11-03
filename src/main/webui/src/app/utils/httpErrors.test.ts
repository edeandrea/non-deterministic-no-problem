import { AxiosError, AxiosHeaders, CanceledError } from 'axios';
import { isConflict, isNotFound } from '@app/utils/httpErrors';

const httpError = (status: number): AxiosError => new AxiosError(
    `Request failed with status code ${status}`,
    AxiosError.ERR_BAD_REQUEST,
    undefined,
    undefined,
    { status, statusText: '', headers: {}, config: { headers: new AxiosHeaders() }, data: {} },
);

describe('isNotFound', () => {
    it('is true for a 404 response', () => {
        expect(isNotFound(httpError(404))).toBe(true);
    });

    it.each([400, 403, 500, 503])('is false for a %i response', (status) => {
        expect(isNotFound(httpError(status))).toBe(false);
    });

    it('is false for a network error without a response', () => {
        expect(isNotFound(new AxiosError('Network Error', AxiosError.ERR_NETWORK))).toBe(false);
    });

    it('is false for an aborted request', () => {
        expect(isNotFound(new CanceledError())).toBe(false);
    });

    it.each([new Error('boom'), 'boom', undefined, null])('is false for %p', (error) => {
        expect(isNotFound(error)).toBe(false);
    });
});

describe('isConflict', () => {
    it('is true for a 409 response', () => {
        expect(isConflict(httpError(409))).toBe(true);
    });

    it.each([400, 404, 500, 503])('is false for a %i response', (status) => {
        expect(isConflict(httpError(status))).toBe(false);
    });

    it('is false for a network error without a response', () => {
        expect(isConflict(new AxiosError('Network Error', AxiosError.ERR_NETWORK))).toBe(false);
    });

    it('is false for an aborted request', () => {
        expect(isConflict(new CanceledError())).toBe(false);
    });

    it.each([new Error('boom'), 'boom', undefined, null])('is false for %p', (error) => {
        expect(isConflict(error)).toBe(false);
    });
});
