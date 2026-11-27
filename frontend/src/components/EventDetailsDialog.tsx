import { useEffect, useId, useRef, type ReactNode } from 'react';
import { CalendarDays, Clock, MapPin, X } from 'lucide-react';
import type { Opportunity } from '../api/opportunities';
import { isOrganizationPage } from '../navigation';
import { EventAttendees, type AttendeeAccess } from './EventAttendees';
import { PageLink } from './PageLink';

const fullDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'full' });
const time = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' });
const dateAndTime = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

export function EventDetailsDialog({
  event,
  description,
  showDescription = true,
  status,
  busy = false,
  attendees,
  onClose,
  children,
}: {
  event: Pick<
    Opportunity,
    'title' | 'location' | 'eventStart' | 'eventEnd' | 'organizationId'
  >;
  description?: string | null;
  showDescription?: boolean;
  status?: string;
  busy?: boolean;
  attendees?: AttendeeAccess;
  onClose: () => void;
  children?: ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  const start = new Date(event.eventStart);
  const end = new Date(event.eventEnd);
  const startDay = fullDate.format(start);
  const organizationPage = `organizations/${event.organizationId}`;

  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) element.showModal();
  }, []);

  return (
    <dialog
      ref={dialog}
      className="preview-dialog registered-event-dialog"
      aria-labelledby={titleId}
      onCancel={(action) => {
        action.preventDefault();
        if (!busy) onClose();
      }}
      onClick={(click) => {
        if (click.target === click.currentTarget && !busy) onClose();
      }}
    >
      <div className="registered-event-heading">
        <div>
          {status && <p>{status}</p>}
          <h2 id={titleId}>{event.title}</h2>
        </div>
        <button
          className="dialog-close"
          type="button"
          aria-label="Close event details"
          disabled={busy}
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </button>
      </div>
      <ul className="registered-event-meta">
        <li>
          <CalendarDays aria-hidden="true" />
          {startDay}
        </li>
        <li>
          <Clock aria-hidden="true" />
          <span>
            <time dateTime={event.eventStart}>{time.format(start)}</time>
            {' – '}
            <time dateTime={event.eventEnd}>
              {fullDate.format(end) === startDay
                ? time.format(end)
                : dateAndTime.format(end)}
            </time>
          </span>
        </li>
        <li>
          <MapPin aria-hidden="true" />
          {event.location}
        </li>
      </ul>
      {showDescription && (
        <section aria-labelledby={descriptionId}>
          <h3 id={descriptionId}>Description</h3>
          {description === undefined ? (
            <p className="profile-empty" role="status">
              Loading description…
            </p>
          ) : description ? (
            <p className="profile-bio">{description}</p>
          ) : (
            <p className="profile-empty">Description unavailable.</p>
          )}
        </section>
      )}
      {isOrganizationPage(organizationPage) && (
        <PageLink className="text-link" page={organizationPage}>
          View organization
        </PageLink>
      )}
      {children}
      {attendees && (
        <div className="registered-event-actions">
          <EventAttendees
            {...attendees}
            eventTitle={event.title}
            disabled={busy}
          />
        </div>
      )}
    </dialog>
  );
}
