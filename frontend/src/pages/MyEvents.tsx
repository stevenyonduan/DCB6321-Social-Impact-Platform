import { useEffect, useRef, useState } from 'react';
import { CalendarDays, ChevronRight, Clock, List, MapPin } from 'lucide-react';
import { ApiError, type Profile } from '../api/auth';
import { canEditOrganization } from '../api/organizations';
import { loadMyRegistrations, type MyRegistration } from '../api/registrations';
import { EventsCalendar } from '../components/EventsCalendar';
import { RegisteredEventDialog } from '../components/RegisteredEventDialog';

const weekday = new Intl.DateTimeFormat(undefined, { weekday: 'short' });
const monthName = new Intl.DateTimeFormat(undefined, { month: 'short' });
const clock = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' });

function EventList({
  events,
  now,
  onOpen,
}: {
  events: MyRegistration[];
  now: number;
  onOpen: (event: MyRegistration, trigger: HTMLButtonElement) => void;
}) {
  return (
    <ul className="my-events-list">
      {events.map((event) => {
        const start = new Date(event.eventStart);
        const end = new Date(event.eventEnd);
        const inProgress = start.getTime() <= now && end.getTime() > now;
        return (
          <li key={event.id}>
            <button
              type="button"
              className="profile-card my-event-card"
              onClick={(click) => onOpen(event, click.currentTarget)}
            >
              <time
                className="my-event-day"
                dateTime={event.eventStart}
                aria-label={start.toLocaleDateString(undefined, {
                  dateStyle: 'full',
                })}
              >
                {monthName.format(start)}
                <strong>{start.getDate()}</strong>
              </time>
              <span className="my-event-body">
                <span className="my-event-title">{event.title}</span>
                <span className="my-event-meta">
                  <span>
                    <Clock aria-hidden="true" />
                    {weekday.format(start)}, {clock.format(start)}
                  </span>
                  <span>
                    <MapPin aria-hidden="true" />
                    {event.location}
                  </span>
                  {inProgress && (
                    <span className="my-event-in-progress">In progress</span>
                  )}
                </span>
              </span>
              <ChevronRight aria-hidden="true" />
            </button>
          </li>
        );
      })}
    </ul>
  );
}

export function MyEvents({
  profile,
  accessToken,
  onUnauthorized,
}: {
  profile: Profile;
  accessToken: string;
  onUnauthorized: () => void;
}) {
  const [view, setView] = useState<'list' | 'calendar'>('list');
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{
    attempt: number;
    events?: MyRegistration[];
  }>();
  const [selected, setSelected] = useState<MyRegistration>();
  const [notice, setNotice] = useState('');
  const [now, setNow] = useState(Date.now);
  const trigger = useRef<HTMLButtonElement>(null);
  const listView = useRef<HTMLInputElement>(null);
  const calendarView = useRef<HTMLInputElement>(null);
  const current = result?.attempt === attempt ? result : undefined;
  const events = current?.events;

  useEffect(() => {
    const controller = new AbortController();
    void loadMyRegistrations(accessToken, controller.signal).then(
      (items) => {
        if (controller.signal.aborted) return;
        setNow(Date.now());
        setResult({
          attempt,
          events: items.sort(
            (left, right) =>
              Date.parse(left.eventStart) - Date.parse(right.eventStart) ||
              left.eventId.localeCompare(right.eventId),
          ),
        });
      },
      (failure: unknown) => {
        if (controller.signal.aborted) return;
        if (failure instanceof ApiError && failure.status === 401)
          onUnauthorized();
        else setResult({ attempt });
      },
    );
    return () => controller.abort();
  }, [accessToken, attempt, onUnauthorized]);

  useEffect(() => {
    const midnight = new Date(now);
    midnight.setHours(24, 0, 0, 0);
    let next = midnight.getTime();
    for (const event of events ?? []) {
      for (const boundary of [
        Date.parse(event.eventStart),
        Date.parse(event.eventEnd),
      ]) {
        if (boundary > now && boundary < next) next = boundary;
      }
    }
    const timer = setTimeout(
      () => setNow(Date.now()),
      Math.max(1, next - Date.now()),
    );
    return () => clearTimeout(timer);
  }, [events, now]);

  function openEvent(event: MyRegistration, button: HTMLButtonElement) {
    trigger.current = button;
    setNow(Date.now());
    setNotice('');
    setSelected(event);
  }

  function closeEvent() {
    setSelected(undefined);
    requestAnimationFrame(() => {
      const control = view === 'list' ? listView.current : calendarView.current;
      (trigger.current?.isConnected ? trigger.current : control)?.focus();
    });
  }

  function cancelEvent(eventId: string) {
    setResult((value) =>
      value?.events
        ? {
            ...value,
            events: value.events.filter((event) => event.eventId !== eventId),
          }
        : value,
    );
    setNotice('Registration canceled.');
    closeEvent();
  }

  const upcoming = events?.filter((event) => Date.parse(event.eventEnd) > now);
  const past = events
    ?.filter((event) => Date.parse(event.eventEnd) <= now)
    .reverse();

  return (
    <>
      <div className="home-welcome">
        <div className="content-width welcome-inner my-events-heading">
          <h1>My Events</h1>
          <div
            className="account-switch registration-switch events-view-switch"
            role="group"
            aria-label="Events view"
          >
            <label>
              <input
                ref={listView}
                type="radio"
                name="events-view"
                checked={view === 'list'}
                onChange={() => setView('list')}
              />
              <List aria-hidden="true" /> List
            </label>
            <label>
              <input
                ref={calendarView}
                type="radio"
                name="events-view"
                checked={view === 'calendar'}
                onChange={() => setView('calendar')}
              />
              <CalendarDays aria-hidden="true" /> Calendar
            </label>
          </div>
        </div>
      </div>
      <div className="content-width my-events-content">
        {notice && (
          <p className="events-notice" role="status">
            {notice}
          </p>
        )}
        {!current && <p role="status">Loading your events…</p>}
        {current && !events && (
          <div className="profile-card events-load-error">
            <p role="alert">Couldn’t load your events.</p>
            <button
              className="button dialog-cancel"
              type="button"
              onClick={() => setAttempt((value) => value + 1)}
            >
              Try again
            </button>
          </div>
        )}
        {events && (
          <>
            <div hidden={view !== 'list'}>
              <section aria-labelledby="my-upcoming-events">
                <h2 id="my-upcoming-events">Upcoming</h2>
                {upcoming?.length ? (
                  <EventList events={upcoming} now={now} onOpen={openEvent} />
                ) : (
                  <p className="profile-empty">No upcoming events yet.</p>
                )}
              </section>
              {!!past?.length && (
                <details className="past-events">
                  <summary>Past events</summary>
                  <EventList events={past} now={now} onOpen={openEvent} />
                </details>
              )}
            </div>
            <div hidden={view !== 'calendar'}>
              <EventsCalendar events={events} now={now} onOpen={openEvent} />
            </div>
          </>
        )}
      </div>
      {selected && (
        <RegisteredEventDialog
          event={selected}
          now={now}
          canViewAttendees={canEditOrganization(
            profile,
            selected.organizationId,
          )}
          accessToken={accessToken}
          onUnauthorized={onUnauthorized}
          onClose={closeEvent}
          onCanceled={() => cancelEvent(selected.eventId)}
        />
      )}
    </>
  );
}
