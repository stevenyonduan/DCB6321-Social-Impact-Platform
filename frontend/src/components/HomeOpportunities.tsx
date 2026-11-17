import { useEffect, useRef, useState } from 'react';
import { ArrowUpRight, Clock, MapPin } from 'lucide-react';
import { ApiError } from '../api/auth';
import {
  listOpportunities,
  opportunityWebUrl,
  type Opportunity,
} from '../api/opportunities';
import type { CommunityAccount } from './CommunityPost';
import { EventDetailsDialog } from './EventDetailsDialog';
import { InternalRegistration } from './InternalRegistration';

const dateAndTime = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

export function HomeOpportunities({ account }: { account: CommunityAccount }) {
  const organizationId = account.profile.organizationId;
  return (
    <section className="event-column" aria-labelledby="opportunities-title">
      <div className="column-heading">
        <h2 id="opportunities-title">Upcoming opportunities</h2>
        <MapPin aria-hidden="true" />
      </div>
      {organizationId ? (
        <OrganizationEvents
          key={organizationId}
          organizationId={organizationId}
          account={account}
        />
      ) : (
        <p className="profile-empty">No upcoming opportunities yet.</p>
      )}
    </section>
  );
}

function OrganizationEvents({
  organizationId,
  account,
}: {
  organizationId: string;
  account: CommunityAccount;
}) {
  const { accessToken, onUnauthorized } = account;
  const [query, setQuery] = useState({ page: 0 });
  const [result, setResult] = useState<{
    query: typeof query;
    data?: Awaited<ReturnType<typeof listOpportunities>>;
  }>();
  const [selected, setSelected] = useState<Opportunity>();
  const trigger = useRef<HTMLButtonElement>(null);
  const current = result?.query === query ? result : undefined;
  const data = current?.data;

  useEffect(() => {
    const controller = new AbortController();
    void listOpportunities(
      organizationId,
      query.page,
      accessToken,
      controller.signal,
    ).then(
      (opportunities) => {
        if (!controller.signal.aborted)
          setResult({ query, data: opportunities });
      },
      (failure: unknown) => {
        if (controller.signal.aborted) return;
        if (failure instanceof ApiError && failure.status === 401)
          onUnauthorized();
        else setResult({ query });
      },
    );
    return () => controller.abort();
  }, [accessToken, onUnauthorized, organizationId, query]);

  const registration = selected && opportunityWebUrl(selected.registrationUrl);

  return (
    <>
      {!current && <p role="status">Loading opportunities…</p>}
      {current && !data && (
        <div className="profile-card post-load-error">
          <p role="alert">Couldn’t load opportunities.</p>
          <button
            className="button dialog-cancel"
            type="button"
            onClick={() => setQuery({ page: query.page })}
          >
            Try again
          </button>
        </div>
      )}
      {data &&
        (data.items.length ? (
          <div className="opportunity-list">
            {data.items.map((event) => {
              const image = opportunityWebUrl(event.imageUrl);
              return (
                <article className="opportunity-card" key={event.id}>
                  {image && (
                    <img
                      className="home-opportunity-image"
                      src={image.href}
                      alt=""
                      loading="lazy"
                      referrerPolicy="no-referrer"
                      onError={(failure) => {
                        failure.currentTarget.hidden = true;
                      }}
                    />
                  )}
                  <div className="opportunity-body">
                    <h3>{event.title}</h3>
                    <p className="event-meta">
                      <MapPin aria-hidden="true" />
                      {event.location}
                    </p>
                    <p className="event-meta">
                      <Clock aria-hidden="true" />
                      <time dateTime={event.eventStart}>
                        {dateAndTime.format(new Date(event.eventStart))}
                      </time>
                    </p>
                    <button
                      type="button"
                      aria-label={`Take a look at ${event.title}`}
                      onClick={(click) => {
                        trigger.current = click.currentTarget;
                        setSelected(event);
                      }}
                    >
                      Take a look <ArrowUpRight aria-hidden="true" />
                    </button>
                  </div>
                </article>
              );
            })}
          </div>
        ) : (
          <p className="profile-empty">
            {data.page === 0
              ? 'No upcoming opportunities yet.'
              : 'No opportunities on this page.'}
          </p>
        ))}
      {data && (data.page > 0 || (data.hasNext && data.page < 10_000)) && (
        <nav className="home-opportunity-pages" aria-label="Opportunity pages">
          <button
            className="button dialog-cancel"
            type="button"
            disabled={data.page === 0}
            onClick={() => setQuery({ page: data.page - 1 })}
          >
            Previous
          </button>
          <button
            className="button dialog-cancel"
            type="button"
            disabled={!data.hasNext || data.page === 10_000}
            onClick={() => setQuery({ page: data.page + 1 })}
          >
            Next
          </button>
        </nav>
      )}
      {selected && (
        <EventDetailsDialog
          event={selected}
          description={selected.description}
          onClose={() => {
            setSelected(undefined);
            requestAnimationFrame(() => trigger.current?.focus());
          }}
        >
          {selected.registrationType === 'INTERNAL' ? (
            <InternalRegistration
              eventId={selected.id}
              eventStart={selected.eventStart}
              accessToken={accessToken}
              onUnauthorized={onUnauthorized}
            />
          ) : (
            <div className="registered-event-actions">
              {registration ? (
                <a
                  className="organization-website"
                  href={registration.href}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  Register on {registration.hostname}
                </a>
              ) : (
                <p className="profile-empty">Registration link unavailable.</p>
              )}
            </div>
          )}
        </EventDetailsDialog>
      )}
    </>
  );
}
