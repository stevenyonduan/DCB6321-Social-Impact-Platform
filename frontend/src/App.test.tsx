// @vitest-environment jsdom
import {
  cleanup,
  render,
  screen,
  within,
  waitFor,
  act,
  fireEvent,
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
const organizationId = 'a975e6c1-38b9-438a-a798-12741f117870';
const organizationPath = `/organizations/${organizationId}`;
const opportunityPath = `/api${organizationPath}/opportunities?page=0&size=20`;
const emptyOpportunities = { items: [], page: 0, size: 20, hasNext: false };
const organization = {
  id: organizationId,
  name: 'Atlanta Community Gardens',
  description: 'Neighbors growing food together.',
  website: 'https://example.org/',
  isVerified: true,
  createdAt: '2026-11-01T18:00:00Z',
};
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

describe('organization profile', () => {
  it.each(['/profile', organizationPath])(
    'opens the feed when a signed-in user clicks the logo from %s',
    async (path) => {
      restore();
      window.history.replaceState(null, '', path);
      vi.mocked(fetch)
        .mockResolvedValueOnce(json(profile))
        .mockResolvedValueOnce(json(organization));
      render(<App />);
      await userEvent.click(
        await screen.findByRole('link', { name: 'SOW feed' }),
      );
      expect(window.location.pathname).toBe('/home');
      expect(screen.getByRole('heading', { name: /Hi, Sam/ })).toBeTruthy();
    },
  );

  it.each([
    [404, 'ORGANIZATION_NOT_FOUND', 'Organization not found.'],
    [401, 'UNAUTHORIZED', 'Your session has expired. Please log in again.'],
  ])(
    'handles organization read errors with status %s',
    async (status, code, message) => {
      restore();
      window.history.replaceState(null, '', organizationPath);
      vi.mocked(fetch)
        .mockResolvedValueOnce(json(profile))
        .mockResolvedValueOnce(json({ code }, status));
      render(<App />);
      await screen.findByText(message);
      expect(window.location.pathname).toBe(
        status === 401 ? '/login' : organizationPath,
      );
      expect(screen.queryByRole('button', { name: 'Edit profile' })).toBeNull();
    },
  );

  it.each(['STANDARD', 'ADMIN', 'ORGANIZATION'])(
    'shows the direct-link profile read-only for %s without ownership',
    async (role) => {
      restore();
      window.history.replaceState(null, '', organizationPath);
      vi.mocked(fetch)
        .mockResolvedValueOnce(
          json({
            ...profile,
            role,
            organizationId: role === 'ADMIN' ? organizationId : id,
          }),
        )
        .mockResolvedValueOnce(json(organization));
      render(<App />);
      await screen.findByRole('heading', { name: organization.name });
      expect(screen.getByText('Verified')).toBeTruthy();
      expect(
        screen.getByRole('link', { name: 'example.org' }).getAttribute('href'),
      ).toBe(organization.website);
      expect(screen.queryByRole('button', { name: 'Edit profile' })).toBeNull();
    },
  );

  it('opens My organization and saves only changed fields with the current token', async () => {
    restore();
    window.history.replaceState(null, '', '/profile');
    vi.mocked(fetch)
      .mockResolvedValueOnce(
        json({ ...profile, role: 'ORGANIZATION', organizationId }),
      )
      .mockResolvedValueOnce(json(organization))
      .mockResolvedValueOnce(json(organization))
      .mockResolvedValueOnce(json(emptyOpportunities))
      .mockResolvedValueOnce(json({ ...organization, website: null }));
    render(<App />);
    await userEvent.click(
      await screen.findByRole('link', { name: organization.name }),
    );
    expect(window.location.pathname).toBe(organizationPath);
    await userEvent.click(
      await screen.findByRole('button', { name: 'Edit profile' }),
    );
    await userEvent.clear(screen.getByLabelText('Website'));
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(
      await screen.findByText('Organization profile updated.'),
    ).toBeTruthy();
    expect(screen.getByText('No website listed.')).toBeTruthy();
    expect(fetch).toHaveBeenLastCalledWith(
      `/api${organizationPath}`,
      expect.objectContaining({
        method: 'PATCH',
        body: JSON.stringify({ website: null }),
        headers: expect.objectContaining({
          Authorization: 'Bearer access-token',
        }),
      }),
    );
  });

  it('creates an organization from Account details and opens it with owner controls', async () => {
    restore();
    window.history.replaceState(null, '', '/profile');
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(json(organization, 201))
      .mockResolvedValueOnce(
        json({ ...profile, role: 'ORGANIZATION', organizationId }),
      )
      .mockResolvedValueOnce(json(organization));
    render(<App />);
    const details = await screen.findByRole('region', {
      name: 'Account details',
    });
    expect(within(details).getByText('My organization')).toBeTruthy();
    await userEvent.click(
      within(details).getByRole('button', { name: 'Create organization' }),
    );
    const dialog = within(
      screen.getByRole('dialog', { name: 'Create organization' }),
    );
    await userEvent.type(
      dialog.getByLabelText(/Organization name/),
      organization.name,
    );
    await userEvent.click(
      dialog.getByRole('button', { name: 'Create organization' }),
    );
    await screen.findByRole('button', { name: 'Edit profile' });
    expect(window.location.pathname).toBe(organizationPath);
    expect(fetch).toHaveBeenNthCalledWith(
      2,
      '/api/organizations',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          name: organization.name,
          description: null,
          website: null,
        }),
        headers: expect.objectContaining({
          Authorization: 'Bearer access-token',
        }),
      }),
    );
    expect(fetch).toHaveBeenNthCalledWith(
      3,
      '/api/users/me',
      expect.anything(),
    );
  });

  it('refreshes the linked organization after a creation conflict without posting twice', async () => {
    restore();
    window.history.replaceState(null, '', '/profile');
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(
        json({ code: 'ORGANIZATION_ALREADY_ASSOCIATED' }, 409),
      )
      .mockResolvedValueOnce(
        json({ ...profile, role: 'ORGANIZATION', organizationId }),
      )
      .mockResolvedValueOnce(json(organization));
    render(<App />);
    await userEvent.click(
      await screen.findByRole('button', { name: 'Create organization' }),
    );
    const dialog = within(
      screen.getByRole('dialog', { name: 'Create organization' }),
    );
    await userEvent.type(
      dialog.getByLabelText(/Organization name/),
      organization.name,
    );
    await userEvent.click(
      dialog.getByRole('button', { name: 'Create organization' }),
    );
    expect((await dialog.findByRole('alert')).textContent).toContain(
      'already linked',
    );
    await userEvent.click(
      dialog.getByRole('button', { name: 'Refresh profile' }),
    );
    await screen.findByRole('link', { name: organization.name });
    expect(
      vi
        .mocked(fetch)
        .mock.calls.filter(([, options]) => options?.method === 'POST'),
    ).toHaveLength(1);
  });

  it('keeps the draft after a failed save and hides editing when permission is denied', async () => {
    restore();
    window.history.replaceState(null, '', organizationPath);
    vi.mocked(fetch)
      .mockResolvedValueOnce(
        json({ ...profile, role: 'ORGANIZATION', organizationId }),
      )
      .mockResolvedValueOnce(json(organization))
      .mockResolvedValueOnce(json(emptyOpportunities))
      .mockResolvedValueOnce(json({ code: 'ORGANIZATION_UNAVAILABLE' }, 503))
      .mockResolvedValueOnce(json({ code: 'FORBIDDEN' }, 403));
    render(<App />);
    await userEvent.click(
      await screen.findByRole('button', { name: 'Edit profile' }),
    );
    const name = screen.getByLabelText<HTMLInputElement>('Organization name');
    await userEvent.clear(name);
    await userEvent.type(name, 'New name');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByText(/save could not be confirmed/);
    expect(name.value).toBe('New name');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByText(/do not have permission/);
    expect(screen.queryByRole('button', { name: 'Edit profile' })).toBeNull();
    expect(
      screen.getByRole('heading', { name: organization.name }),
    ).toBeTruthy();
  });

  it('preserves an organization deep link through sign-in', async () => {
    window.history.replaceState(null, '', organizationPath);
    render(<App />);
    await screen.findByRole('heading', { name: 'Welcome back.' });
    expect(fetch).not.toHaveBeenCalled();
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(result()))
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(json(organization));
    const user = await fill();
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    await screen.findByRole('heading', { name: organization.name });
    expect(window.location.pathname).toBe(organizationPath);
  });
});

describe('organization opportunities', () => {
  const start = '2099-01-15T10:00';
  const end = '2099-01-15T12:00';
  const opportunity = {
    id: '3a5b0fd2-7d4a-4bd9-8d71-dca84dd08792',
    organizationId,
    creatorId: id,
    createdAt: organization.createdAt,
    title: 'Garden workday',
    description: 'Help prepare garden beds.',
    location: 'Atlanta, GA',
    eventStart: new Date(start).toISOString(),
    eventEnd: new Date(end).toISOString(),
    imageUrl: null,
    registrationType: 'EXTERNAL',
    registrationUrl: 'https://example.org/register',
  };

  it('publishes as the owner and loads the saved opportunity again after reload', async () => {
    restore();
    window.history.replaceState(null, '', organizationPath);
    let published = false;
    vi.mocked(fetch).mockImplementation(async (url, options) => {
      if (url === '/api/users/me') {
        return json({ ...profile, role: 'ORGANIZATION', organizationId });
      }
      if (url === opportunityPath) {
        return json({
          ...emptyOpportunities,
          items: published ? [opportunity] : [],
        });
      }
      if (options?.method === 'POST') {
        published = true;
        return json(opportunity, 201);
      }
      return json(organization);
    });
    const view = render(<App />);
    await screen.findByText('No upcoming opportunities yet.');
    await userEvent.click(
      screen.getByRole('button', { name: 'Publish opportunity' }),
    );
    const dialog = within(
      screen.getByRole('dialog', { name: 'Publish opportunity' }),
    );
    expect(
      dialog.getByRole<HTMLInputElement>('radio', { name: 'Internal' }).checked,
    ).toBe(true);
    expect(dialog.queryByLabelText('Registration URL')).toBeNull();
    await userEvent.click(dialog.getByRole('radio', { name: 'External' }));
    await userEvent.type(
      dialog.getByLabelText('Registration URL'),
      opportunity.registrationUrl,
    );
    await userEvent.type(dialog.getByLabelText('Title'), opportunity.title);
    await userEvent.type(
      dialog.getByLabelText('Description'),
      opportunity.description,
    );
    await userEvent.type(
      dialog.getByLabelText('Location'),
      opportunity.location,
    );
    fireEvent.change(dialog.getByLabelText('Start date and time'), {
      target: { value: start },
    });
    fireEvent.change(dialog.getByLabelText('End date and time'), {
      target: { value: end },
    });
    await userEvent.click(
      dialog.getByRole('button', { name: 'Publish opportunity' }),
    );
    await screen.findByText('Opportunity published.');
    expect(
      (
        await screen.findByRole('link', { name: 'Register on example.org' })
      ).getAttribute('href'),
    ).toBe(opportunity.registrationUrl);
    expect(fetch).toHaveBeenCalledWith(
      '/api/organizations/me/opportunities',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          Authorization: 'Bearer access-token',
        }),
        body: JSON.stringify({
          title: opportunity.title,
          description: opportunity.description,
          location: opportunity.location,
          eventStart: opportunity.eventStart,
          eventEnd: opportunity.eventEnd,
          imageUrl: null,
          registrationType: 'EXTERNAL',
          registrationUrl: opportunity.registrationUrl,
        }),
      }),
    );
    view.unmount();
    render(<App />);
    await screen.findByRole('heading', { name: opportunity.title });
    expect(
      vi.mocked(fetch).mock.calls.filter(([url]) => url === opportunityPath),
    ).toHaveLength(3);
  });

  it('shows a read-only list to a regular user and loads the next page', async () => {
    restore();
    window.history.replaceState(null, '', organizationPath);
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(json(organization))
      .mockResolvedValueOnce(
        json({ ...emptyOpportunities, items: [opportunity], hasNext: true }),
      )
      .mockResolvedValueOnce(json({ ...emptyOpportunities, page: 1 }));
    render(<App />);
    await screen.findByRole('heading', { name: opportunity.title });
    expect(
      screen.queryByRole('button', { name: 'Publish opportunity' }),
    ).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Page 2');
    expect(fetch).toHaveBeenLastCalledWith(
      opportunityPath.replace('page=0', 'page=1'),
      expect.anything(),
    );
  });
});

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
    await waitFor(() => expect(window.location.pathname).toBe('/login'));
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

describe('profile page', () => {
  it('navigates between Feed and Profile with one authenticated profile request', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    render(<App />);
    await screen.findByRole('heading', { name: /Hi, Sam/ });

    const nav = within(
      screen.getByRole('navigation', { name: 'Main navigation' }),
    );
    expect(nav.getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Feed',
      'Profile',
    ]);
    expect(
      nav.getByRole('link', { name: 'Feed' }).getAttribute('aria-current'),
    ).toBe('page');
    await userEvent.click(nav.getByRole('link', { name: 'Profile' }));
    expect(window.location.pathname).toBe('/profile');
    expect(document.title).toBe('Profile | SOW');
    expect(
      nav.getByRole('link', { name: 'Profile' }).getAttribute('aria-current'),
    ).toBe('page');
    expect(screen.getByRole('heading', { name: 'Sam Example' })).toBeTruthy();
    expect(screen.getByText('SE')).toBeTruthy();
    expect(screen.getByText('No bio yet.')).toBeTruthy();
    expect(screen.getByText('sam@example.com')).toBeTruthy();
    expect(screen.getByText('November 2026')).toBeTruthy();

    await userEvent.click(nav.getByRole('link', { name: 'Feed' }));
    expect(window.location.pathname).toBe('/home');
    expect(screen.getByRole('heading', { name: /Hi, Sam/ })).toBeTruthy();
    expect(screen.queryByText('sam@example.com')).toBeNull();
    expect(fetch).toHaveBeenCalledTimes(1);

    window.history.back();
    await screen.findByRole('heading', { name: 'Sam Example' });
  });

  it('renders a saved bio and avatar on a direct visit and falls back if the image fails', async () => {
    restore();
    window.history.replaceState(null, '', '/profile');
    vi.mocked(fetch).mockResolvedValue(
      json({
        ...profile,
        bio: 'I volunteer at a community garden.',
        avatarUrl: 'https://example.com/avatar.jpg',
      }),
    );
    const { container } = render(<App />);
    await screen.findByRole('heading', { name: 'Sam Example' });
    expect(screen.getByText('I volunteer at a community garden.')).toBeTruthy();
    const avatar = container.querySelector('.profile-avatar img')!;
    expect(avatar.getAttribute('src')).toBe('https://example.com/avatar.jpg');
    fireEvent.error(avatar);
    expect(screen.getByText('SE')).toBeTruthy();
  });

  it('shows loading and retry on a direct visit while keeping account data private', async () => {
    restore();
    window.history.replaceState(null, '', '/profile');
    vi.mocked(fetch)
      .mockResolvedValueOnce(json({ code: 'ACCOUNT_UNAVAILABLE' }, 503))
      .mockResolvedValue(json(profile));
    render(<App />);
    expect(screen.getByRole('status').textContent).toBe(
      'Loading your profile…',
    );
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText('sam@example.com')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await screen.findByRole('heading', { name: 'Sam Example' });

    act(() => emit('SIGNED_OUT', null));
    await screen.findByRole('heading', { name: 'Welcome back.' });
    await waitFor(() => expect(window.location.pathname).toBe('/login'));
    expect(screen.queryByText('sam@example.com')).toBeNull();
  });

  it('redirects an anonymous direct visit to login without requesting private data', async () => {
    window.history.replaceState(null, '', '/profile');
    render(<App />);
    await screen.findByRole('heading', { name: 'Welcome back.' });
    await waitFor(() => expect(window.location.pathname).toBe('/login'));
    expect(screen.queryByRole('link', { name: 'Profile' })).toBeNull();
    expect(fetch).not.toHaveBeenCalled();
  });
});
