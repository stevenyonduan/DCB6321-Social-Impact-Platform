import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/auth';
import { registrationRequest } from '../api/registrations';

type RegistrationState =
  | { status: 'loading' }
  | { status: 'ready'; registered: boolean; error?: string }
  | { status: 'unavailable'; missing: boolean };

export function InternalRegistration({
  eventId,
  eventStart,
  accessToken,
  onUnauthorized,
}: {
  eventId: string;
  eventStart: string;
  accessToken: string;
  onUnauthorized: () => void;
}) {
  const start = Date.parse(eventStart);
  const [started, setStarted] = useState(() => Date.now() >= start);
  const [state, setState] = useState<RegistrationState>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const [saving, setSaving] = useState(false);
  const mutation = useRef<AbortController | null>(null);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    function schedule() {
      timer = setTimeout(
        () => {
          if (Date.now() >= start) setStarted(true);
          else schedule();
        },
        Math.max(0, Math.min(start - Date.now(), 2_147_483_647)),
      );
    }
    schedule();
    return () => clearTimeout(timer);
  }, [start]);

  useEffect(() => {
    const controller = new AbortController();
    void registrationRequest(
      eventId,
      'GET',
      accessToken,
      controller.signal,
    ).then(
      (registered) => {
        if (!controller.signal.aborted)
          setState({ status: 'ready', registered });
      },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) onUnauthorized();
        else
          setState({
            status: 'unavailable',
            missing:
              error instanceof ApiError && error.code === 'EVENT_NOT_FOUND',
          });
      },
    );
    return () => {
      controller.abort();
      mutation.current?.abort();
    };
  }, [accessToken, attempt, eventId, onUnauthorized]);

  async function changeRegistration() {
    if (mutation.current || state.status !== 'ready' || started) return;
    if (Date.now() >= start) {
      setStarted(true);
      return;
    }
    const desired = !state.registered;
    const controller = new AbortController();
    const { signal } = controller;
    mutation.current = controller;
    setSaving(true);
    setState({ status: 'ready', registered: state.registered });
    try {
      const registered = await registrationRequest(
        eventId,
        desired ? 'POST' : 'DELETE',
        accessToken,
        signal,
      );
      if (!signal.aborted) setState({ status: 'ready', registered });
    } catch (error) {
      if (signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) {
        onUnauthorized();
        return;
      }
      const closed =
        error instanceof ApiError && error.code === 'EVENT_ALREADY_STARTED';
      if (closed) setStarted(true);
      try {
        const registered = await registrationRequest(
          eventId,
          'GET',
          accessToken,
          signal,
        );
        if (!signal.aborted)
          setState({
            status: 'ready',
            registered,
            error:
              closed || registered === desired
                ? undefined
                : 'The registration didn’t change. Please try again.',
          });
      } catch (refreshError) {
        if (signal.aborted) return;
        if (refreshError instanceof ApiError && refreshError.status === 401)
          onUnauthorized();
        else
          setState({
            status: 'unavailable',
            missing:
              refreshError instanceof ApiError &&
              refreshError.code === 'EVENT_NOT_FOUND',
          });
      }
    } finally {
      mutation.current = null;
      if (!signal.aborted) setSaving(false);
    }
  }

  return (
    <div className="internal-registration">
      {state.status === 'loading' && (
        <p role="status">Checking registration…</p>
      )}
      {state.status === 'unavailable' && (
        <>
          <p role="alert">
            {state.missing
              ? 'This opportunity is no longer available.'
              : 'Couldn’t check registration.'}
          </p>
          {!state.missing && (
            <button
              className="button dialog-cancel"
              type="button"
              onClick={() => {
                setState({ status: 'loading' });
                setAttempt((value) => value + 1);
              }}
            >
              Retry registration status
            </button>
          )}
        </>
      )}
      {state.status === 'ready' && (
        <>
          <p role="status">
            {state.registered && 'Registered'}
            {state.registered && started && ' · '}
            {started && 'Registration closed'}
          </p>
          {state.error && (
            <p className="form-error" role="alert">
              {state.error}
            </p>
          )}
          {!started && (
            <button
              className={
                state.registered ? 'button dialog-cancel' : 'button primary'
              }
              type="button"
              disabled={saving}
              onClick={() => void changeRegistration()}
            >
              {saving
                ? 'Updating…'
                : state.registered
                  ? 'Cancel registration'
                  : 'Register'}
            </button>
          )}
        </>
      )}
    </div>
  );
}
