// @vitest-environment jsdom
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Profile } from '../api/auth';
import { maxImageBytes } from '../api/imageFiles';
import { OwnAvatarContext, useOwnAvatar } from '../auth/ownAvatar';
import { ProfileAvatarEditor } from './ProfileAvatarEditor';

const path = '/api/users/me/avatar';
const initial: Profile = {
  id: 'b6034174-d377-4ad4-b96a-6777660143e6',
  firstName: 'Sam',
  lastName: 'Example',
  role: 'STANDARD',
  email: 'sam@example.test',
  avatarUrl: null,
  bio: null,
  organizationId: null,
  organizationRole: null,
  createdAt: '2026-11-01T18:00:00Z',
};
const currentRef = `profile-avatars/avatars/${initial.id}/75ae3610-ac80-4a9b-b393-6f57217f0737.png`;
const nextRef = `profile-avatars/avatars/${initial.id}/a739afdb-8ed8-4814-a925-278e88c90855.png`;
const photo = new File(['photo bytes'], 'avatar.png', { type: 'image/png' });
const createObjectURL = vi.fn<(value: Blob) => string>();
const revokeObjectURL = vi.fn<(url: string) => void>();
const onUnauthorized = vi.fn();

function renderAvatar(profile: Profile = initial) {
  function AccountAvatar() {
    const [current, setProfile] = useState(profile);
    const avatar = useOwnAvatar(
      { profile: current, accessToken: 'test-access' },
      onUnauthorized,
    );
    return (
      <OwnAvatarContext value={avatar}>
        <ProfileAvatarEditor
          profile={current}
          accessToken="test-access"
          initials="SE"
          onUnauthorized={onUnauthorized}
          onUpdated={setProfile}
        />
      </OwnAvatarContext>
    );
  }
  return render(<AccountAvatar />);
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function serve({
  before = initial,
  uploadFailure = false,
  invalidImageOnce = false,
  readStatus = 200,
}: {
  before?: Profile;
  uploadFailure?: boolean;
  invalidImageOnce?: boolean;
  readStatus?: number;
} = {}) {
  let current = before;
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url !== path) throw new Error(`Unexpected request: ${url}`);
    if (init?.method === 'PUT') {
      if (invalidImageOnce) {
        invalidImageOnce = false;
        return json({ code: 'INVALID_IMAGE' }, 415);
      }
      if (uploadFailure) {
        uploadFailure = false;
        return json({ code: 'STORAGE_UNAVAILABLE' }, 503);
      }
      current = { ...current, avatarUrl: nextRef };
      return json(current);
    }
    if (init?.method === 'DELETE') {
      current = { ...current, avatarUrl: null };
      return json(current);
    }
    if (readStatus !== 200) return json({ code: 'UNAUTHORIZED' }, readStatus);
    return new Response(new Uint8Array([1, 2, 3]), {
      headers: { 'Content-Type': 'image/png' },
    });
  });
  vi.stubGlobal('fetch', fetch);
  return { fetch, current: () => current };
}

function pick(file: File) {
  fireEvent.change(screen.getByLabelText('Select profile photo'), {
    target: { files: [file] },
  });
}

function preview() {
  return within(screen.getByRole('dialog')).getByRole('img', {
    name: 'Circular profile photo preview',
  });
}

function decodePreview(width: number, height: number) {
  const image = preview();
  Object.defineProperties(image, {
    naturalWidth: { value: width },
    naturalHeight: { value: height },
  });
  fireEvent.load(image);
}

function mockCanvas() {
  const drawImage = vi.fn();
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(
    () => ({ drawImage }) as unknown as CanvasRenderingContext2D,
  );
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(
    (callback, type) => callback(new Blob(['normalized bytes'], { type })),
  );
  return drawImage;
}

function display(container: HTMLElement) {
  return container.querySelector('.profile-avatar img');
}

beforeEach(() => {
  vi.stubEnv('VITE_API_BASE_URL', '/api');
  let counter = 0;
  createObjectURL
    .mockReset()
    .mockImplementation(() => `blob:avatar-${++counter}`);
  revokeObjectURL.mockClear();
  onUnauthorized.mockClear();
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = createObjectURL;
      static revokeObjectURL = revokeObjectURL;
    },
  );
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
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe('own profile avatar', () => {
  it.each([
    {
      width: 6000,
      height: 4000,
      invalidImageOnce: false,
      uploads: 1,
      expected: [1024, 683],
    },
    {
      width: 800,
      height: 400,
      invalidImageOnce: true,
      uploads: 2,
      expected: [800, 400],
    },
  ])(
    'normalizes a browser-decodable JPEG with $width source pixels or an explicit backend rejection',
    async ({ width, height, invalidImageOnce, uploads, expected }) => {
      const { fetch } = serve({ invalidImageOnce });
      const drawImage = mockCanvas();
      const user = userEvent.setup();
      renderAvatar();
      const jpeg = new File(['JPEG bytes'], 'camera.jpg', {
        type: 'image/jpeg',
      });

      pick(jpeg);
      decodePreview(width, height);
      expect(fetch).not.toHaveBeenCalled();
      await user.click(screen.getByRole('button', { name: 'Save photo' }));

      await screen.findByText('Profile photo saved.');
      expect(drawImage).toHaveBeenCalledWith(
        expect.any(HTMLImageElement),
        0,
        0,
        ...expected,
      );
      expect(fetch).toHaveBeenCalledTimes(uploads);
      const sent = fetch.mock.calls.map(
        ([, request]) => (request?.body as FormData).get('image') as File,
      );
      if (invalidImageOnce) expect(sent[0]).toBe(jpeg);
      expect(sent.at(-1)).toMatchObject({
        name: 'camera.jpg',
        type: 'image/jpeg',
        size: 16,
      });
    },
  );

  it('opens the picker from initials, saves one multipart file, shows the preview immediately and reloads through the authenticated GET', async () => {
    const { fetch, current } = serve();
    const user = userEvent.setup();
    const view = renderAvatar();
    const camera = screen.getByRole('button', { name: 'Add profile photo' });
    const input = screen.getByLabelText('Select profile photo');
    const inputClick = vi.spyOn(input, 'click');
    expect(screen.getByText('SE')).toBeTruthy();
    await user.click(camera);
    expect(inputClick).toHaveBeenCalledOnce();

    pick(photo);
    expect(
      screen.getByRole('dialog', { name: 'Add profile photo' }),
    ).toBeTruthy();
    expect(fetch).not.toHaveBeenCalled();
    const candidate = preview();
    expect(candidate.getAttribute('src')).toBe('blob:avatar-1');
    expect(
      screen.getByRole<HTMLButtonElement>('button', { name: 'Save photo' })
        .disabled,
    ).toBe(true);
    fireEvent.load(candidate);
    await user.click(screen.getByRole('button', { name: 'Save photo' }));

    await screen.findByText('Profile photo saved.');
    await waitFor(() =>
      expect(display(view.container)?.getAttribute('src')).toBe('blob:avatar-2'),
    );
    expect(fetch).toHaveBeenCalledOnce();
    const [url, request] = fetch.mock.calls[0];
    expect(url).toBe(path);
    expect(request?.method).toBe('PUT');
    expect(request?.headers).toEqual({
      Authorization: 'Bearer test-access',
      Accept: 'application/json',
    });
    expect(Array.from((request?.body as FormData).entries())).toEqual([
      ['image', photo],
    ]);
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('button', { name: 'Change profile photo' }),
      ),
    );

    view.unmount();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:avatar-1');
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:avatar-2');
    const next = renderAvatar(current());
    await waitFor(() =>
      expect(display(next.container)?.getAttribute('src')).toBe(
        'blob:avatar-3',
      ),
    );
    expect(fetch).toHaveBeenLastCalledWith(
      path,
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer test-access',
        }),
      }),
    );
  });

  it('keeps the saved photo and preview on failure, then retries the replacement', async () => {
    const { fetch } = serve({
      before: { ...initial, avatarUrl: currentRef },
      uploadFailure: true,
    });
    const user = userEvent.setup();
    const { container } = renderAvatar({ ...initial, avatarUrl: currentRef });
    await waitFor(() =>
      expect(display(container)?.getAttribute('src')).toBe('blob:avatar-1'),
    );
    await user.click(
      screen.getByRole('button', { name: 'Change profile photo' }),
    );
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: 'Replace photo' }),
    );
    await user.click(screen.getByRole('button', { name: 'Replace photo' }));
    pick(photo);
    const candidate = preview();
    fireEvent.load(candidate);
    await user.click(screen.getByRole('button', { name: 'Save photo' }));

    await screen.findByRole('alert');
    expect(
      screen.getByText('Couldn’t confirm the photo save. Try again.'),
    ).toBeTruthy();
    expect(preview()).toBe(candidate);
    expect(display(container)?.getAttribute('src')).toBe('blob:avatar-1');

    await user.click(screen.getByRole('button', { name: 'Save photo' }));
    await screen.findByText('Profile photo saved.');
    await waitFor(() =>
      expect(display(container)?.getAttribute('src')).toBe('blob:avatar-3'),
    );
    expect(
      fetch.mock.calls.filter(([, request]) => request?.method === 'PUT'),
    ).toHaveLength(2);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:avatar-1');
  });

  it('supports choosing another photo and cancelling without an upload', async () => {
    const { fetch } = serve();
    renderAvatar();
    const user = userEvent.setup();
    pick(photo);
    const input = screen.getByLabelText('Select profile photo');
    const inputClick = vi.spyOn(input, 'click');
    await user.click(screen.getByRole('button', { name: 'Choose another' }));
    expect(inputClick).toHaveBeenCalledOnce();
    pick(new File(['new bytes'], 'another.jpg', { type: 'image/jpeg' }));
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:avatar-1');
    expect(preview().getAttribute('src')).toBe('blob:avatar-2');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:avatar-2');
    expect(fetch).not.toHaveBeenCalled();
  });

  it.each([
    [
      new File(['text'], 'photo.gif', { type: 'image/gif' }),
      'Choose a valid JPEG or PNG photo.',
    ],
    [
      new File([new Uint8Array(maxImageBytes + 1)], 'photo.png', {
        type: 'image/png',
      }),
      'Choose a photo that is 5 MB or smaller.',
    ],
  ])(
    'rejects an unsupported or oversized file before uploading (%s)',
    (file, message) => {
      const { fetch } = serve();
      renderAvatar();
      pick(file);
      expect(screen.getByRole('alert').textContent).toBe(message);
      expect(
        screen.getByRole<HTMLButtonElement>('button', { name: 'Save photo' })
          .disabled,
      ).toBe(true);
      expect(fetch).not.toHaveBeenCalled();
      expect(createObjectURL).not.toHaveBeenCalled();
    },
  );

  it('asks for confirmation before deletion and restores initials after success', async () => {
    const legacy = 'https://example.test/previous.png';
    const { fetch } = serve({ before: { ...initial, avatarUrl: legacy } });
    const user = userEvent.setup();
    const { container } = renderAvatar({ ...initial, avatarUrl: legacy });
    const open = () =>
      user.click(screen.getByRole('button', { name: 'Change profile photo' }));
    expect(display(container)?.getAttribute('src')).toBe(legacy);
    await open();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('button', { name: 'Remove photo' })).toBeNull();
    await open();
    await user.click(screen.getByRole('button', { name: 'Remove photo' }));
    expect(
      screen.getByRole('dialog', { name: 'Remove profile photo?' }),
    ).toBeTruthy();
    expect(fetch).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(fetch).not.toHaveBeenCalled();
    expect(display(container)?.getAttribute('src')).toBe(legacy);

    await open();
    await user.click(screen.getByRole('button', { name: 'Remove photo' }));
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', {
        name: 'Remove photo',
      }),
    );
    await screen.findByText('Profile photo removed.');
    expect(fetch).toHaveBeenCalledOnce();
    expect(fetch).toHaveBeenCalledWith(
      path,
      expect.objectContaining({ method: 'DELETE', body: undefined }),
    );
    expect(display(container)).toBeNull();
    expect(screen.getByText('SE')).toBeTruthy();
  });

  it('uses the session-expiry handler on an unauthorized avatar read', async () => {
    serve({ before: { ...initial, avatarUrl: currentRef }, readStatus: 401 });
    renderAvatar({ ...initial, avatarUrl: currentRef });
    await waitFor(() => expect(onUnauthorized).toHaveBeenCalledOnce());
  });
});
