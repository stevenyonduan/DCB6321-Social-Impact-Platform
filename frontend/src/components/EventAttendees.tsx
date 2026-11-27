import { useEffect, useId, useRef, useState } from 'react';
import { Users } from 'lucide-react';
import { ApiError } from '../api/auth';
import { loadEventAttendees, type EventAttendee } from '../api/registrations';

export type AttendeeAccess = {
  eventId: string;
  accessToken: string;
  onUnauthorized: () => void;
};

type AttendeeProps = AttendeeAccess & { eventTitle: string };

const registrationTime = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

export function EventAttendees({
  eventTitle,
  eventId,
  accessToken,
  onUnauthorized,
  disabled,
}: AttendeeProps & { disabled?: boolean }) {
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);

  return (
    <>
      <button
        className="button dialog-cancel"
        type="button"
        aria-label={`View attendees for ${eventTitle}`}
        ref={trigger}
        disabled={disabled}
        onClick={() => setOpen(true)}
      >
        <Users aria-hidden="true" /> View attendees
      </button>
      {open && (
        <AttendeesDialog
          eventTitle={eventTitle}
          eventId={eventId}
          accessToken={accessToken}
          onUnauthorized={onUnauthorized}
          onClose={() => {
            setOpen(false);
            requestAnimationFrame(() => trigger.current?.focus());
          }}
        />
      )}
    </>
  );
}

type RosterResult = {
  query: { page: number };
  error?: 'unavailable' | 'forbidden' | 'missing';
  hasNext?: boolean;
};

function AttendeesDialog({
  eventTitle,
  eventId,
  accessToken,
  onUnauthorized,
  onClose,
}: AttendeeProps & { onClose: () => void }) {
  const [query, setQuery] = useState({ page: 0 });
  const [result, setResult] = useState<RosterResult>();
  const [rows, setRows] = useState<EventAttendee[]>([]);
  const dialog = useRef<HTMLDialogElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const eventIdLabel = useId();
  const current = result?.query === query ? result : undefined;

  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) element.showModal();
    closeButton.current?.focus();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    void loadEventAttendees(eventId, query.page, accessToken, signal).then(
      ({ items, hasNext }) => {
        if (signal.aborted) return;
        setRows((existing) => {
          const merged = new Map(
            (query.page === 0 ? [] : existing).map((row) => [row.id, row]),
          );
          for (const row of items) merged.set(row.id, row);
          return [...merged.values()];
        });
        setResult({ query, hasNext });
      },
      (failure: unknown) => {
        if (signal.aborted) return;
        const status = failure instanceof ApiError ? failure.status : undefined;
        if (status === 401) {
          onUnauthorized();
          return;
        }
        if (status === 403 || status === 404) setRows([]);
        setResult({
          query,
          error:
            status === 403
              ? 'forbidden'
              : status === 404
                ? 'missing'
                : 'unavailable',
        });
      },
    );
    return () => controller.abort();
  }, [eventId, query, accessToken, onUnauthorized]);

  return (
    <dialog
      ref={dialog}
      className="preview-dialog organization-form-dialog event-attendees-dialog"
      aria-labelledby={titleId}
      aria-describedby={eventIdLabel}
      onCancel={(event) => {
        event.preventDefault();
        event.stopPropagation();
        onClose();
      }}
    >
      <h2 id={titleId}>Attendees</h2>
      <p className="dialog-note" id={eventIdLabel}>
        {eventTitle}
      </p>
      {rows.length > 0 && (
        <ul className="event-attendee-list" aria-label="Registered attendees">
          {rows.map((row) => (
            <li key={row.id}>
              <p>
                {row.firstName} {row.lastName}
              </p>
              <p className="dialog-note">
                Registered{' '}
                <time dateTime={row.registeredAt}>
                  {registrationTime.format(new Date(row.registeredAt))}
                </time>
              </p>
            </li>
          ))}
        </ul>
      )}
      {!current && <p role="status">Loading attendees…</p>}
      {current?.error && (
        <p className="form-error" role="alert">
          {current.error === 'forbidden'
            ? 'You don’t have permission to view attendees for this event.'
            : current.error === 'missing'
              ? 'This event is no longer available.'
              : 'Couldn’t load attendees.'}
        </p>
      )}
      {current && !current.error && rows.length === 0 && (
        <p className="dialog-note">No registrations yet.</p>
      )}
      <div className="organization-form-actions">
        {current?.error === 'unavailable' && (
          <button
            className="button primary"
            type="button"
            onClick={() => setQuery({ page: query.page })}
          >
            Try again
          </button>
        )}
        {current?.hasNext && (
          <button
            className="button primary"
            type="button"
            onClick={() => setQuery({ page: query.page + 1 })}
          >
            Load more
          </button>
        )}
        <button
          className="button dialog-cancel"
          type="button"
          ref={closeButton}
          onClick={onClose}
        >
          Close
        </button>
      </div>
    </dialog>
  );
}
