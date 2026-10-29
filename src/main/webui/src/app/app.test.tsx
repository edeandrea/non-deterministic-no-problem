import * as React from 'react';
import App from '@app/index';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event'
import '@testing-library/jest-dom'
import axios from 'axios';

describe('App tests', () => {
  // There is no backend under jsdom. The claims request never settles, so ClaimsList renders an empty table and no state
  // update lands after render (a settled request would update outside act, and a real one fails with a network error).
  beforeEach(() => {
    jest.spyOn(axios, 'get').mockReturnValue(new Promise(() => {}));
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  test('should render default App component', () => {
    const { asFragment } = render(<App />);

    expect(asFragment()).toMatchSnapshot();
  });

  it('should render a nav-toggle button', () => {
    render(<App />);

    expect(screen.getByRole('button', { name: "Global navigation"})).toBeVisible();
  });

  it('should expand the sidebar on larger viewports', () => {
    render(<App />);

    act(() => {
      window.dispatchEvent(new Event('resize'));
    });

    expect(screen.getByRole('link', { name: "Dashboard"})).toBeVisible();
  });

  it('should hide the sidebar when clicking the nav-toggle button', async () => {
    const user = userEvent.setup()

    render(<App />);

    act(() => {
      window.dispatchEvent(new Event('resize'));
    });
    const button = screen.getByRole('button', { name: "Global navigation"})

    expect(screen.getByRole('link', { name: "Dashboard"})).toBeVisible();

    await user.click(button);

    expect(screen.queryByRole('link', { name: "Dashboard"})).not.toBeInTheDocument();
  });
});
