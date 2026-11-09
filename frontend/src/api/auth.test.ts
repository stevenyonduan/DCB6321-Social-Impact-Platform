import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, authenticate, getProfile } from './auth';

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
const input = { email: 'sam@example.com', password: 'secret password' };
afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.useRealTimers();
});

describe('account API failures', () => {
  it.each([
    json({ user: {}, session: {} }),
    json({
      user: {
        id: 'id',
        email: 'email',
        role: 'ROOT',
        firstName: 'Sam',
        lastName: 'Example',
      },
      session: {},
    }),
    new Response('<html>proxy failure</html>', {
      status: 502,
      headers: { 'Content-Type': 'text/html' },
    }),
    new Response('{broken', {
      headers: { 'Content-Type': 'application/json' },
    }),
  ])(
    'rejects malformed and non-JSON responses without exposing the body',
    async (response) => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response));
      await expect(authenticate('login', input)).rejects.toBeInstanceOf(
        ApiError,
      );
      expect(fetch).toHaveBeenCalledTimes(1);
    },
  );

  it('never sends unexpected role/identity fields', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(json({ code: 'INVALID_REQUEST' }, 400)),
    );
    const request = {
      ...input,
      firstName: 'Sam',
      lastName: 'Example',
      role: 'ADMIN',
      id: 'attacker',
    };
    await expect(authenticate('signup', request)).rejects.toBeInstanceOf(
      ApiError,
    );
    const options = vi.mocked(fetch).mock.calls[0][1];
    expect(JSON.parse(String(options?.body))).toEqual({
      ...input,
      firstName: 'Sam',
      lastName: 'Example',
    });
    expect(options).toEqual(
      expect.objectContaining({
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
      }),
    );
  });

  it('sanitizes network errors and does not retry signup', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new Error('secret driver details')),
    );
    await expect(
      authenticate('signup', {
        ...input,
        firstName: 'Sam',
        lastName: 'Example',
      }),
    ).rejects.toThrow('The service is unavailable.');
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('times out a stalled request without retrying it', async () => {
    vi.useFakeTimers();
    // Stub the timer-backed signal to make the full request deadline deterministic.
    vi.spyOn(AbortSignal, 'timeout').mockImplementation((ms) => {
      const controller = new AbortController();
      setTimeout(() => controller.abort(), ms);
      return controller.signal;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url, options: RequestInit) =>
          new Promise((_resolve, reject) => {
            options.signal?.addEventListener('abort', () =>
              reject(new Error('timeout')),
            );
          }),
      ),
    );
    const pending = expect(authenticate('login', input)).rejects.toThrow(
      'The service is unavailable.',
    );
    await vi.advanceTimersByTimeAsync(30001);
    await pending;
    expect(fetch).toHaveBeenCalledTimes(1);
    vi.restoreAllMocks();
  });

  it('uses the configured API origin and sends the profile token only as a header', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com/api/');
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(json({ code: 'UNAUTHORIZED' }, 401)),
    );
    await expect(getProfile('private-token')).rejects.toMatchObject({
      status: 401,
      code: 'UNAUTHORIZED',
    });
    expect(fetch).toHaveBeenCalledWith(
      'https://api.example.com/api/users/me',
      expect.objectContaining({
        headers: {
          Authorization: 'Bearer private-token',
          Accept: 'application/json',
        },
      }),
    );
  });
});
