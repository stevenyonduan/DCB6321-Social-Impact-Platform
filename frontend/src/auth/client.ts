import { createClient, type SupabaseClient } from '@supabase/supabase-js';

// This contains provider sessions only. Never put database credentials in VITE_*.
export const SESSION_KEY = 'sow-auth-session';
let client: SupabaseClient | undefined;

export function authClient() {
  if (client) return client;
  const url = import.meta.env.VITE_SUPABASE_URL;
  const key = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY;
  if (!url || !key || !/^sb_publishable_[A-Za-z0-9_-]+$/.test(key)) {
    throw new Error('Sign-in is not configured yet. Please try again later.');
  }
  try {
    const origin = new URL(url);
    if (
      origin.protocol !== 'https:' ||
      origin.username ||
      origin.password ||
      origin.search ||
      origin.hash ||
      origin.pathname !== '/'
    )
      throw new Error();
    client = createClient(url, key, {
      global: {
        fetch: (input, init) =>
          fetch(input, {
            ...init,
            signal: init?.signal
              ? AbortSignal.any([init.signal, AbortSignal.timeout(15000)])
              : AbortSignal.timeout(15000),
          }),
      },
      auth: {
        persistSession: true,
        autoRefreshToken: true,
        detectSessionInUrl: false,
        storageKey: SESSION_KEY,
      },
    });
  } catch {
    throw new Error('Sign-in is not configured yet. Please try again later.');
  }
  return client;
}

// Ensure this browser forgets the session even when remote sign-out fails.
export function forgetStoredSession() {
  try {
    localStorage.removeItem(SESSION_KEY);
    sessionStorage.removeItem('sow-demo-session');
  } catch {
    // Storage may be unavailable; the SDK still handles its in-memory session.
  }
}
