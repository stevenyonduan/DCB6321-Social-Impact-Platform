import { useState } from 'react';
import {
  MapPin,
  Clock,
  Sprout,
  PackageOpen,
  BookOpen,
  ArrowUpRight,
} from 'lucide-react';
import { opportunities } from '../demo/content';
import type { CommunityAccount } from '../components/CommunityPost';
import { CommunityPosts } from '../components/CommunityPosts';
import { PreviewDialog } from '../components/PreviewDialog';

type Preview = {
  title: string;
  description: string;
  schedule?: string;
  location?: string;
};

export function Home({ account }: { account: CommunityAccount }) {
  const [preview, setPreview] = useState<Preview | null>(null);
  return (
    <>
      <section className="home-welcome">
        <div className="content-width welcome-inner">
          <div>
            <span className="eyebrow">YOUR COMMUNITY STARTS HERE</span>
            <h1>Hi, {account.profile.firstName}. Let’s do some good.</h1>
            <p>A small step today can make a difference close to home.</p>
          </div>
          <span className="location">
            <MapPin aria-hidden="true" />
            Atlanta, GA
          </span>
        </div>
      </section>
      <div className="content-width home-body">
        <div className="feed-layout">
          <section className="feed-column" aria-labelledby="feed-title">
            <div className="column-heading">
              <h2 id="feed-title">Your community</h2>
              <span>Recent posts</span>
            </div>
            <CommunityPosts account={account} />
          </section>
          <section
            className="event-column"
            aria-labelledby="opportunities-title"
          >
            <div className="column-heading">
              <h2 id="opportunities-title">Opportunities near you</h2>
              <MapPin aria-hidden="true" />
            </div>
            <p className="event-intro">Sample opportunities</p>
            <div className="opportunity-list">
              {opportunities.map((event) => {
                const Icon =
                  event.theme === 'garden'
                    ? Sprout
                    : event.theme === 'food'
                      ? PackageOpen
                      : BookOpen;
                return (
                  <article className="opportunity-card" key={event.id}>
                    <div className={`opportunity-art ${event.theme}`}>
                      <Icon aria-hidden="true" />
                      <span>{event.artwork}</span>
                    </div>
                    <div className="opportunity-body">
                      <span className="event-category">{event.category}</span>
                      <h3>{event.title}</h3>
                      <p className="event-meta">
                        <MapPin aria-hidden="true" />
                        {event.location}
                      </p>
                      <p className="event-meta">
                        <Clock aria-hidden="true" />
                        {event.schedule}
                      </p>
                      <button
                        onClick={() =>
                          setPreview({
                            title: event.title,
                            description: event.description,
                            schedule: event.schedule,
                            location: event.location,
                          })
                        }
                      >
                        Take a look <ArrowUpRight aria-hidden="true" />
                      </button>
                    </div>
                  </article>
                );
              })}
            </div>
          </section>
        </div>
      </div>
      {preview && (
        <PreviewDialog title={preview.title} onClose={() => setPreview(null)}>
          <p>{preview.description}</p>
          {preview.location && (
            <p className="event-meta">
              <MapPin aria-hidden="true" />
              {preview.location}
            </p>
          )}
          {preview.schedule && (
            <p className="event-meta">
              <Clock aria-hidden="true" />
              {preview.schedule}
            </p>
          )}
          {preview.schedule && (
            <p className="dialog-note">
              Sample opportunity. Event registration is not available in this
              demo.
            </p>
          )}
        </PreviewDialog>
      )}
    </>
  );
}
