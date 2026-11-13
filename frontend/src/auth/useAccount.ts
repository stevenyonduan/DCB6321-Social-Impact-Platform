import { useCallback, useEffect, useRef, useState } from 'react';
import type { Session } from '@supabase/supabase-js';
import {
  ApiError,
  authenticate,
  getProfile,
  type Credentials,
  type Profile,
  type Signup,
} from '../api/auth';
import { authClient, forgetStoredSession } from './client';

type AccountState =
  | {
      status: 'loading' | 'anonymous' | 'signing-out';
      profile?: never;
      message?: string;
    }
  | { status: 'ready'; profile: Profile; accessToken: string; message?: never }
  | { status: 'error'; profile?: never; message: string };

export function useAccount() {
  const [state, setState] = useState<AccountState>({ status: 'loading' });
  const [retry, setRetry] = useState(0);
  const epoch = useRef(0);
  const request = useRef<AbortController | null>(null);
  const signingOut = useRef(false);
  const mounted = useRef(false);

  const expireSession = useCallback(() => {
    ++epoch.current;
    request.current?.abort();
    forgetStoredSession();
    setState({
      status: 'anonymous',
      message: 'Your session has expired. Please log in again.',
    });
  }, []);

  useEffect(() => {
    mounted.current = true;
    let active = true;
    let expiry: ReturnType<typeof setTimeout> | undefined;
    let unsubscribe = () => {};
    async function load(session: Session | null) {
      if (!active || signingOut.current) return;
      const generation = ++epoch.current;
      clearTimeout(expiry);
      request.current?.abort();
      const controller = new AbortController();
      request.current = controller;
      if (!session) {
        setState({ status: 'anonymous' });
        return;
      }
      const remaining = (session.expires_at ?? 0) * 1000 - Date.now();
      if (remaining <= 0) {
        forgetStoredSession();
        setState({
          status: 'anonymous',
          message: 'Your session has expired. Please log in again.',
        });
        return;
      }
      expiry = setTimeout(
        () => {
          if (!active || epoch.current !== generation) return;
          ++epoch.current;
          controller.abort();
          forgetStoredSession();
          setState({
            status: 'anonymous',
            message: 'Your session has expired. Please log in again.',
          });
        },
        Math.min(remaining, 2147483647),
      );
      setState({ status: 'loading' });
      try {
        const profile = await getProfile(
          session.access_token,
          controller.signal,
        );
        if (profile.id !== session.user.id)
          throw new ApiError(401, 'UNAUTHORIZED');
        if (active && epoch.current === generation)
          setState({
            status: 'ready',
            profile,
            accessToken: session.access_token,
          });
      } catch (error) {
        if (
          !active ||
          epoch.current !== generation ||
          controller.signal.aborted
        )
          return;
        if (error instanceof ApiError && error.status === 401) {
          forgetStoredSession();
          setState({ status: 'anonymous', message: error.message });
        } else {
          setState({
            status: 'error',
            message:
              error instanceof ApiError
                ? error.message
                : 'Your profile could not be loaded. Please try again.',
          });
        }
      }
    }
    // Keep callbacks synchronous: awaiting Auth calls inside them can deadlock SDK locks.
    Promise.resolve().then(async () => {
      if (!active) return;
      try {
        const client = authClient();
        const subscription = client.auth.onAuthStateChange((event, session) => {
          if (event !== 'INITIAL_SESSION')
            queueMicrotask(() => void load(session));
        });
        unsubscribe = () => subscription.data.subscription.unsubscribe();
        const { data, error } = await client.auth.getSession();
        if (!active) return;
        if (error) {
          forgetStoredSession();
          setState({
            status: 'anonymous',
            message: 'Your session could not be restored. Please log in again.',
          });
        } else {
          await load(data.session);
        }
      } catch {
        if (active) setState({ status: 'anonymous' });
      }
    });
    return () => {
      active = false;
      mounted.current = false;
      request.current?.abort();
      clearTimeout(expiry);
      unsubscribe();
    };
  }, [retry]);

  async function signIn(
    mode: 'login' | 'signup',
    input: Credentials | Signup,
    signal: AbortSignal,
  ) {
    // Check public session configuration before a signup can create an account.
    const client = authClient();
    const result = await authenticate(mode, input, signal);
    if (signal.aborted || !mounted.current) return;
    let failed: boolean;
    try {
      const { error } = await client.auth.setSession({
        access_token: result.session.accessToken,
        refresh_token: result.session.refreshToken,
      });
      failed = Boolean(error);
    } catch {
      failed = true;
    }
    if (signal.aborted || !mounted.current) {
      await logout();
      return;
    }
    if (failed) {
      forgetStoredSession();
      throw new Error(
        'Your session could not be started. Please try logging in.',
      );
    }
  }

  async function logout() {
    if (signingOut.current) return;
    signingOut.current = true;
    ++epoch.current;
    request.current?.abort();
    setState({ status: 'signing-out' });
    let failed = false;
    try {
      const { error } = await authClient().auth.signOut({ scope: 'local' });
      failed = Boolean(error);
    } catch {
      failed = true;
    } finally {
      forgetStoredSession();
      signingOut.current = false;
      if (mounted.current)
        setState({
          status: 'anonymous',
          message: failed
            ? 'Signed out on this browser. The service could not confirm remote sign-out.'
            : undefined,
        });
    }
  }

  return {
    state,
    signIn,
    logout,
    expireSession,
    retry: () => setRetry((value) => value + 1),
  };
}
