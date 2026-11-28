// @vitest-environment jsdom
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Profile } from '../api/auth';
import type { Interest } from '../api/interests';
import { OrganizationPage } from '../pages/Organization';
import { ProfilePage } from '../pages/Profile';

const organizationId = 'a975e6c1-38b9-438a-a798-12741f117870';
const organizationPath = `/api/organizations/${organizationId}`;
const organizationProfileInterests = `${organizationPath}/interests`;
const personalSelection = '/api/users/me/interests';
const organizationSelection = '/api/organizations/me/interests';
const catalogPath = '/api/interests';
const environment = {
  id: '75ae3610-ac80-4a9b-b393-6f57217f0737',
  name: 'Environment & Outdoors',
};
const food = {
  id: 'a739afdb-8ed8-4814-a925-278e88c90855',
  name: 'Food Access',
};
const catalog = [environment, food];
const profile: Profile = {
  id: 'b6034174-d377-4ad4-b96a-6777660143e6',
  role: 'STANDARD',
  firstName: 'Sam',
  lastName: 'Example',
  email: 'sam@example.com',
  bio: 'I enjoy volunteering.',
  avatarUrl: null,
  organizationId: null,
  organizationRole: null,
  createdAt: '2026-11-01T18:00:00Z',
};
const organization = {
  id: organizationId,
  name: 'Atlanta Park Stewards',
  description: null,
  website: null,
  isVerified: true,
  createdAt: '2026-11-01T18:00:00Z',
};

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function serve({
  personal = [environment],
  team = [food],
  failCatalog = false,
  failPersonalSave = false,
  teamReadStatus = 200,
  teamWriteStatus = 200,
}: {
  personal?: Interest[];
  team?: Interest[];
  failCatalog?: boolean;
  failPersonalSave?: boolean;
  teamReadStatus?: number;
  teamWriteStatus?: number;
} = {}) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === catalogPath) {
      if (failCatalog) {
        failCatalog = false;
        return json({ code: 'INTERESTS_UNAVAILABLE' }, 503);
      }
      return json(catalog);
    }
    if (url === organizationProfileInterests) {
      return teamReadStatus === 200
        ? json(team)
        : json({ code: 'UNAUTHORIZED' }, teamReadStatus);
    }
    if (url === personalSelection || url === organizationSelection) {
      if (init?.method === 'PUT') {
        if (url === personalSelection && failPersonalSave) {
          failPersonalSave = false;
          return json({ code: 'INTERESTS_UNAVAILABLE' }, 503);
        }
        if (url === organizationSelection && teamWriteStatus !== 200)
          return json({ code: 'FORBIDDEN' }, teamWriteStatus);
        const { interestIds } = JSON.parse(String(init.body)) as {
          interestIds: string[];
        };
        const selection = catalog.filter(({ id }) => interestIds.includes(id));
        if (url === personalSelection) personal = selection;
        else team = selection;
        return json(selection);
      }
      return json(url === personalSelection ? personal : team);
    }
    if (url === '/api/users/me' && init?.method === 'PATCH')
      return json({ ...profile, ...JSON.parse(String(init.body)) });
    if (url === organizationPath) return json(organization);
    if (
      url.startsWith('/api/posts?') ||
      url.startsWith(`${organizationPath}/opportunities?`)
    )
      return json({ items: [], page: 0, size: 20, hasNext: false });
    throw new Error(`Unexpected API request: ${url}`);
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

const onUnauthorized = vi.fn();
const noop = () => {};

function PersonalProfile() {
  const [current, setProfile] = useState(profile);
  return (
    <ProfilePage
      profile={current}
      accessToken="access-token"
      onUnauthorized={onUnauthorized}
      onRefresh={noop}
      onProfileUpdated={setProfile}
      onOrganizationCreated={noop}
    />
  );
}

function renderOrganization(
  role: Profile['organizationRole'],
  id = organizationId,
) {
  return render(
    <OrganizationPage
      id={organizationId}
      account={{
        ...profile,
        organizationId: role ? id : null,
        organizationRole: role,
      }}
      accessToken="access-token"
      onUnauthorized={onUnauthorized}
    />,
  );
}

async function openEditor() {
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Edit profile' }));
  return user;
}

function savedTags() {
  return screen.getByRole('list', { name: 'Interests' });
}

beforeEach(() => {
  vi.stubEnv('VITE_API_BASE_URL', '/api');
  onUnauthorized.mockClear();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe('profile interests', () => {
  it('shows saved tags under Bio, discards cancelled choices, and can replace or clear selections', async () => {
    const fetch = serve();
    render(<PersonalProfile />);
    await screen.findByRole('list', { name: 'Interests' });
    expect(
      within(screen.getByRole('region', { name: 'Bio' })).getByText(
        environment.name,
      ),
    ).toBeTruthy();
    expect(fetch).not.toHaveBeenCalledWith(catalogPath, expect.anything());

    const user = await openEditor();
    const option = (name: string) =>
      screen.findByRole<HTMLInputElement>('checkbox', { name });
    expect((await option(environment.name)).checked).toBe(true);
    const foodOption = await option(food.name);
    foodOption.focus();
    await user.keyboard('[Space]');
    expect(foodOption.checked).toBe(true);
    expect(within(savedTags()).queryByText(food.name)).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('button', { name: 'Edit profile' }),
      ),
    );

    await user.click(screen.getByRole('button', { name: 'Edit profile' }));
    expect((await option(food.name)).checked).toBe(false);
    await user.click(await option(environment.name));
    await user.click(await option(food.name));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await within(savedTags()).findByText(food.name);
    expect(within(savedTags()).queryByText(environment.name)).toBeNull();
    expect(fetch).toHaveBeenCalledWith(
      personalSelection,
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ interestIds: [food.id] }),
        headers: expect.objectContaining({
          Authorization: 'Bearer access-token',
        }),
      }),
    );
    expect(fetch).not.toHaveBeenCalledWith('/api/users/me', expect.anything());

    await user.click(screen.getByRole('button', { name: 'Edit profile' }));
    expect((await option(food.name)).checked).toBe(true);
    await user.click(await option(food.name));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByText('No interests yet.');
    expect(fetch).toHaveBeenCalledWith(
      personalSelection,
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ interestIds: [] }),
      }),
    );
  });

  it('retries a catalog load and preserves the draft when the interest save fails after the bio saved', async () => {
    const fetch = serve({ failCatalog: true, failPersonalSave: true });
    render(<PersonalProfile />);
    const user = await openEditor();
    const form = within(
      screen.getByRole('form', { name: 'Edit your profile' }),
    );
    await form.findByText('Interests could not be loaded.');
    expect(form.queryByRole('checkbox')).toBeNull();
    await user.click(form.getByRole('button', { name: 'Try again' }));
    await user.click(await form.findByRole('checkbox', { name: food.name }));
    await user.type(
      form.getByRole('textbox', { name: 'Bio' }),
      ' And gardening.',
    );
    await user.click(form.getByRole('button', { name: 'Save changes' }));
    await form.findByText(
      'Profile details saved. Interests could not be saved. Try again.',
    );
    expect(
      form.getByRole<HTMLInputElement>('checkbox', { name: food.name }).checked,
    ).toBe(true);
    expect(within(savedTags()).queryByText(food.name)).toBeNull();
    expect(
      within(screen.getByRole('region', { name: 'Bio' })).getByText(
        'I enjoy volunteering. And gardening.',
      ),
    ).toBeTruthy();

    await user.click(form.getByRole('button', { name: 'Save changes' }));
    await within(savedTags()).findByText(food.name);
    expect(
      fetch.mock.calls.filter(([url]) => url === '/api/users/me'),
    ).toHaveLength(1);
    expect(
      fetch.mock.calls.filter(
        ([url, init]) => url === personalSelection && init?.method === 'PUT',
      ),
    ).toHaveLength(2);
  });
});

describe('organization profile interests', () => {
  it.each(['OWNER', 'ADMIN'] as const)(
    'shows the tags under About and updates them when a matching %s saves',
    async (role) => {
      const fetch = serve();
      renderOrganization(role);
      const about = within(
        await screen.findByRole('region', { name: 'About' }),
      );
      await about.findByText(food.name);
      const user = await openEditor();
      const option = (name: string) =>
        screen.findByRole<HTMLInputElement>('checkbox', { name });
      expect((await option(environment.name)).checked).toBe(false);
      expect((await option(food.name)).checked).toBe(true);
      await user.click(await option(environment.name));
      await user.click(await option(food.name));
      expect(about.queryByText(environment.name)).toBeNull();
      await user.click(screen.getByRole('button', { name: 'Save changes' }));
      await screen.findByText('Organization profile updated.');
      expect(about.getByText(environment.name)).toBeTruthy();
      expect(about.queryByText(food.name)).toBeNull();
      await user.click(screen.getByRole('button', { name: 'Edit profile' }));
      expect((await option(environment.name)).checked).toBe(true);
      expect((await option(food.name)).checked).toBe(false);
      expect(fetch).toHaveBeenCalledWith(
        organizationSelection,
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({ interestIds: [environment.id] }),
        }),
      );
      expect(
        fetch.mock.calls.filter(
          ([url, init]) =>
            url === organizationProfileInterests && init?.method === 'GET',
        ),
      ).toHaveLength(1);
      expect(fetch).not.toHaveBeenCalledWith(
        personalSelection,
        expect.anything(),
      );
    },
  );

  it.each([null, 'OWNER'] as const)(
    'shows tags without edit access for an unrelated %s',
    async (role) => {
      const fetch = serve();
      renderOrganization(role, '5ffe052b-0b76-4d78-b89d-94163e2ed50a');
      const about = within(
        await screen.findByRole('region', { name: 'About' }),
      );
      await about.findByText(food.name);
      expect(screen.queryByRole('button', { name: 'Edit profile' })).toBeNull();
      expect(fetch).not.toHaveBeenCalledWith(
        organizationSelection,
        expect.anything(),
      );
      expect(fetch).not.toHaveBeenCalledWith(catalogPath, expect.anything());
    },
  );

  it('closes the editor when the organization interest write is denied', async () => {
    serve({ teamWriteStatus: 403 });
    renderOrganization('ADMIN');
    const user = await openEditor();
    await user.click(await screen.findByRole('checkbox', { name: food.name }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByText(
      /You do not have permission to edit this organization/,
    );
    expect(screen.queryByRole('button', { name: 'Edit profile' })).toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(within(savedTags()).getByText(food.name)).toBeTruthy();
  });

  it('uses the session-expiry flow when an interest read returns 401', async () => {
    serve({ teamReadStatus: 401 });
    renderOrganization('ADMIN');
    await waitFor(() => expect(onUnauthorized).toHaveBeenCalledOnce());
  });
});
