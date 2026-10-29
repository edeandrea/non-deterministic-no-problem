import { format } from 'util';

// Fails any test that logs through console.error or console.warn. React's act() warnings and jsdom's own errors (a failed
// XHR, for one) arrive there, and a green run used to bury them in the Maven output (#269). Each call is still printed.
const logged: string[] = [];

beforeEach(() => {
  logged.length = 0;

  (['error', 'warn'] as const).forEach(level => {
    const original = console[level];

    jest.spyOn(console, level).mockImplementation((...args: unknown[]) => {
      logged.push(`console.${level}: ${format(...args)}`);
      original(...args);
    });
  });
});

afterEach(() => {
  jest.restoreAllMocks();
  expect(logged).toEqual([]);
});
