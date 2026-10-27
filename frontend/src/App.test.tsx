// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { DEMO_SESSION_KEY } from './demo/session';

beforeEach(() => {
  window.history.replaceState(null, '', '/');
  sessionStorage.clear();
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  // jsdom does not implement the native modal lifecycle.
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.open = true;
    },
  });
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('local client walkthrough', () => {
  it('opens sign-up from the landing and preserves only a demo display name across refresh', async () => {
    const user = userEvent.setup();
    const view = render(<App />);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toContain(
      'Good things',
    );
    await user.click(screen.getAllByRole('link', { name: 'Join SOW' })[0]);
    expect(window.location.pathname).toBe('/signup');
    await user.type(screen.getByLabelText('First name'), 'Sam');
    await user.type(screen.getByLabelText('Last name'), 'Example');
    await user.type(screen.getByLabelText('Email address'), 'sam@example.com');
    await user.type(
      screen.getByLabelText('Password', { exact: true }),
      'made-up-password',
    );
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(window.location.pathname).toBe('/home');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toContain(
      'Hi, Sam',
    );
    expect(JSON.parse(sessionStorage.getItem(DEMO_SESSION_KEY)!)).toEqual({
      displayName: 'Sam',
    });
    view.unmount();
    render(<App />);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toContain(
      'Hi, Sam',
    );
  });

  it('blocks empty/invalid forms, allows demo login, and clears the session on logout', async () => {
    window.history.replaceState(null, '', '/login');
    const user = userEvent.setup();
    render(<App />);
    const submit = screen.getByRole('button', { name: 'Log in' });
    await user.click(submit);
    expect(window.location.pathname).toBe('/login');
    await user.type(screen.getByLabelText('Email address'), 'invalid');
    await user.type(
      screen.getByLabelText('Password', { exact: true }),
      'demo-only',
    );
    await user.click(submit);
    expect(window.location.pathname).toBe('/login');
    await user.clear(screen.getByLabelText('Email address'));
    await user.type(screen.getByLabelText('Email address'), 'demo@example.com');
    await user.click(screen.getByRole('button', { name: 'Show password' }));
    expect(
      screen.getByLabelText('Password', { exact: true }).getAttribute('type'),
    ).toBe('text');
    await user.click(submit);
    expect(window.location.pathname).toBe('/home');
    await user.click(screen.getByRole('button', { name: 'Log out' }));
    expect(window.location.pathname).toBe('/');
    expect(sessionStorage.getItem(DEMO_SESSION_KEY)).toBeNull();
    window.history.replaceState(null, '', '/home');
    window.dispatchEvent(new PopStateEvent('popstate'));
    expect(
      await screen.findByRole('heading', { name: 'Welcome back.' }),
    ).toBeTruthy();
    expect(window.location.pathname).toBe('/login');
  });

  it('handles direct home visits and corrupt sessions, and makes the logo a home link', async () => {
    window.history.replaceState(null, '', '/home');
    sessionStorage.setItem(DEMO_SESSION_KEY, '{not-json');
    render(<App />);
    expect(window.location.pathname).toBe('/login');
    expect(screen.getByRole('heading', { name: 'Welcome back.' })).toBeTruthy();
    expect(screen.queryByText('Back to landing')).toBeNull();
    await userEvent.click(screen.getByRole('link', { name: 'SOW home' }));
    expect(window.location.pathname).toBe('/');
  });

  it('clears credentials on mode changes and supports browser back/forward', async () => {
    window.history.replaceState(null, '', '/login');
    const user = userEvent.setup();
    render(<App />);
    await user.type(
      screen.getByLabelText('Password', { exact: true }),
      'temporary',
    );
    await user.click(screen.getByRole('link', { name: 'Sign up' }));
    expect(
      (screen.getByLabelText('Password', { exact: true }) as HTMLInputElement)
        .value,
    ).toBe('');
    window.history.replaceState(null, '', '/login');
    window.dispatchEvent(new PopStateEvent('popstate'));
    expect(
      await screen.findByRole('heading', { name: 'Welcome back.' }),
    ).toBeTruthy();
  });

  it('shows community posts and opportunities in separate sections with honest preview actions', async () => {
    window.history.replaceState(null, '', '/home');
    sessionStorage.setItem(
      DEMO_SESSION_KEY,
      JSON.stringify({ displayName: 'Alex' }),
    );
    const user = userEvent.setup();
    render(<App />);
    const feed = screen.getByRole('region', { name: 'Your community' });
    const events = screen.getByRole('region', {
      name: 'Opportunities near you',
    });
    expect(within(feed).getAllByRole('article')).toHaveLength(2);
    expect(within(events).getAllByRole('article')).toHaveLength(3);
    const like = within(feed).getAllByRole('button', { name: 'Like' })[0];
    await user.click(like);
    expect(like.getAttribute('aria-pressed')).toBe('true');
    expect(within(feed).getByText('13 likes · 3 comments')).toBeTruthy();
    await user.click(
      within(events).getAllByRole('button', { name: 'Take a look' })[0],
    );
    expect(screen.getByRole('dialog').textContent).toContain(
      'Event registration is not available',
    );
    await user.click(screen.getByRole('button', { name: 'Close preview' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
