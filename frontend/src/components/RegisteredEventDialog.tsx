import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/auth';
import { loadUpcomingOpportunityDescription } from '../api/opportunities';
import { registrationRequest, type MyRegistration } from '../api/registrations';
import { EventDetailsDialog } from './EventDetailsDialog';

export function RegisteredEventDialog({
  event,
  now,
  accessToken,
  onUnauthorized,
  onClose,
  onCanceled,
}: {
  event: MyRegistration;
  now: number;
  accessToken: string;
  onUnauthorized: () => void;
  onClose: () => void;
  onCanceled: () => void;
}) {
  const keepButton = useRef<HTMLButtonElement>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const pending = useRef<AbortController | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [saving, setSaving] = useState(false);
  const [closed, setClosed] = useState(false);
  const [needsStatus, setNeedsStatus] = useState(false);
  const [error, setError] = useState('');
  const startAt = Date.parse(event.eventStart);
  const [lookupDescription] = useState(() => startAt > now);
  const [description, setDescription] = useState<string | null>();
  const started = closed || startAt <= now;
  const ended = Date.parse(event.eventEnd) <= now;

  useEffect(() => () => pending.current?.abort(), []);

  useEffect(() => {
    if (confirm) keepButton.current?.focus();
  }, [confirm]);

  useEffect(() => {
    if (!lookupDescription) return;
    const controller = new AbortController();
    void loadUpcomingOpportunityDescription(
      event.organizationId,
      event.eventId,
      accessToken,
      controller.signal,
    ).then(
      (text) => {
        if (!controller.signal.aborted) setDescription(text);
      },
      (failure: unknown) => {
        if (controller.signal.aborted) return;
        if (failure instanceof ApiError && failure.status === 401)
          onUnauthorized();
        else setDescription(null);
      },
    );
    return () => controller.abort();
  }, [
    accessToken,
    event.eventId,
    event.organizationId,
    lookupDescription,
    onUnauthorized,
  ]);

  async function updateRegistration(checkOnly: boolean) {
    if (pending.current) return;
    if (!checkOnly && Date.now() >= startAt) {
      setClosed(true);
      return;
    }
    const controller = new AbortController();
    const { signal } = controller;
    pending.current = controller;
    setSaving(true);
    setError('');
    try {
      if (!checkOnly) {
        try {
          await registrationRequest(
            event.eventId,
            'DELETE',
            accessToken,
            signal,
          );
          if (!signal.aborted) onCanceled();
          return;
        } catch (failure) {
          if (signal.aborted) return;
          if (
            failure instanceof ApiError &&
            failure.code === 'REGISTRATION_NOT_FOUND'
          ) {
            onCanceled();
            return;
          }
          if (
            failure instanceof ApiError &&
            failure.code === 'EVENT_ALREADY_STARTED'
          ) {
            setClosed(true);
            return;
          }
          if (failure instanceof ApiError && failure.status === 401)
            throw failure;
        }
      }
      const registered = await registrationRequest(
        event.eventId,
        'GET',
        accessToken,
        signal,
      );
      if (signal.aborted) return;
      if (!registered) {
        onCanceled();
      } else {
        setNeedsStatus(false);
        setConfirm(false);
        setError('You’re still registered. You can try canceling again.');
      }
    } catch (failure) {
      if (signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401)
        onUnauthorized();
      else if (
        failure instanceof ApiError &&
        failure.code === 'EVENT_NOT_FOUND'
      )
        onCanceled();
      else {
        setNeedsStatus(true);
        setError('Couldn’t confirm your registration status.');
      }
    } finally {
      pending.current = null;
      if (!signal.aborted) setSaving(false);
    }
  }

  return (
    <EventDetailsDialog
      event={event}
      description={description}
      showDescription={lookupDescription}
      status={ended ? 'Past event' : started ? 'In progress' : 'Registered'}
      busy={saving}
      onClose={() => {
        if (!pending.current) onClose();
      }}
    >
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {needsStatus ? (
        <button
          className="button dialog-cancel"
          type="button"
          disabled={saving}
          onClick={() => void updateRegistration(true)}
        >
          {saving ? 'Checking…' : 'Check registration status'}
        </button>
      ) : (
        !started && (
          <div className="registered-event-actions">
            {confirm ? (
              <>
                <p>Cancel your registration for this event?</p>
                <div className="organization-form-actions">
                  <button
                    className="button dialog-cancel"
                    ref={keepButton}
                    type="button"
                    disabled={saving}
                    onClick={() => {
                      setConfirm(false);
                      requestAnimationFrame(() =>
                        cancelButton.current?.focus(),
                      );
                    }}
                  >
                    Keep registration
                  </button>
                  <button
                    className="button primary"
                    type="button"
                    disabled={saving}
                    onClick={() => void updateRegistration(false)}
                  >
                    {saving ? 'Canceling…' : 'Confirm cancellation'}
                  </button>
                </div>
              </>
            ) : (
              <button
                className="button dialog-cancel"
                ref={cancelButton}
                type="button"
                onClick={() => setConfirm(true)}
              >
                Cancel registration
              </button>
            )}
          </div>
        )
      )}
    </EventDetailsDialog>
  );
}
