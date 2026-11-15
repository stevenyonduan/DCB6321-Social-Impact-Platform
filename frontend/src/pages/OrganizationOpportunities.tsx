import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Plus } from 'lucide-react';
import { ApiError } from '../api/auth';
import {
  listOpportunities,
  publishOpportunity,
  type RegistrationType,
} from '../api/opportunities';

const eventTime = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

function webUrl(value: string | null) {
  if (!value || !/^https?:\/\//i.test(value)) return null;
  try {
    const url = new URL(value);
    return url.hostname && !url.username && !url.password && url.port !== '0'
      ? url
      : null;
  } catch {
    return null;
  }
}

function localDate(value: string) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return null;
  const localTime = new Date(
    date.getTime() - date.getTimezoneOffset() * 60_000,
  );
  return localTime.toISOString().slice(0, 16) === value ? date : null;
}

type SectionProps = {
  id: string;
  accessToken: string;
  canPublish: boolean;
  onUnauthorized: () => void;
};

export function OrganizationOpportunities({
  id,
  accessToken,
  canPublish,
  onUnauthorized,
}: SectionProps) {
  const [query, setQuery] = useState({ page: 0 });
  const [result, setResult] = useState<{
    query: typeof query;
    data?: Awaited<ReturnType<typeof listOpportunities>>;
  }>();
  const [formOpen, setFormOpen] = useState(false);
  const [published, setPublished] = useState(false);
  const [forbidden, setForbidden] = useState(false);
  const openButton = useRef<HTMLButtonElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    void listOpportunities(id, query.page, accessToken, controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setResult({ query, data });
      },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) onUnauthorized();
        else setResult({ query });
      },
    );
    return () => controller.abort();
  }, [id, query, accessToken, onUnauthorized]);

  const current = result?.query === query ? result : undefined;
  const data = current?.data;

  function closeForm() {
    setFormOpen(false);
    requestAnimationFrame(() =>
      (openButton.current ?? heading.current)?.focus(),
    );
  }

  return (
    <section
      className="profile-card organization-wide opportunity-section"
      aria-labelledby="upcoming-opportunities"
    >
      <div className="opportunity-heading">
        <h2 id="upcoming-opportunities" ref={heading} tabIndex={-1}>
          Upcoming opportunities
        </h2>
        {canPublish && !forbidden && (
          <button
            ref={openButton}
            className="button primary"
            onClick={() => {
              setPublished(false);
              setFormOpen(true);
            }}
          >
            <Plus aria-hidden="true" /> Publish opportunity
          </button>
        )}
      </div>
      {published && <p role="status">Opportunity published.</p>}
      {forbidden && (
        <p className="form-error" role="alert">
          You don’t have permission to publish for this organization.
        </p>
      )}
      {!current && <p role="status">Loading opportunities…</p>}
      {current && !data && (
        <>
          <p role="alert">Couldn’t load opportunities.</p>
          <button
            className="button dialog-cancel"
            onClick={() => setQuery({ page: query.page })}
          >
            Try again
          </button>
        </>
      )}
      {data && data.items.length === 0 && (
        <p className="profile-empty">
          {data.page === 0
            ? 'No upcoming opportunities yet.'
            : 'No opportunities on this page.'}
        </p>
      )}
      {data && data.items.length > 0 && (
        <ul className="opportunity-list">
          {data.items.map((item) => {
            const image = webUrl(item.imageUrl);
            const registration = webUrl(item.registrationUrl);
            return (
              <li key={item.id}>
                {image && (
                  <img
                    src={image.href}
                    alt=""
                    loading="lazy"
                    referrerPolicy="no-referrer"
                  />
                )}
                <div>
                  <h3>{item.title}</h3>
                  <p className="profile-empty">
                    <time dateTime={item.eventStart}>
                      {eventTime.format(new Date(item.eventStart))}
                    </time>
                    {' – '}
                    <time dateTime={item.eventEnd}>
                      {eventTime.format(new Date(item.eventEnd))}
                    </time>
                  </p>
                  <p>{item.location}</p>
                  <p className="profile-bio">{item.description}</p>
                  {registration && (
                    <a
                      className="organization-website"
                      href={registration.href}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      Register on {registration.hostname}
                    </a>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
      {data && (data.page > 0 || (data.hasNext && data.page < 10000)) && (
        <nav
          className="organization-form-actions"
          aria-label="Opportunity pages"
        >
          <button
            className="button dialog-cancel"
            disabled={data.page === 0}
            onClick={() => setQuery({ page: data.page - 1 })}
          >
            Previous
          </button>
          <span>Page {data.page + 1}</span>
          <button
            className="button dialog-cancel"
            disabled={!data.hasNext || data.page === 10000}
            onClick={() => setQuery({ page: data.page + 1 })}
          >
            Next
          </button>
        </nav>
      )}
      {formOpen && canPublish && (
        <PublishDialog
          id={id}
          accessToken={accessToken}
          onUnauthorized={onUnauthorized}
          onClose={closeForm}
          onForbidden={() => {
            setForbidden(true);
            closeForm();
          }}
          onRefresh={() => {
            setQuery({ page: 0 });
            closeForm();
          }}
          onPublished={() => {
            setPublished(true);
            setQuery({ page: 0 });
            closeForm();
          }}
        />
      )}
    </section>
  );
}

function PublishDialog({
  id,
  accessToken,
  onUnauthorized,
  onClose,
  onForbidden,
  onRefresh,
  onPublished,
}: Omit<SectionProps, 'canPublish'> & {
  onClose: () => void;
  onForbidden: () => void;
  onRefresh: () => void;
  onPublished: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const pending = useRef<AbortController | null>(null);
  const [type, setType] = useState<RegistrationType>('INTERNAL');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [checkList, setCheckList] = useState(false);

  useEffect(() => {
    dialog.current?.showModal();
    return () => pending.current?.abort();
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current || checkList) return;
    const fields = new FormData(event.currentTarget);
    const title = String(fields.get('title')).trim();
    const description = String(fields.get('description')).trim();
    const location = String(fields.get('location')).trim();
    const start = localDate(String(fields.get('eventStart')));
    const end = localDate(String(fields.get('eventEnd')));
    const registrationUrl =
      type === 'EXTERNAL' ? String(fields.get('registrationUrl')).trim() : null;
    if (
      !title ||
      !description ||
      !location ||
      [title, location].some((value) => Array.from(value).length > 255)
    ) {
      setError(
        'Enter a title, description and location. Title and location can be up to 255 characters.',
      );
      return;
    }
    if (!start || start.getTime() <= Date.now() || !end || end <= start) {
      setError(
        'Choose a future start time and an end time after it in your local time zone.',
      );
      return;
    }
    if (type === 'EXTERNAL' && !webUrl(registrationUrl)) {
      setError('Enter an HTTP or HTTPS registration URL.');
      return;
    }

    const controller = new AbortController();
    pending.current = controller;
    setBusy(true);
    setError('');
    try {
      await publishOpportunity(
        id,
        {
          title,
          description,
          location,
          eventStart: start.toISOString(),
          eventEnd: end.toISOString(),
          imageUrl: null,
          registrationType: type,
          registrationUrl,
        },
        accessToken,
        controller.signal,
      );
      if (!controller.signal.aborted) onPublished();
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401)
        onUnauthorized();
      else if (failure instanceof ApiError && failure.status === 403)
        onForbidden();
      else if (
        failure instanceof ApiError &&
        failure.code === 'EVENT_START_NOT_FUTURE'
      ) {
        setError(
          'The start time must be in the future. Choose a later start time.',
        );
      } else if (
        failure instanceof ApiError &&
        failure.code === 'INVALID_REGISTRATION'
      ) {
        setError('Check the registration type and registration URL.');
      } else if (failure instanceof ApiError && failure.status === 400) {
        setError('Check the opportunity details and try again.');
      } else if (
        failure instanceof ApiError &&
        (failure.status === 404 || failure.status === 429)
      ) {
        setError(
          failure.status === 404
            ? 'This organization could not be found.'
            : failure.message,
        );
      } else {
        setCheckList(true);
        setError(
          'Publishing could not be confirmed. Check the list before trying again.',
        );
      }
    } finally {
      pending.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  return (
    <dialog
      ref={dialog}
      className="preview-dialog publish-opportunity-dialog"
      aria-labelledby="publish-opportunity-title"
      onCancel={(event) => {
        event.preventDefault();
        if (!pending.current) onClose();
      }}
    >
      <h2 id="publish-opportunity-title">Publish opportunity</h2>
      <form onSubmit={(event) => void submit(event)}>
        <fieldset disabled={busy || checkList}>
          <label>
            Title
            <input name="title" required />
          </label>
          <label>
            Description
            <textarea name="description" rows={3} required />
          </label>
          <label>
            Location
            <input name="location" required />
          </label>
          <div className="opportunity-dates">
            <label>
              Start date and time
              <input
                name="eventStart"
                type="datetime-local"
                aria-describedby="opportunity-time-zone"
                required
              />
            </label>
            <label>
              End date and time
              <input
                name="eventEnd"
                type="datetime-local"
                aria-describedby="opportunity-time-zone"
                required
              />
            </label>
          </div>
          <p className="dialog-note" id="opportunity-time-zone">
            Times are in your local time zone (
            {eventTime.resolvedOptions().timeZone}).
          </p>
          <fieldset>
            <legend>Registration type</legend>
            <div className="account-switch registration-switch">
              <label>
                <input
                  type="radio"
                  name="registrationType"
                  checked={type === 'INTERNAL'}
                  onChange={() => setType('INTERNAL')}
                />
                Internal
              </label>
              <label>
                <input
                  type="radio"
                  name="registrationType"
                  checked={type === 'EXTERNAL'}
                  onChange={() => setType('EXTERNAL')}
                />
                External
              </label>
            </div>
          </fieldset>
          {type === 'EXTERNAL' && (
            <label>
              Registration URL
              <input name="registrationUrl" type="url" required />
            </label>
          )}
        </fieldset>
        {error && (
          <p role="alert" className="form-error">
            {error}
          </p>
        )}
        <div className="organization-form-actions">
          <button
            type="button"
            className="button dialog-cancel"
            disabled={busy}
            onClick={onClose}
          >
            Cancel
          </button>
          {checkList ? (
            <button
              type="button"
              className="button primary"
              onClick={onRefresh}
            >
              Refresh opportunities
            </button>
          ) : (
            <button type="submit" className="button primary" disabled={busy}>
              {busy ? 'Publishing…' : 'Publish opportunity'}
            </button>
          )}
        </div>
      </form>
    </dialog>
  );
}
