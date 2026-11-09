// @vitest-environment jsdom
import {
  cleanup,
  render,
  screen,
  within,
  waitFor,
  act,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

const auth = vi.hoisted(() => ({
  listeners: new Set<(event: string, session: unknown) => void>(),
  getSession: vi.fn(),
  setSession: vi.fn(),
  signOut: vi.fn(),
}));
vi.mock('./auth/client', () => ({
  authClient: () => ({
    auth: {
      ...auth,
      onAuthStateChange: (
        listener: (event: string, session: unknown) => void,
      ) => {
        auth.listeners.add(listener);
        return {
          data: {
            subscription: {
              unsubscribe: () => auth.listeners.delete(listener),
            },
          },
        };
      },
    },
  }),
  forgetStoredSession: () => localStorage.removeItem('sow-auth-session'),
}));

const id = 'b6034174-d377-4ad4-b96a-6777660143e6';
const profile = {
  id,
  email: 'sam@example.com',
  role: 'STANDARD',
  firstName: 'Sam',
  lastName: 'Example',
  bio: null,
  avatarUrl: null,
  organizationId: null,
  createdAt: '2026-11-01T18:00:00Z',
};
const session = () => ({
  access_token: 'access-token',
  refresh_token: 'refresh-token',
  expires_at: Math.floor(Date.now() / 1000) + 3600,
  user: { id },
});
const result = () => ({
  user: profile,
  session: {
    accessToken: 'access-token',
    refreshToken: 'refresh-token',
    tokenType: 'bearer',
    expiresIn: 3600,
    expiresAt: session().expires_at,
  },
});
function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
function emit(event: string, value: unknown) {
  for (const listener of auth.listeners) listener(event, value);
}
function restore() {
  localStorage.setItem('sow-auth-session', JSON.stringify(session()));
}
async function fill(mode: 'signup' | 'login' = 'login') {
  const user = userEvent.setup();
  if (mode === 'signup') {
    await user.type(screen.getByLabelText('First name'), 'Sam');
    await user.type(screen.getByLabelText('Last name'), 'Example');
  }
  await user.type(screen.getByLabelText('Email address'), 'sam@example.com');
  await user.type(
    screen.getByLabelText('Password', { exact: true }),
    ' untouched password ',
  );
  return user;
}

beforeEach(() => {
  vi.stubEnv('VITE_API_BASE_URL', '/api');
  window.history.replaceState(null, '', '/');
  localStorage.clear();
  sessionStorage.clear();
  auth.listeners.clear();
  auth.getSession.mockImplementation(async () => ({
    data: {
      session: JSON.parse(localStorage.getItem('sow-auth-session') ?? 'null'),
    },
    error: null,
  }));
  auth.setSession.mockImplementation(async () => {
    restore();
    emit('SIGNED_IN', session());
    return { data: { session: session() }, error: null };
  });
  auth.signOut.mockImplementation(async () => {
    localStorage.removeItem('sow-auth-session');
    emit('SIGNED_OUT', null);
    return { error: null };
  });
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) =>
      url.endsWith('/users/me')
        ? json(profile)
        : json(result(), url.endsWith('/signup') ? 201 : 200),
    ),
  );
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
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
  vi.clearAllMocks();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe('backend account integration', () => {
  it('signs up with the exact contract, verifies the profile and restores it on reload', async () => {
    const view = render(<App />);
    await userEvent.click(screen.getAllByRole('link', { name: 'Join SOW' })[0]);
    const user = await fill('signup');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(
      (await screen.findByRole('heading', { level: 1, name: /Hi, Sam/ }))
        .textContent,
    ).toContain('Hi, Sam');
    const calls = vi.mocked(fetch).mock.calls;
    expect(calls[0][0]).toBe('/api/auth/signup');
    expect(JSON.parse(String(calls[0][1]?.body))).toEqual({
      email: 'sam@example.com',
      password: ' untouched password ',
      firstName: 'Sam',
      lastName: 'Example',
    });
    expect(calls[1][1]?.headers).toEqual({
      Authorization: 'Bearer access-token',
      Accept: 'application/json',
    });
    expect(localStorage.getItem('sow-auth-session')).not.toContain('password');
    view.unmount();
    render(<App />);
    await screen.findByRole('heading', { level: 1, name: /Hi, Sam/ });
  });

  it('blocks invalid forms, logs in, and clears access on logout', async () => {
    window.history.replaceState(null, '', '/login');
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }));
    expect(fetch).not.toHaveBeenCalled();
    const user = await fill();
    await user.click(screen.getByRole('button', { name: 'Show password' }));
    expect(
      screen.getByLabelText('Password', { exact: true }).getAttribute('type'),
    ).toBe('text');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    await screen.findByRole('heading', { name: /Hi, Sam/ });
    expect(JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body))).toEqual(
      { email: 'sam@example.com', password: ' untouched password ' },
    );
    await user.click(screen.getByRole('button', { name: 'Log out' }));
    await waitFor(() => expect(window.location.pathname).toBe('/'));
    expect(auth.signOut).toHaveBeenCalledWith({ scope: 'local' });
    expect(localStorage.getItem('sow-auth-session')).toBeNull();
    act(() => {
      window.history.replaceState(null, '', '/home');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await screen.findByRole('heading', { name: 'Welcome back.' });
  });

  it('ignores demo storage and protects a direct home visit', async () => {
    window.history.replaceState(null, '', '/home');
    sessionStorage.setItem(
      'sow-demo-session',
      JSON.stringify({ displayName: 'Fake' }),
    );
    render(<App />);
    await screen.findByRole('heading', { name: 'Welcome back.' });
    expect(window.location.pathname).toBe('/login');
    expect(fetch).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('link', { name: 'SOW home' }));
    expect(window.location.pathname).toBe('/');
  });

  it.each([
    [401, 'INVALID_CREDENTIALS', 'Invalid email or password.'],
    [429, 'AUTH_RATE_LIMITED', 'Too many attempts.'],
    [
      503,
      'ACCOUNT_PROVISIONING_REQUIRED',
      'Your account setup needs attention.',
    ],
    [
      503,
      'ACCOUNT_PROVISIONING_FAILED',
      'Contact support before signing up again.',
    ],
  ])('handles %s / %s without opening home', async (status, code, message) => {
    window.history.replaceState(null, '', '/login');
    vi.mocked(fetch).mockResolvedValue(
      json({ code, message: 'provider secret' }, status),
    );
    render(<App />);
    const user = await fill();
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    expect((await screen.findByRole('alert')).textContent).toContain(message);
    expect(screen.queryByText(/provider secret/)).toBeNull();
    expect(window.location.pathname).toBe('/login');
    expect(auth.setSession).not.toHaveBeenCalled();
    expect(
      (screen.getByLabelText('Password', { exact: true }) as HTMLInputElement)
        .value,
    ).toBe('');
  });

  it('prevents duplicate submission and ignores a response after changing forms', async () => {
    window.history.replaceState(null, '', '/login');
    let resolve!: (value: Response) => void;
    vi.mocked(fetch).mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    render(<App />);
    const user = await fill();
    await user.dblClick(screen.getByRole('button', { name: 'Log in' }));
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(
      (screen.getByRole('button', { name: 'Logging in…' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    await user.click(screen.getByRole('link', { name: 'Sign up' }));
    await act(async () => resolve(json(result())));
    expect(auth.setSession).not.toHaveBeenCalled();
    expect(window.location.pathname).toBe('/signup');
    expect(
      (screen.getByLabelText('Password', { exact: true }) as HTMLInputElement)
        .value,
    ).toBe('');
  });

  it('keeps private content hidden on profile outage and permits retry', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    vi.mocked(fetch)
      .mockResolvedValueOnce(json({ code: 'ACCOUNT_UNAVAILABLE' }, 503))
      .mockResolvedValue(json(profile));
    render(<App />);
    expect((await screen.findByRole('alert')).textContent).toContain(
      'temporarily unavailable',
    );
    expect(screen.queryByRole('region', { name: 'Your community' })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await screen.findByRole('heading', { name: /Hi, Sam/ });
  });

  it('rejects expired tokens and clears stored sessions on a profile 401', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    vi.mocked(fetch).mockResolvedValue(json({ code: 'UNAUTHORIZED' }, 401));
    render(<App />);
    await screen.findByRole('heading', { name: 'Welcome back.' });
    expect(localStorage.getItem('sow-auth-session')).toBeNull();
  });

  it('reloads the profile using refreshed credentials and responds to provider sign-out', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    render(<App />);
    await screen.findByRole('heading', { name: /Hi, Sam/ });
    act(() =>
      emit('TOKEN_REFRESHED', { ...session(), access_token: 'new-access' }),
    );
    await waitFor(() =>
      expect(fetch).toHaveBeenLastCalledWith(
        '/api/users/me',
        expect.objectContaining({
          headers: {
            Authorization: 'Bearer new-access',
            Accept: 'application/json',
          },
        }),
      ),
    );
    act(() => emit('SIGNED_OUT', null));
    await screen.findByRole('heading', { name: 'Welcome back.' });
  });

  it('forgets this browser session even if remote logout fails', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    auth.signOut.mockResolvedValue({ error: new Error('network') });
    render(<App />);
    await screen.findByRole('heading', { name: /Hi, Sam/ });
    await userEvent.click(screen.getByRole('button', { name: 'Log out' }));
    await waitFor(() => expect(window.location.pathname).toBe('/'));
    expect(localStorage.getItem('sow-auth-session')).toBeNull();
    expect(screen.getByRole('status').textContent).toContain(
      'could not confirm remote sign-out',
    );
  });

  it('preserves community and opportunity previews after real authentication', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    render(<App />);
    const feed = await screen.findByRole('region', { name: 'Your community' });
    const events = screen.getByRole('region', {
      name: 'Opportunities near you',
    });
    expect(within(feed).getAllByRole('article')).toHaveLength(2);
    expect(within(events).getAllByRole('article')).toHaveLength(3);
    await userEvent.click(
      within(feed).getAllByRole('button', { name: 'Like' })[0],
    );
    expect(within(feed).getByText('13 likes · 3 comments')).toBeTruthy();
    await userEvent.click(
      within(events).getAllByRole('button', { name: 'Take a look' })[0],
    );
    expect(screen.getByRole('dialog').textContent).toContain(
      'Event registration is not available',
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Close preview' }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });
  it('hides content while profile verification is pending and ignores a late profile after logout', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    let resolve!: (value: Response) => void;
    vi.mocked(fetch).mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    render(<App />);
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('region', { name: 'Your community' })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Log out' }));
    await waitFor(() => expect(window.location.pathname).toBe('/'));
    await act(async () => resolve(json(profile)));
    expect(screen.queryByRole('region', { name: 'Your community' })).toBeNull();
    expect(localStorage.getItem('sow-auth-session')).toBeNull();
  });

  it('expires private UI when automatic refresh cannot replace an expiring session', async () => {
    vi.useFakeTimers();
    window.history.replaceState(null, '', '/home');
    localStorage.setItem(
      'sow-auth-session',
      JSON.stringify({
        ...session(),
        expires_at: Math.floor(Date.now() / 1000) + 2,
      }),
    );
    await act(async () => {
      render(<App />);
    });
    expect(screen.getByRole('heading', { name: /Hi, Sam/ })).toBeTruthy();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2100);
    });
    expect(screen.getByRole('heading', { name: 'Welcome back.' })).toBeTruthy();
    expect(localStorage.getItem('sow-auth-session')).toBeNull();
    vi.useRealTimers();
  });

  it('rejects a profile that belongs to a different UUID', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    vi.mocked(fetch).mockResolvedValue(
      json({ ...profile, id: 'another-user' }),
    );
    render(<App />);
    await screen.findByRole('heading', { name: 'Welcome back.' });
    expect(screen.queryByRole('region', { name: 'Your community' })).toBeNull();
    expect(localStorage.getItem('sow-auth-session')).toBeNull();
  });

  it('sanitizes SDK adoption failures without repeating signup', async () => {
    window.history.replaceState(null, '', '/signup');
    auth.setSession.mockRejectedValue(new Error('provider secret'));
    render(<App />);
    const user = await fill('signup');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect((await screen.findByRole('alert')).textContent).toContain(
      'Please try logging in.',
    );
    expect(screen.queryByText(/provider secret/)).toBeNull();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(window.location.pathname).toBe('/signup');
  });
});
