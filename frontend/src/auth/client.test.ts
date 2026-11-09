import { afterEach, describe, expect, it, vi } from 'vitest';
import { authClient } from './client';

afterEach(() => vi.unstubAllEnvs());

describe('public auth configuration', () => {
  it('fails safely before account creation when public configuration is missing', () => {
    vi.stubEnv('VITE_SUPABASE_URL', '');
    vi.stubEnv('VITE_SUPABASE_PUBLISHABLE_KEY', '');
    expect(() => authClient()).toThrow('Sign-in is not configured yet.');
  });
  it.each([
    'http://example.com',
    'https://example.com/path',
    'https://user:password@example.com',
    'https://example.com?secret=value',
  ])('rejects a non-project origin: %s', (url) => {
    vi.stubEnv('VITE_SUPABASE_URL', url);
    vi.stubEnv('VITE_SUPABASE_PUBLISHABLE_KEY', 'sb_publishable_example');
    expect(() => authClient()).toThrow('Sign-in is not configured yet.');
  });
  it('does not accept a secret key as browser configuration', () => {
    vi.stubEnv('VITE_SUPABASE_URL', 'https://example.com');
    vi.stubEnv('VITE_SUPABASE_PUBLISHABLE_KEY', 'sb_secret_do-not-expose');
    expect(() => authClient()).toThrow('Sign-in is not configured yet.');
  });
});
