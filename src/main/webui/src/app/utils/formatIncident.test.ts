import { formatIncident, formatIncidentDate, formatIncidentTime } from '@app/utils/formatIncident';

describe('formatIncidentDate', () => {
    it.each([
        ['1955-01-02', 'January 2, 1955'],
        ['2024-02-03', 'February 3, 2024'],
        ['2023-12-31', 'December 31, 2023'],
    ])('formats %s as %s', (isoDate, expected) => {
        expect(formatIncidentDate(isoDate)).toBe(expected);
    });

    it.each([undefined, null, '', '1955-1-2', '02/01/1955', 'January 2nd, 1955', '2024-13-01', '2024-00-10', '2024-01-32', '2024-01-00'])('returns undefined for %p', (isoDate) => {
        expect(formatIncidentDate(isoDate)).toBeUndefined();
    });
});

describe('formatIncidentTime', () => {
    it.each([
        ['15:30', '3:30 PM'],
        ['15:30:00', '3:30 PM'],
        ['15:30:45.123', '3:30 PM'],
        ['00:05', '12:05 AM'],
        ['12:00', '12:00 PM'],
        ['11:59', '11:59 AM'],
        ['23:59:59', '11:59 PM'],
    ])('formats %s as %s', (isoTime, expected) => {
        expect(formatIncidentTime(isoTime)).toBe(expected);
    });

    it.each([undefined, null, '', '3:30 PM', '1530', 'noon', '24:00', '25:00', '12:60'])('returns undefined for %p', (isoTime) => {
        expect(formatIncidentTime(isoTime)).toBeUndefined();
    });
});

describe('formatIncident', () => {
    it('combines date and time', () => {
        expect(formatIncident('1955-01-02', '15:30:00')).toBe('January 2, 1955 at 3:30 PM');
    });

    it('shows only the date when there is no time', () => {
        expect(formatIncident('2024-02-03', undefined)).toBe('February 3, 2024');
    });

    it('returns undefined when there is no date, even with a time', () => {
        expect(formatIncident(undefined, '15:30')).toBeUndefined();
    });
});
