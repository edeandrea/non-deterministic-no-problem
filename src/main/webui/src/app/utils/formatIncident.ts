const MONTHS = [
    'January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December'
];

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const ISO_TIME = /^(\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?$/;

// Formats an ISO date ("1955-01-02") as "January 2, 1955".
// Parsed by hand on purpose: new Date("1955-01-02") is UTC midnight, which renders as the previous day west of UTC.
export const formatIncidentDate = (isoDate?: string | null): string | undefined => {
    const match = isoDate ? ISO_DATE.exec(isoDate) : null;

    if (!match) {
        return undefined;
    }

    const month = Number(match[2]);
    const day = Number(match[3]);

    if (month < 1 || month > 12 || day < 1 || day > 31) {
        return undefined;
    }

    return `${MONTHS[month - 1]} ${day}, ${match[1]}`;
};

// Formats an ISO time ("15:30" or "15:30:00") as "3:30 PM".
// Built by hand rather than with Intl, whose output varies by browser (e.g. a narrow no-break space before "PM").
export const formatIncidentTime = (isoTime?: string | null): string | undefined => {
    const match = isoTime ? ISO_TIME.exec(isoTime) : null;

    if (!match) {
        return undefined;
    }

    const hours = Number(match[1]);
    const minutes = Number(match[2]);

    if (hours > 23 || minutes > 59) {
        return undefined;
    }

    const hours12 = (hours % 12 === 0) ? 12 : hours % 12;
    const period = (hours < 12) ? 'AM' : 'PM';

    return `${hours12}:${match[2]} ${period}`;
};

// "January 2, 1955 at 3:30 PM", "February 3, 2024" (no time), or undefined when there is no valid date.
export const formatIncident = (isoDate?: string | null, isoTime?: string | null): string | undefined => {
    const date = formatIncidentDate(isoDate);
    const time = formatIncidentTime(isoTime);

    if (!date) {
        return undefined;
    }

    return time ? `${date} at ${time}` : date;
};
