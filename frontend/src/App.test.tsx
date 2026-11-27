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
  storageFrom: vi.fn(),
}));
vi.mock('./auth/client', () => ({
  authClient: () => ({
    storage: { from: auth.storageFrom },
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
const feedPath = '/api/posts?page=0&size=20';
const emptyFeed = { items: [], page: 0, size: 20, hasNext: false };
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
  organizationRole: null,
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
      expect(
        await screen.findByRole('heading', { name: /Hi, Sam/ }),
      ).toBeTruthy();
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
            organizationId: role === 'ADMIN' ? null : id,
            organizationRole: role === 'ADMIN' ? null : 'ADMIN',
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

  it.each(['OWNER', 'ADMIN'])(
    'opens My organization and saves only changed fields as %s with the current token',
    async (organizationRole) => {
      restore();
      window.history.replaceState(null, '', '/profile');
      vi.mocked(fetch)
        .mockResolvedValueOnce(
          json({ ...profile, organizationId, organizationRole }),
        )
        .mockResolvedValueOnce(json(organization))
        .mockResolvedValueOnce(json(emptyFeed))
        .mockResolvedValueOnce(json(organization))
        .mockResolvedValueOnce(json(emptyOpportunities))
        .mockResolvedValueOnce(json({ ...organization, website: null }));
      render(<App />);
      await screen.findByText('You haven’t posted yet.');
      await userEvent.click(
        await screen.findByRole('link', { name: organization.name }),
      );
      expect(window.location.pathname).toBe(organizationPath);
      await userEvent.click(
        await screen.findByRole('button', { name: 'Edit profile' }),
      );
      await userEvent.clear(screen.getByLabelText('Website'));
      await userEvent.click(
        screen.getByRole('button', { name: 'Save changes' }),
      );
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
    },
  );

  it('creates an organization from Account details and opens it with owner controls', async () => {
    restore();
    window.history.replaceState(null, '', '/profile');
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(json(emptyFeed))
      .mockResolvedValueOnce(
        json({ organization, ownerId: id, organizationRole: 'OWNER' }, 201),
      )
      .mockResolvedValueOnce(
        json({ ...profile, organizationId, organizationRole: 'OWNER' }),
      )
      .mockResolvedValueOnce(json(organization));
    render(<App />);
    await screen.findByText('You haven’t posted yet.');
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
      3,
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
      4,
      '/api/users/me',
      expect.anything(),
    );
  });

  it('refreshes the linked organization after a creation conflict without posting twice', async () => {
    restore();
    window.history.replaceState(null, '', '/profile');
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(json(emptyFeed))
      .mockResolvedValueOnce(json({ code: 'MEMBERSHIP_CONFLICT' }, 409))
      .mockResolvedValueOnce(
        json({ ...profile, organizationId, organizationRole: 'OWNER' }),
      )
      .mockResolvedValueOnce(json(organization));
    render(<App />);
    await screen.findByText('You haven’t posted yet.');
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
        json({ ...profile, organizationId, organizationRole: 'OWNER' }),
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

describe('editing organization admins', () => {
  const adminsPath = '/api/organizations/me/admins';
  const address = 'Mike@Example.test';
  const owner = {
    id,
    firstName: 'Sam',
    lastName: 'Example',
    organizationRole: 'OWNER',
  };
  const admin = {
    id: '3a5b0fd2-7d4a-4bd9-8d71-dca84dd08792',
    firstName: 'Mike',
    lastName: 'Example',
    organizationRole: 'ADMIN',
  };

  function openOrganization({
    association = { organizationId, organizationRole: 'OWNER' },
    loadTeam = () => json([owner, admin]),
  }: {
    association?: {
      organizationId: string | null;
      organizationRole: 'OWNER' | 'ADMIN' | null;
    };
    loadTeam?: () => Response | Promise<Response>;
  } = {}) {
    restore();
    window.history.replaceState(null, '', organizationPath);
    vi.mocked(fetch).mockImplementation(async (url, init) => {
      if (url === '/api/users/me') return json({ ...profile, ...association });
      if (url === `/api${organizationPath}`) return json(organization);
      if (url === opportunityPath) return json(emptyOpportunities);
      if (url === adminsPath && !init?.method) return loadTeam();
      throw new Error('Unexpected request');
    });
    render(<App />);
  }

  async function openDialog() {
    const trigger = await screen.findByRole('button', { name: 'Edit admins' });
    await userEvent.click(trigger);
    const dialog = within(screen.getByRole('dialog', { name: 'Edit admins' }));
    const email = dialog.getByRole('textbox', {
      name: 'Email address',
    }) as HTMLInputElement;
    expect(document.activeElement).toBe(email);
    return { dialog, email, trigger };
  }

  it.each([
    { organizationId, organizationRole: 'ADMIN' as const },
    { organizationId: id, organizationRole: 'OWNER' as const },
    { organizationId: null, organizationRole: null },
  ])(
    'hides the editor for $organizationRole on $organizationId',
    async (association) => {
      openOrganization({ association });
      await screen.findByRole('heading', { name: organization.name });
      expect(screen.queryByRole('button', { name: 'Edit admins' })).toBeNull();
      expect(fetch).not.toHaveBeenCalledWith(adminsPath, expect.anything());
    },
  );

  it('shows loading, retries a list error, and treats an owner-only list as empty', async () => {
    let complete!: (response: Response) => void;
    const firstLoad = new Promise<Response>((resolve) => {
      complete = resolve;
    });
    const loadTeam = vi.fn((): Response | Promise<Response> => json([owner]));
    loadTeam.mockReturnValueOnce(firstLoad);
    openOrganization({ loadTeam });
    const { dialog, trigger } = await openDialog();
    expect(dialog.getByText('Loading admins…')).toBeTruthy();
    complete(json({ code: 'ORGANIZATION_UNAVAILABLE' }, 503));
    await userEvent.click(
      await dialog.findByRole('button', { name: 'Try again' }),
    );
    await dialog.findByText('No admins yet.');
    expect(dialog.queryByText('Sam Example')).toBeNull();
    expect(
      dialog.queryByRole('button', { name: 'Remove Sam Example' }),
    ).toBeNull();
    expect(fetch).toHaveBeenCalledWith(
      adminsPath,
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer access-token',
        }),
      }),
    );
    fireEvent(
      screen.getByRole('dialog'),
      new Event('cancel', { cancelable: true }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });

  it('shows admin emails and profile photos with a fallback for missing or broken photos', async () => {
    const avatarUrl = 'https://example.test/mike.png';
    openOrganization({
      loadTeam: () =>
        json([
          owner,
          { ...admin, email: address, avatarUrl },
          {
            ...admin,
            id: 'a3a824ed-5f24-46f8-8bdb-4c3b138fc0db',
            firstName: 'Alex',
            email: 'alex@example.test',
          },
        ]),
    });
    const { dialog } = await openDialog();
    const mike = (await dialog.findByText(address)).closest('li')!;
    const alex = dialog.getByText('alex@example.test').closest('li')!;
    const photo = mike.querySelector('img')!;
    expect(photo.getAttribute('src')).toBe(avatarUrl);
    expect(alex.querySelector('.organization-admin-avatar svg')).toBeTruthy();
    fireEvent.error(photo);
    expect(mike.querySelector('img')).toBeNull();
    expect(mike.querySelector('.organization-admin-avatar svg')).toBeTruthy();
  });

  it('submits one exact email, prevents duplicate requests, and refreshes after 204', async () => {
    const loadTeam = vi.fn(() => json([owner, admin]));
    loadTeam.mockReturnValueOnce(json([owner]));
    openOrganization({ loadTeam });
    const { dialog, email, trigger } = await openDialog();
    await dialog.findByText('No admins yet.');
    let complete!: (response: Response) => void;
    const response = new Promise<Response>((resolve) => {
      complete = resolve;
    });
    vi.mocked(fetch).mockReturnValueOnce(response);
    await userEvent.type(email, address);
    const form = email.closest('form')!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(
      vi
        .mocked(fetch)
        .mock.calls.filter(
          ([url, init]) => url === adminsPath && init?.method === 'POST',
        ),
    ).toHaveLength(1);
    expect(fetch).toHaveBeenLastCalledWith(
      adminsPath,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ email: address }),
        headers: expect.objectContaining({
          Authorization: 'Bearer access-token',
        }),
      }),
    );
    expect(
      (dialog.getByRole('button', { name: 'Done' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);

    complete(new Response(null, { status: 204 }));
    await dialog.findByText('Admin added.');
    await dialog.findByRole('button', { name: 'Remove Mike Example' });
    expect(email.value).toBe('');
    expect(loadTeam).toHaveBeenCalledTimes(2);
    await userEvent.click(dialog.getByRole('button', { name: 'Done' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });

  it('rejects multiple addresses without submitting', async () => {
    openOrganization();
    const { dialog, email } = await openDialog();
    await dialog.findByText('Mike Example');
    await userEvent.type(email, `${address}, other@example.test`);
    await userEvent.click(dialog.getByRole('button', { name: 'Add admin' }));
    expect(dialog.getByRole('alert').textContent).toContain(
      'Enter one email address',
    );
    expect(fetch).not.toHaveBeenCalledWith(
      adminsPath,
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('keeps the email editable on a generic membership conflict', async () => {
    openOrganization();
    const { dialog, email } = await openDialog();
    await dialog.findByText('Mike Example');
    vi.mocked(fetch).mockResolvedValueOnce(
      json({ code: 'MEMBERSHIP_CONFLICT' }, 409),
    );
    await userEvent.type(email, address);
    await userEvent.click(dialog.getByRole('button', { name: 'Add admin' }));
    expect((await dialog.findByRole('alert')).textContent).toBe(
      'This account couldn’t be added. Check the email and try again.',
    );
    expect(email.value).toBe(address);
    expect(dialog.queryByText('Admin added.')).toBeNull();
  });

  it('confirms removal by ID, supports cancellation, and refreshes after removal', async () => {
    const loadTeam = vi.fn(() => json([owner]));
    loadTeam.mockReturnValueOnce(json([owner, admin]));
    openOrganization({ loadTeam });
    const { dialog } = await openDialog();
    const remove = await dialog.findByRole('button', {
      name: 'Remove Mike Example',
    });
    await userEvent.click(remove);
    await dialog.findByText(
      'Remove Mike Example? Their admin access ends immediately.',
    );
    const cancel = dialog.getByRole('button', { name: 'Cancel' });
    expect(document.activeElement).toBe(cancel);
    await userEvent.click(cancel);
    await waitFor(() => expect(document.activeElement).toBe(remove));
    expect(fetch).not.toHaveBeenCalledWith(
      `${adminsPath}/${admin.id}`,
      expect.anything(),
    );

    await userEvent.click(remove);
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 204 }));
    await userEvent.click(dialog.getByRole('button', { name: 'Remove admin' }));
    await dialog.findByText('Admin removed.');
    await dialog.findByText('No admins yet.');
    expect(fetch).toHaveBeenCalledWith(
      `${adminsPath}/${admin.id}`,
      expect.objectContaining({
        method: 'DELETE',
        headers: expect.objectContaining({
          Authorization: 'Bearer access-token',
        }),
      }),
    );
    expect(loadTeam).toHaveBeenCalledTimes(2);
  });

  it.each([
    [404, 'ADMIN_NOT_FOUND', 'This admin is no longer available.'],
    [
      503,
      'ORGANIZATION_UNAVAILABLE',
      'The removal couldn’t be confirmed. Check the list before trying again.',
    ],
  ])(
    'reconciles the roster after removal returns %s without retrying the write',
    async (status, code, message) => {
      const loadTeam = vi.fn(() => json([owner]));
      loadTeam.mockReturnValueOnce(json([owner, admin]));
      openOrganization({ loadTeam });
      const { dialog } = await openDialog();
      await userEvent.click(
        await dialog.findByRole('button', { name: 'Remove Mike Example' }),
      );
      vi.mocked(fetch).mockResolvedValueOnce(json({ code }, status));
      await userEvent.click(
        dialog.getByRole('button', { name: 'Remove admin' }),
      );
      await dialog.findByText(message);
      await dialog.findByText('No admins yet.');
      expect(
        vi
          .mocked(fetch)
          .mock.calls.filter(([, init]) => init?.method === 'DELETE'),
      ).toHaveLength(1);
      expect(dialog.queryByText('Admin removed.')).toBeNull();
    },
  );

  it('keeps confirmed mutation feedback if reloading the list fails', async () => {
    const loadTeam = vi.fn(() =>
      json({ code: 'ORGANIZATION_UNAVAILABLE' }, 503),
    );
    loadTeam.mockReturnValueOnce(json([owner]));
    openOrganization({ loadTeam });
    const { dialog, email } = await openDialog();
    await dialog.findByText('No admins yet.');
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 204 }));
    await userEvent.type(email, address);
    await userEvent.click(dialog.getByRole('button', { name: 'Add admin' }));
    await dialog.findByText('The admin list couldn’t be loaded.');
    expect(dialog.getByText('Admin added.')).toBeTruthy();
  });

  it('hides the editor when the server denies mutation', async () => {
    openOrganization();
    const { dialog, email } = await openDialog();
    await dialog.findByText('Mike Example');
    vi.mocked(fetch).mockResolvedValueOnce(json({ code: 'FORBIDDEN' }, 403));
    await userEvent.type(email, address);
    await userEvent.click(dialog.getByRole('button', { name: 'Add admin' }));
    await screen.findByText(
      'You do not have permission to edit organization admins.',
    );
    expect(screen.queryByRole('button', { name: 'Edit admins' })).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
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
  const internalOpportunity = {
    ...opportunity,
    registrationType: 'INTERNAL',
    registrationUrl: null,
  };
  const registrationPath = `/api/opportunities/${opportunity.id}/registrations/me`;
  const registration = {
    id: 'dde615fc-81f4-44f9-b4d1-5176bc6e4a41',
    registeredAt: organization.createdAt,
  };

  it('shows linked-organization events and opens details with the appropriate registration action on Home', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    const external = {
      ...opportunity,
      id: 'external-event',
      title: 'Native Planting Day',
    };
    const internal = {
      ...internalOpportunity,
      imageUrl: 'https://example.org/garden.jpg',
    };
    vi.mocked(fetch).mockImplementation(async (url, options) => {
      if (url === '/api/users/me')
        return json({ ...profile, organizationId, organizationRole: 'OWNER' });
      if (url === feedPath) return json(emptyFeed);
      if (url === opportunityPath)
        return json({ ...emptyOpportunities, items: [internal, external] });
      if (url === registrationPath) {
        if (options?.method === 'POST')
          return json(
            { ...registration, eventId: internal.id, userId: id },
            201,
          );
        return json({
          eventId: internal.id,
          registered: false,
          registration: null,
        });
      }
      throw new Error(`Unexpected URL ${url}`);
    });
    render(<App />);
    const column = within(
      await screen.findByRole('region', { name: 'Upcoming opportunities' }),
    );
    const open = await column.findByRole('button', {
      name: `Take a look at ${internal.title}`,
    });
    expect(
      open.closest('article')?.querySelector('img')?.getAttribute('src'),
    ).toBe(internal.imageUrl);
    expect(open.closest('article')?.querySelector('time')?.dateTime).toBe(
      internal.eventStart,
    );
    expect(
      vi.mocked(fetch).mock.calls.some(([url]) => url === registrationPath),
    ).toBe(false);
    await userEvent.click(open);
    const details = within(
      screen.getByRole('dialog', { name: internal.title }),
    );
    expect(details.getByText(internal.description)).toBeTruthy();
    await userEvent.click(
      await details.findByRole('button', { name: 'Register' }),
    );
    await details.findByRole('button', { name: 'Cancel registration' });
    await userEvent.click(
      details.getByRole('button', { name: 'Close event details' }),
    );
    await waitFor(() => expect(document.activeElement).toBe(open));

    await userEvent.click(
      column.getByRole('button', { name: `Take a look at ${external.title}` }),
    );
    const externalDetails = within(
      screen.getByRole('dialog', { name: external.title }),
    );
    expect(
      externalDetails
        .getByRole('link', { name: 'Register on example.org' })
        .getAttribute('href'),
    ).toBe(external.registrationUrl);
    expect(
      vi
        .mocked(fetch)
        .mock.calls.filter(([url]) =>
          String(url).endsWith('/registrations/me'),
        ),
    ).toHaveLength(2);
  });

  it('fetches the newly published event when its organizer returns to Home', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    let published = false;
    vi.mocked(fetch).mockImplementation(async (url) => {
      if (url === '/api/users/me')
        return json({ ...profile, organizationId, organizationRole: 'OWNER' });
      if (url === feedPath) return json(emptyFeed);
      if (url === `/api${organizationPath}`) return json(organization);
      if (url === opportunityPath)
        return json({
          ...emptyOpportunities,
          items: published ? [internalOpportunity] : [],
        });
      if (url === '/api/organizations/me/opportunities') {
        published = true;
        return json(internalOpportunity, 201);
      }
      if (url === registrationPath)
        return json({
          eventId: opportunity.id,
          registered: false,
          registration: null,
        });
      throw new Error(`Unexpected URL ${url}`);
    });
    render(<App />);
    await screen.findByText('No upcoming opportunities yet.');
    act(() => {
      window.history.pushState(null, '', organizationPath);
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await userEvent.click(
      await screen.findByRole('button', { name: 'Publish opportunity' }),
    );
    const dialog = within(
      screen.getByRole('dialog', { name: 'Publish opportunity' }),
    );
    await userEvent.type(
      dialog.getByLabelText('Title'),
      internalOpportunity.title,
    );
    await userEvent.type(
      dialog.getByLabelText('Description'),
      internalOpportunity.description,
    );
    await userEvent.type(
      dialog.getByLabelText('Location'),
      internalOpportunity.location,
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
    await userEvent.click(screen.getByRole('link', { name: 'Feed' }));
    const column = within(
      await screen.findByRole('region', { name: 'Upcoming opportunities' }),
    );
    expect(
      await column.findByRole('heading', { name: internalOpportunity.title }),
    ).toBeTruthy();
  });

  it.each([false, true])(
    'registers, restores the RSVP, and cancels for an account with owner access %s',
    async (owner) => {
      restore();
      window.history.replaceState(null, '', organizationPath);
      let registered = false;
      vi.mocked(fetch).mockImplementation(async (url, options) => {
        if (url === '/api/users/me')
          return json(
            owner
              ? { ...profile, organizationId, organizationRole: 'OWNER' }
              : profile,
          );
        if (url === opportunityPath)
          return json({ ...emptyOpportunities, items: [internalOpportunity] });
        if (url === registrationPath) {
          if (options?.method === 'POST') {
            registered = true;
            return json(
              { ...registration, eventId: opportunity.id, userId: id },
              201,
            );
          }
          if (options?.method === 'DELETE') {
            registered = false;
            return new Response(null, { status: 204 });
          }
          return json({
            eventId: opportunity.id,
            registered,
            registration: registered ? registration : null,
          });
        }
        return json(organization);
      });

      const view = render(<App />);
      await userEvent.click(
        await screen.findByRole('button', { name: 'Register' }),
      );
      await screen.findByText('Registered');
      expect(fetch).toHaveBeenLastCalledWith(
        registrationPath,
        expect.objectContaining({
          method: 'POST',
          headers: {
            Authorization: 'Bearer access-token',
            Accept: 'application/json',
          },
        }),
      );
      expect(vi.mocked(fetch).mock.lastCall?.[1]?.body).toBeUndefined();

      view.unmount();
      render(<App />);
      await userEvent.click(
        await screen.findByRole('button', { name: 'Cancel registration' }),
      );
      await screen.findByRole('button', { name: 'Register' });
      expect(fetch).toHaveBeenLastCalledWith(
        registrationPath,
        expect.objectContaining({ method: 'DELETE' }),
      );
      expect(screen.queryByText('Registered')).toBeNull();
    },
  );

  it('reloads status when a competing request has already registered the user', async () => {
    restore();
    window.history.replaceState(null, '', organizationPath);
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(json(organization))
      .mockResolvedValueOnce(
        json({ ...emptyOpportunities, items: [internalOpportunity] }),
      )
      .mockResolvedValueOnce(
        json({
          eventId: opportunity.id,
          registered: false,
          registration: null,
        }),
      )
      .mockResolvedValueOnce(json({ code: 'ALREADY_REGISTERED' }, 409))
      .mockResolvedValueOnce(
        json({ eventId: opportunity.id, registered: true, registration }),
      );
    render(<App />);

    await userEvent.click(
      await screen.findByRole('button', { name: 'Register' }),
    );
    await screen.findByText('Registered');
    expect(fetch).toHaveBeenLastCalledWith(
      registrationPath,
      expect.objectContaining({ method: 'GET' }),
    );
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it.each(['OWNER', 'ADMIN'])(
    'publishes as %s and loads the saved opportunity again after reload',
    async (organizationRole) => {
      restore();
      window.history.replaceState(null, '', organizationPath);
      let published = false;
      vi.mocked(fetch).mockImplementation(async (url, options) => {
        if (url === '/api/users/me') {
          return json({ ...profile, organizationId, organizationRole });
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
        dialog.getByRole<HTMLInputElement>('radio', { name: 'Internal' })
          .checked,
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
    },
  );

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
    expect(
      vi.mocked(fetch).mock.calls.some(([url]) => url === registrationPath),
    ).toBe(false);
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
    vi.fn(async (url: string) => {
      if (url.endsWith('/users/me')) return json(profile);
      if (url === feedPath) return json(emptyFeed);
      return json(result(), url.endsWith('/signup') ? 201 : 200);
    }),
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
      expect(fetch).toHaveBeenCalledWith(
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

  it('shows empty states for a new account without posts or an organization', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    render(<App />);
    const feed = await screen.findByRole('region', { name: 'Your community' });
    const events = screen.getByRole('region', {
      name: 'Upcoming opportunities',
    });
    expect(await within(feed).findByText(/No posts yet/)).toBeTruthy();
    expect(
      within(events).getByText('No upcoming opportunities yet.'),
    ).toBeTruthy();
    expect(
      vi
        .mocked(fetch)
        .mock.calls.some(([url]) => String(url).includes('/opportunities')),
    ).toBe(false);
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

describe('community posts', () => {
  const existing = {
    id: 'existing-post',
    authorId: 'other-account',
    textContent: 'We packed meals for Atlanta neighbors.',
    imageReference: null,
    createdAt: '2026-11-17T00:00:00Z',
    likeCount: 0,
    commentCount: 1,
    liked: false,
    saved: false,
  };
  const created = {
    ...existing,
    id: 'new-post',
    authorId: id,
    textContent: 'I signed up for the park cleanup.',
    commentCount: 0,
  };
  const savedPath = '/api/users/me/saved-posts?page=0&size=100';

  it('publishes inline, shows the post first, and loads comments only when expanded', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    const commentPath = `/api/posts/${existing.id}/comments`;
    const threadPath = `${commentPath}?page=0&size=100`;
    const comment = {
      id: 'existing-comment',
      postId: existing.id,
      authorId: existing.authorId,
      content: 'Thank you for helping!',
      createdAt: existing.createdAt,
    };
    vi.mocked(fetch).mockImplementation(async (url, options) => {
      if (url === '/api/users/me') return json(profile);
      if (url === feedPath) return json({ ...emptyFeed, items: [existing] });
      if (url === '/api/posts' && options?.method === 'POST')
        return json(created, 201);
      if (url === `/api/posts/${existing.id}/like`)
        return new Response(null, { status: 204 });
      if (url === threadPath)
        return json({ items: [comment], page: 0, size: 100, hasNext: false });
      if (url === commentPath)
        return json(
          {
            ...comment,
            id: 'new-comment',
            authorId: id,
            content: 'Glad to hear it!',
          },
          201,
        );
      throw new Error(`Unexpected URL ${url}`);
    });
    render(<App />);
    await screen.findByText(existing.textContent);
    expect(screen.queryByText(comment.content)).toBeNull();
    expect(
      vi.mocked(fetch).mock.calls.some(([url]) => url === threadPath),
    ).toBe(false);

    await userEvent.click(
      screen.getByRole('button', { name: /Share a little good/ }),
    );
    await userEvent.type(
      screen.getByRole('textbox', { name: 'Write a post' }),
      created.textContent,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Post' }));
    await screen.findByText('Post published.');
    const feed = within(screen.getByRole('region', { name: 'Your community' }));
    const cards = feed.getAllByRole('article');
    expect(within(cards[0]).getByText(created.textContent)).toBeTruthy();
    expect(within(cards[0]).getByText('Sam Example')).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(fetch).toHaveBeenCalledWith(
      '/api/posts',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ textContent: created.textContent }),
      }),
    );

    const card = within(cards[1]);
    await userEvent.click(card.getByRole('button', { name: 'Like' }));
    expect(
      (await card.findByRole('button', { name: 'Liked' })).getAttribute(
        'aria-pressed',
      ),
    ).toBe('true');
    await userEvent.click(card.getByRole('button', { name: 'Comment' }));
    await card.findByText(comment.content);
    await userEvent.type(
      card.getByRole('textbox', { name: 'Write a comment' }),
      'Glad to hear it!',
    );
    await userEvent.click(card.getByRole('button', { name: 'Post comment' }));
    await card.findByText('Glad to hear it!');
    expect(card.getByText('1 like · 2 comments')).toBeTruthy();
    await userEvent.type(
      card.getByRole('textbox', { name: 'Write a comment' }),
      'Discard this draft',
    );
    await userEvent.click(card.getByRole('button', { name: 'Cancel' }));
    expect(card.queryByText(comment.content)).toBeNull();
    expect(card.queryByRole('textbox')).toBeNull();
  });

  it('uploads a selected image as multipart and can show a private image through Storage', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    const imageReference =
      'social-posts/posts/bb73653c-38f9-4137-ad8d-668350f19012.png';
    const download = vi.fn().mockResolvedValue({
      data: new Blob(['png'], { type: 'image/png' }),
      error: null,
    });
    auth.storageFrom.mockReturnValue({
      getPublicUrl: () => ({
        data: { publicUrl: 'https://example.test/public.png' },
      }),
      download,
    });
    vi.stubGlobal(
      'URL',
      class extends URL {
        static createObjectURL = () => 'blob:test-post-image';
        static revokeObjectURL = vi.fn();
      },
    );
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(json(emptyFeed))
      .mockResolvedValueOnce(
        json({ ...created, textContent: null, imageReference }, 201),
      );
    render(<App />);
    await screen.findByText(/No posts yet/);
    await userEvent.click(
      screen.getByRole('button', { name: /Share a little good/ }),
    );
    const file = new File(['png'], 'photo.png', { type: 'image/png' });
    const chooser = screen.getByLabelText<HTMLInputElement>(
      'Add image (optional)',
    );
    expect(chooser.multiple).toBe(false);
    expect(chooser.getAttribute('accept')).toBe('image/jpeg,image/png');
    expect(
      document.getElementById(chooser.getAttribute('aria-describedby')!)
        ?.textContent,
    ).toContain('One JPEG or PNG image, up to 5 MB.');
    await userEvent.upload(chooser, file);
    expect(
      screen.getByRole('img', { name: 'Selected image preview' }),
    ).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Post' }));
    const image = await screen.findByRole('img', {
      name: 'Image attached to this post',
    });
    const request = vi.mocked(fetch).mock.lastCall?.[1];
    expect(request?.method).toBe('POST');
    expect(new Headers(request?.headers).has('Content-Type')).toBe(false);
    expect((request?.body as FormData).get('image')).toBe(file);
    expect(image.getAttribute('src')).toBe('https://example.test/public.png');
    fireEvent.error(image);
    await waitFor(() =>
      expect(
        screen
          .getByRole('img', { name: 'Image attached to this post' })
          .getAttribute('src'),
      ).toBe('blob:test-post-image'),
    );
    expect(download).toHaveBeenCalled();
  });

  it('uses a private Saved posts page and keeps unsaved cards in place until reload', async () => {
    restore();
    window.history.replaceState(null, '', '/profile');
    let saved = [
      { ...created, saved: true },
      { ...existing, saved: true },
    ];
    vi.mocked(fetch).mockImplementation(async (url, options) => {
      if (url === '/api/users/me') return json(profile);
      if (url === savedPath)
        return json({ items: saved, page: 0, size: 100, hasNext: false });
      if (url === `/api/posts/${existing.id}/save`) {
        saved = saved.filter((post) => post.id !== existing.id);
        if (options?.method === 'PUT')
          saved = [{ ...existing, saved: true }, ...saved];
        return new Response(null, { status: 204 });
      }
      throw new Error(`Unexpected URL ${url}`);
    });
    let view = render(<App />);
    await userEvent.click(
      await screen.findByRole('link', { name: 'Saved posts' }),
    );
    await screen.findByText(existing.textContent);
    expect(window.location.pathname).toBe('/profile/saved-posts');
    expect(screen.getByRole('link', { name: 'Back to profile' })).toBeTruthy();
    const oldCard = within(
      screen.getByRole('article', { name: 'Community member' }),
    );
    await userEvent.click(oldCard.getByRole('button', { name: 'Saved' }));
    expect(
      (await oldCard.findByRole('button', { name: 'Save' })).getAttribute(
        'aria-pressed',
      ),
    ).toBe('false');
    expect(oldCard.getByText(existing.textContent)).toBeTruthy();
    await userEvent.click(oldCard.getByRole('button', { name: 'Save' }));
    await oldCard.findByRole('button', { name: 'Saved' });
    expect(screen.getAllByRole('article')[1]).toBe(
      oldCard.getByText(existing.textContent).closest('article'),
    );

    view.unmount();
    view = render(<App />);
    await screen.findByText(existing.textContent);
    expect(
      within(screen.getAllByRole('article')[0]).getByText(existing.textContent),
    ).toBeTruthy();
    await userEvent.click(
      within(screen.getAllByRole('article')[0]).getByRole('button', {
        name: 'Saved',
      }),
    );
    await screen.findByRole('button', { name: 'Save' });
    view.unmount();
    render(<App />);
    await screen.findByText(created.textContent);
    expect(screen.queryByText(existing.textContent)).toBeNull();
  });
});

describe('my events', () => {
  const listPath = '/api/users/me/registrations?page=0&size=100';
  const first = {
    id: 'registration-one',
    eventId: 'event-one',
    organizationId,
    title: 'Grant Park Cleanup',
    eventStart: new Date(2026, 10, 21, 21).toISOString(),
    eventEnd: new Date(2026, 10, 21, 22).toISOString(),
    location: 'Grant Park, Atlanta, GA',
    registeredAt: '2026-11-01T18:00:00Z',
  };
  const later = {
    ...first,
    id: 'registration-two',
    eventId: 'event-two',
    title: 'Trail Maintenance Day',
    eventStart: new Date(2026, 11, 5, 10).toISOString(),
    eventEnd: new Date(2026, 11, 5, 12).toISOString(),
  };
  const past = {
    ...first,
    id: 'registration-three',
    eventId: 'event-three',
    title: 'Neighborhood Litter Walk',
    eventStart: new Date(2026, 10, 8, 10).toISOString(),
    eventEnd: new Date(2026, 10, 8, 12).toISOString(),
  };
  const cancelPath = `/api/opportunities/${first.eventId}/registrations/me`;
  const published = {
    id: first.eventId,
    organizationId,
    creatorId: id,
    title: first.title,
    description: 'Pick up litter along the walking paths. Gloves are provided.',
    location: first.location,
    eventStart: first.eventStart,
    eventEnd: first.eventEnd,
    createdAt: organization.createdAt,
    imageUrl: null,
    registrationType: 'INTERNAL',
    registrationUrl: null,
  };

  beforeEach(() => {
    vi.setSystemTime(new Date(2026, 10, 17, 12));
    restore();
    window.history.replaceState(null, '', '/events');
  });

  afterEach(() => vi.useRealTimers());

  it('sorts a paginated list and places each event on its local calendar date', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(
        json({ items: [later, past], page: 0, size: 100, hasNext: true }),
      )
      .mockResolvedValueOnce(
        json({ items: [first], page: 1, size: 100, hasNext: false }),
      );
    render(<App />);
    const upcoming = within(
      await screen.findByRole('region', { name: 'Upcoming' }),
    );
    await upcoming.findByRole('button', { name: /Grant Park Cleanup/ });
    expect(
      upcoming.getAllByRole('button').map((button) => button.textContent),
    ).toEqual([
      expect.stringContaining('Grant Park Cleanup'),
      expect.stringContaining('Trail Maintenance Day'),
    ]);
    expect(screen.getByRole('link', { name: 'Events' })).toHaveProperty(
      'ariaCurrent',
      'page',
    );
    const pastEvents = screen.getByText('Past events');
    expect(pastEvents.closest('details')?.open).toBe(false);
    await userEvent.click(pastEvents);
    expect(pastEvents.closest('details')?.open).toBe(true);
    await userEvent.click(
      screen.getByRole('button', { name: /Neighborhood Litter Walk/ }),
    );
    const details = screen.getByRole('dialog', {
      name: 'Neighborhood Litter Walk',
    });
    expect(
      within(details).queryByRole('button', { name: 'Cancel registration' }),
    ).toBeNull();
    await userEvent.click(
      within(details).getByRole('button', { name: 'Close event details' }),
    );

    await userEvent.click(screen.getByRole('radio', { name: 'Calendar' }));
    const november = screen.getByRole('table', { name: 'November 2026' });
    const labels = [...november.querySelectorAll('tbody time')].map(
      (time) => time.textContent,
    );
    expect(labels).toEqual(
      Array.from({ length: 35 }, (_, index) =>
        String(index < 30 ? index + 1 : index - 29),
      ),
    );
    const cleanup = within(november).getByRole('button', {
      name: 'Grant Park Cleanup',
    });
    expect(cleanup.closest('td')?.querySelector('time')?.dateTime).toBe(
      '2026-11-21',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Next month' }));
    const december = screen.getByRole('table', { name: 'December 2026' });
    expect(
      within(december).getByRole('button', { name: 'Trail Maintenance Day' }),
    ).toBeTruthy();
    expect(fetch).toHaveBeenCalledTimes(3);
    expect(fetch).toHaveBeenNthCalledWith(
      2,
      listPath,
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer access-token',
        }),
      }),
    );
  });

  it('cancels from the calendar details and removes the event across both views', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(
        json({ items: [first], page: 0, size: 100, hasNext: false }),
      )
      .mockResolvedValueOnce(
        json({
          ...emptyOpportunities,
          items: [{ ...published, id: later.eventId }],
          hasNext: true,
        }),
      )
      .mockResolvedValueOnce(
        json({
          ...emptyOpportunities,
          items: [published],
          page: 1,
        }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    render(<App />);
    await screen.findByRole('button', { name: /Grant Park Cleanup/ });
    await userEvent.click(screen.getByRole('radio', { name: 'Calendar' }));
    await userEvent.click(
      screen.getByRole('button', { name: 'Grant Park Cleanup' }),
    );
    const details = within(
      screen.getByRole('dialog', { name: 'Grant Park Cleanup' }),
    );
    expect(details.getByText(first.location)).toBeTruthy();
    expect(await details.findByText(published.description)).toBeTruthy();
    expect(fetch).toHaveBeenLastCalledWith(
      opportunityPath.replace('page=0', 'page=1'),
      expect.anything(),
    );
    await userEvent.click(
      details.getByRole('button', { name: 'Cancel registration' }),
    );
    await userEvent.click(
      details.getByRole('button', { name: 'Keep registration' }),
    );
    expect(fetch).toHaveBeenCalledTimes(4);
    await userEvent.click(
      details.getByRole('button', { name: 'Cancel registration' }),
    );
    await userEvent.click(
      details.getByRole('button', { name: 'Confirm cancellation' }),
    );
    await screen.findByText('Registration canceled.');
    expect(fetch).toHaveBeenLastCalledWith(
      cancelPath,
      expect.objectContaining({ method: 'DELETE' }),
    );
    expect(
      screen.queryByRole('button', { name: 'Grant Park Cleanup' }),
    ).toBeNull();
    await userEvent.click(screen.getByRole('radio', { name: 'List' }));
    expect(screen.getByText('No upcoming events yet.')).toBeTruthy();
  });

  it('rechecks registration status after an uncertain cancellation', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(
        json({ items: [first], page: 0, size: 100, hasNext: false }),
      )
      .mockResolvedValueOnce(json({ code: 'OPPORTUNITIES_UNAVAILABLE' }, 503))
      .mockRejectedValueOnce(new Error('Connection lost'))
      .mockResolvedValueOnce(
        json({ eventId: first.eventId, registered: false, registration: null }),
      );
    render(<App />);
    await userEvent.click(
      await screen.findByRole('button', { name: /Grant Park Cleanup/ }),
    );
    await screen.findByText('Description unavailable.');
    await userEvent.click(
      screen.getByRole('button', { name: 'Cancel registration' }),
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Confirm cancellation' }),
    );
    await screen.findByText('Registration canceled.');
    expect(fetch).toHaveBeenLastCalledWith(
      cancelPath,
      expect.objectContaining({ method: 'GET' }),
    );
  });
});

describe('profile activity', () => {
  function post(number: number, authorId = id) {
    return {
      id: `activity-${number}`,
      authorId,
      textContent: `Activity post ${number}`,
      imageReference: null,
      createdAt: new Date(Date.UTC(2026, 10, 17, 0, -number)).toISOString(),
      likeCount: 0,
      commentCount: 0,
      liked: false,
      saved: false,
    };
  }

  it('fills the initial batch from multiple pages and loads older matches near the bottom', async () => {
    let reachBottom: (() => void) | undefined;
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(callback: IntersectionObserverCallback) {
          reachBottom = () =>
            callback(
              [{ isIntersecting: true } as IntersectionObserverEntry],
              this as unknown as IntersectionObserver,
            );
        }
        observe() {}
        disconnect() {}
      },
    );
    restore();
    window.history.replaceState(null, '', '/profile');
    const secondPage = feedPath.replace('page=0', 'page=1');
    const thirdPage = feedPath.replace('page=0', 'page=2');
    let resolveSecond!: (response: Response) => void;
    vi.mocked(fetch).mockImplementation(async (url) => {
      if (url === '/api/users/me') return json(profile);
      if (url === feedPath)
        return json({
          ...emptyFeed,
          hasNext: true,
          items: [
            post(1),
            ...Array.from({ length: 19 }, (_, index) =>
              post(index + 101, 'another-author'),
            ),
          ],
        });
      if (url === secondPage)
        return new Promise((resolve) => {
          resolveSecond = resolve;
        });
      if (url === thirdPage)
        return json({ ...emptyFeed, page: 2, items: [post(6), post(7)] });
      throw new Error(`Unexpected URL ${url}`);
    });

    render(<App />);
    const activity = within(
      await screen.findByRole('region', { name: 'Activity' }),
    );
    await activity.findByText('Activity post 1');
    expect(activity.queryByText('Activity post 101')).toBeNull();
    expect(activity.queryByText('You haven’t posted yet.')).toBeNull();
    expect(fetch).toHaveBeenCalledWith(secondPage, expect.anything());
    await act(async () =>
      resolveSecond(
        json({
          ...emptyFeed,
          page: 1,
          hasNext: true,
          items: [post(1), post(2), post(3), post(4), post(5), post(6)],
        }),
      ),
    );
    await activity.findByRole('button', { name: 'Load older posts' });
    expect(activity.getAllByRole('article')).toHaveLength(5);
    expect(activity.queryByText('Activity post 6')).toBeNull();
    expect(fetch).not.toHaveBeenCalledWith(thirdPage, expect.anything());

    act(() => reachBottom?.());
    await activity.findByText('Activity post 7');
    expect(
      activity
        .getAllByRole('article')
        .map((card) => card.querySelector('.post-text')?.textContent),
    ).toEqual(
      Array.from({ length: 7 }, (_, index) => `Activity post ${index + 1}`),
    );
    expect(
      activity.queryByRole('button', { name: 'Load older posts' }),
    ).toBeNull();
  });

  it('retries the failed page and only shows the empty state after the feed is exhausted', async () => {
    restore();
    window.history.replaceState(null, '', '/profile');
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(
        json({
          ...emptyFeed,
          items: [post(1, 'another-author')],
          hasNext: true,
        }),
      )
      .mockResolvedValueOnce(json({ code: 'SOCIAL_UNAVAILABLE' }, 503))
      .mockResolvedValueOnce(json({ ...emptyFeed, page: 1 }));
    render(<App />);

    const activity = within(
      await screen.findByRole('region', { name: 'Activity' }),
    );
    const retry = await activity.findByRole('button', {
      name: 'Retry activity',
    });
    expect(activity.queryByText('You haven’t posted yet.')).toBeNull();
    await userEvent.click(retry);
    await activity.findByText('You haven’t posted yet.');
    expect(vi.mocked(fetch).mock.calls.map(([url]) => url)).toEqual([
      '/api/users/me',
      feedPath,
      feedPath.replace('page=0', 'page=1'),
      feedPath.replace('page=0', 'page=1'),
    ]);
  });
});

describe('profile page', () => {
  it('prefills the editor and cancels without saving the draft', async () => {
    restore();
    window.history.replaceState(null, '', '/profile');
    vi.mocked(fetch)
      .mockResolvedValueOnce(json({ ...profile, bio: 'Gardens' }))
      .mockResolvedValueOnce(json(emptyFeed));
    render(<App />);
    await screen.findByText('You haven’t posted yet.');

    await userEvent.click(
      await screen.findByRole('button', { name: 'Edit profile' }),
    );
    const firstName = screen.getByRole<HTMLInputElement>('textbox', {
      name: 'First name',
    });
    expect(firstName.value).toBe('Sam');
    expect(firstName).toBe(document.activeElement);
    expect(
      screen.getByRole<HTMLInputElement>('textbox', { name: 'Last name' })
        .value,
    ).toBe('Example');
    expect(
      screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Bio' }).value,
    ).toBe('Gardens');
    await userEvent.type(firstName, 'uel');
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(
      screen.queryByRole('form', { name: 'Edit your profile' }),
    ).toBeNull();
    expect(screen.getByRole('heading', { name: 'Sam Example' })).toBeTruthy();
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('sends the changed fields and uses the returned profile across pages', async () => {
    restore();
    window.history.replaceState(null, '', '/profile');
    vi.mocked(fetch)
      .mockResolvedValueOnce(json({ ...profile, bio: 'Gardens' }))
      .mockResolvedValueOnce(json(emptyFeed))
      .mockResolvedValueOnce(json({ ...profile, firstName: 'Jordan' }));
    const user = userEvent.setup();
    render(<App />);
    await screen.findByText('You haven’t posted yet.');

    await user.click(
      await screen.findByRole('button', { name: 'Edit profile' }),
    );
    const firstName = screen.getByRole('textbox', { name: 'First name' });
    await user.clear(firstName);
    await user.type(firstName, 'Jordan');
    await user.clear(screen.getByRole('textbox', { name: 'Bio' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await screen.findByText('Profile updated.');
    expect(fetch).toHaveBeenLastCalledWith(
      '/api/users/me',
      expect.objectContaining({
        method: 'PATCH',
        headers: expect.objectContaining({
          Authorization: 'Bearer access-token',
          'Content-Type': 'application/json',
        }),
        body: JSON.stringify({ firstName: 'Jordan', bio: null }),
      }),
    );
    expect(
      screen.getByRole('heading', { name: 'Jordan Example' }),
    ).toBeTruthy();
    expect(screen.getByText('No bio yet.')).toBeTruthy();
    await user.click(screen.getByRole('link', { name: 'Feed' }));
    expect(
      await screen.findByRole('heading', { name: /Hi, Jordan/ }),
    ).toBeTruthy();
    expect(
      vi.mocked(fetch).mock.calls.filter(([url]) => url === '/api/users/me'),
    ).toHaveLength(2);
  });

  it('keeps the draft when a save fails and lets the user retry', async () => {
    restore();
    window.history.replaceState(null, '', '/profile');
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(json(emptyFeed))
      .mockResolvedValueOnce(json({ code: 'REQUEST_TOO_LARGE' }, 413))
      .mockResolvedValueOnce(json({ ...profile, bio: 'Gardens' }));
    render(<App />);
    await screen.findByText('You haven’t posted yet.');

    await userEvent.click(
      await screen.findByRole('button', { name: 'Edit profile' }),
    );
    const bio = screen.getByRole<HTMLTextAreaElement>('textbox', {
      name: 'Bio',
    });
    fireEvent.change(bio, { target: { value: '🌱'.repeat(4096) } });
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('alert')).toHaveProperty(
      'textContent',
      'Your profile update is too large. Try shortening your bio.',
    );
    expect(fetch).toHaveBeenCalledTimes(2);

    fireEvent.change(bio, { target: { value: 'Gardens' } });
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
    expect(screen.getByRole('alert')).toBeTruthy();
    expect(bio.value).toBe('Gardens');
    expect(screen.getByText('No bio yet.')).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByText('Profile updated.');
    expect(screen.getByText('Gardens')).toBeTruthy();
  });

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
      'Events',
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
    expect(
      await screen.findByRole('heading', { name: /Hi, Sam/ }),
    ).toBeTruthy();
    expect(screen.queryByText('sam@example.com')).toBeNull();
    expect(
      vi.mocked(fetch).mock.calls.filter(([url]) => url === '/api/users/me'),
    ).toHaveLength(1);

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

describe('shared post pages and author actions', () => {
  const postId = 'c597c9f7-4c4f-4c71-83aa-866fa418a5ee';
  const postRoute = `/posts/${postId}`;
  const postApi = `/api${postRoute}`;
  const imageReference =
    'social-posts/posts/bb73653c-38f9-4137-ad8d-668350f19012.png';
  const original = {
    id: postId,
    authorId: id,
    textContent: 'Meet at the park gate.',
    imageReference: null as string | null,
    createdAt: '2026-11-17T00:00:00Z',
    likeCount: 0,
    commentCount: 1,
    liked: false,
    saved: false,
  };

  async function options(action: string) {
    await userEvent.click(screen.getByRole('button', { name: 'Post options' }));
    await userEvent.click(screen.getByRole('button', { name: action }));
    return within(screen.getByRole('dialog', { name: action }));
  }

  it('returns to a shared post after login and expands its comments', async () => {
    window.history.replaceState(null, '', postRoute);
    vi.mocked(fetch).mockImplementation(async (url) => {
      if (url === '/api/auth/login') return json(result());
      if (url === '/api/users/me') return json(profile);
      if (url === postApi) return json(original);
      if (url === `${postApi}/comments?page=0&size=100`) {
        return json({
          items: [
            {
              id: 'comment-1',
              postId,
              authorId: id,
              content: 'See you there!',
              createdAt: original.createdAt,
            },
          ],
          page: 0,
          size: 100,
          hasNext: false,
        });
      }
      throw new Error('Unexpected request');
    });
    const view = render(<App />);
    await screen.findByLabelText('Email address');
    expect(window.location.pathname).toBe('/login');
    expect(new URLSearchParams(window.location.search).get('next')).toBe(
      postRoute,
    );
    expect(fetch).not.toHaveBeenCalled();
    view.unmount();
    render(<App />);
    await screen.findByLabelText('Email address');
    const user = await fill();
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    await screen.findByText('See you there!');
    expect(window.location.pathname).toBe(postRoute);
    expect(
      screen.getByRole('navigation', { name: 'Main navigation' }),
    ).toBeTruthy();
    const commentBox = screen.getByRole('textbox', { name: 'Write a comment' });
    expect(document.activeElement).not.toBe(commentBox);
    expect(screen.getAllByRole('article')).toHaveLength(1);
    expect(fetch).not.toHaveBeenCalledWith(feedPath, expect.anything());
  });

  it('shows an unavailable message for a deleted link', async () => {
    restore();
    window.history.replaceState(null, '', postRoute);
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(json({ code: 'POST_NOT_FOUND' }, 404));
    render(<App />);
    await screen.findByText('This post is no longer available.');
    expect(
      screen.getByRole('link', { name: 'Back to feed' }).getAttribute('href'),
    ).toBe('/home');
  });

  it('copies a direct link and keeps author controls hidden on someone else’s post', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    const user = userEvent.setup();
    const writeText = vi
      .spyOn(navigator.clipboard, 'writeText')
      .mockResolvedValue(undefined);
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(
        json({
          ...emptyFeed,
          items: [{ ...original, authorId: 'other-user' }],
        }),
      );
    render(<App />);
    await user.click(
      await screen.findByRole('button', { name: 'Post options' }),
    );
    expect(screen.queryByRole('button', { name: 'Edit content' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add image' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete post' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Copy link' }));
    await screen.findByText('Link copied.');
    expect(writeText).toHaveBeenCalledWith(
      new URL(postRoute, window.location.origin).href,
    );
  });

  it('edits exact text, disables blank text for text-only posts, and confirms deletion', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    const updated = '  Meet at the south gate.  ';
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(json({ ...emptyFeed, items: [original] }))
      .mockResolvedValueOnce(json({ ...original, textContent: updated }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    render(<App />);
    await screen.findByText(original.textContent);
    const edit = await options('Edit content');
    const textbox = edit.getByRole('textbox', { name: 'Post text' });
    await userEvent.clear(textbox);
    expect(
      edit.getByRole<HTMLButtonElement>('button', { name: 'Save changes' })
        .disabled,
    ).toBe(true);
    await userEvent.type(textbox, updated);
    await userEvent.click(edit.getByRole('button', { name: 'Save changes' }));
    await screen.findByText(updated.trim());
    expect(fetch).toHaveBeenLastCalledWith(
      postApi,
      expect.objectContaining({
        method: 'PATCH',
        body: JSON.stringify({ textContent: updated }),
      }),
    );

    const first = await options('Delete post');
    await userEvent.click(first.getByRole('button', { name: 'Cancel' }));
    expect(fetch).not.toHaveBeenCalledWith(
      postApi,
      expect.objectContaining({ method: 'DELETE' }),
    );
    const deletion = await options('Delete post');
    await userEvent.click(
      deletion.getByRole('button', { name: 'Delete post' }),
    );
    await screen.findByText('Post deleted.');
    expect(screen.queryByText(updated.trim())).toBeNull();
    expect(fetch).toHaveBeenLastCalledWith(
      postApi,
      expect.objectContaining({ method: 'DELETE' }),
    );
  });

  it('adds and replaces an image, clears text with an image, and only permits removal when text exists', async () => {
    restore();
    window.history.replaceState(null, '', '/home');
    auth.storageFrom.mockReturnValue({
      getPublicUrl: () => ({
        data: { publicUrl: 'https://example.test/image.png' },
      }),
    });
    vi.stubGlobal(
      'URL',
      class extends URL {
        static createObjectURL = () => 'blob:test-image';
        static revokeObjectURL = vi.fn();
      },
    );
    const withImage = { ...original, imageReference };
    const imageOnly = { ...withImage, textContent: null };
    const updatedImage = {
      ...imageOnly,
      imageReference: imageReference.replace('.png', '.jpg'),
    };
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(profile))
      .mockResolvedValueOnce(json({ ...emptyFeed, items: [original] }))
      .mockResolvedValueOnce(json(withImage))
      .mockResolvedValueOnce(json(imageOnly))
      .mockResolvedValueOnce(json(updatedImage))
      .mockResolvedValueOnce(
        json({ ...updatedImage, textContent: original.textContent }),
      )
      .mockResolvedValueOnce(json(original));
    render(<App />);
    await screen.findByText(original.textContent);
    const add = await options('Add image');
    const image = new File(['png'], 'photo.png', { type: 'image/png' });
    await userEvent.upload(add.getByLabelText('Choose an image'), image);
    await userEvent.click(add.getByRole('button', { name: 'Add image' }));
    await screen.findByRole('img', { name: 'Image attached to this post' });
    const put = vi.mocked(fetch).mock.lastCall;
    expect(put?.[0]).toBe(`${postApi}/image`);
    expect(put?.[1]?.method).toBe('PUT');
    expect(new Headers(put?.[1]?.headers).has('Content-Type')).toBe(false);
    expect((put?.[1]?.body as FormData).get('image')).toBe(image);

    const content = await options('Edit content');
    await userEvent.clear(content.getByRole('textbox', { name: 'Post text' }));
    await userEvent.click(
      content.getByRole('button', { name: 'Save changes' }),
    );
    await waitFor(() =>
      expect(screen.queryByText(original.textContent)).toBeNull(),
    );
    expect(fetch).toHaveBeenLastCalledWith(
      postApi,
      expect.objectContaining({
        method: 'PATCH',
        body: '{"textContent":null}',
      }),
    );

    const replace = await options('Edit image');
    expect(replace.queryByRole('button', { name: 'Remove image' })).toBeNull();
    const replacement = new File(['jpeg'], 'photo.jpg', { type: 'image/jpeg' });
    await userEvent.upload(
      replace.getByLabelText('Choose a replacement'),
      replacement,
    );
    await userEvent.click(
      replace.getByRole('button', { name: 'Save changes' }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(
      (vi.mocked(fetch).mock.lastCall?.[1]?.body as FormData).get('image'),
    ).toBe(replacement);

    const restoreText = await options('Edit content');
    await userEvent.type(
      restoreText.getByRole('textbox', { name: 'Post text' }),
      original.textContent,
    );
    await userEvent.click(
      restoreText.getByRole('button', { name: 'Save changes' }),
    );
    await screen.findByText(original.textContent);
    const remove = await options('Edit image');
    await userEvent.click(remove.getByRole('button', { name: 'Remove image' }));
    await waitFor(() =>
      expect(
        screen.queryByRole('img', { name: 'Image attached to this post' }),
      ).toBeNull(),
    );
    expect(fetch).toHaveBeenLastCalledWith(
      `${postApi}/image`,
      expect.objectContaining({ method: 'DELETE' }),
    );
  });
});

describe('deleting a comment', () => {
  const postId = 'f13820e2-6a20-4b96-8632-af53ab63a6eb';
  const commentId = '5d1c9b79-f40d-4700-87fc-23fa56fcd61a';
  const postPath = `/api/posts/${postId}`;
  const commentPath = `${postPath}/comments/${commentId}`;
  const post = {
    id: postId,
    authorId: 'other-user',
    textContent: 'Park cleanup photos',
    imageReference: null,
    createdAt: '2026-11-17T00:00:00Z',
    likeCount: 0,
    commentCount: 2,
    liked: false,
    saved: false,
  };
  const own = {
    id: commentId,
    postId,
    authorId: id,
    content: 'I can bring gloves.',
    createdAt: post.createdAt,
  };
  const other = {
    ...own,
    id: 'another-comment',
    authorId: post.authorId,
    content: 'Thank you for helping!',
  };

  it.each([
    [204, 'Comment deleted.'],
    [404, 'This comment is no longer available.'],
  ])(
    'offers removal only on your own comment and reconciles a %s response',
    async (status, message) => {
      restore();
      window.history.replaceState(null, '', `/posts/${postId}`);
      vi.mocked(fetch)
        .mockResolvedValueOnce(json(profile))
        .mockResolvedValueOnce(json(post))
        .mockResolvedValueOnce(
          json({ items: [other, own], page: 0, size: 100, hasNext: false }),
        )
        .mockResolvedValueOnce(
          status === 204
            ? new Response(null, { status })
            : json({ code: 'COMMENT_NOT_FOUND' }, status),
        );
      render(<App />);
      const otherComment = (await screen.findByText(other.content)).closest(
        'li',
      )!;
      expect(
        within(otherComment).queryByRole('button', { name: 'Comment options' }),
      ).toBeNull();
      const button = screen.getByRole('button', { name: 'Comment options' });
      await userEvent.click(button);
      expect(screen.queryByRole('button', { name: 'Edit comment' })).toBeNull();
      await userEvent.click(
        screen.getByRole('button', { name: 'Delete comment' }),
      );
      const dialog = screen.getByRole('dialog', { name: 'Delete comment' });
      const cancel = within(dialog).getByRole('button', { name: 'Cancel' });
      expect(document.activeElement).toBe(cancel);
      await userEvent.click(cancel);
      await waitFor(() => expect(document.activeElement).toBe(button));
      expect(fetch).not.toHaveBeenCalledWith(commentPath, expect.anything());

      await userEvent.click(button);
      await userEvent.click(
        screen.getByRole('button', { name: 'Delete comment' }),
      );
      const confirm = screen.getByRole('dialog', { name: 'Delete comment' });
      await userEvent.click(
        within(confirm).getByRole('button', { name: 'Delete comment' }),
      );
      await screen.findByText(message);
      expect(screen.queryByText(own.content)).toBeNull();
      expect(screen.getByText(other.content)).toBeTruthy();
      expect(screen.getByText('0 likes · 1 comment')).toBeTruthy();
      expect(fetch).toHaveBeenLastCalledWith(
        commentPath,
        expect.objectContaining({
          method: 'DELETE',
          headers: expect.objectContaining({
            Authorization: 'Bearer access-token',
          }),
        }),
      );
    },
  );
});
