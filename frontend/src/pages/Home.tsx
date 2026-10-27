import { useState } from 'react';
import {
  MapPin,
  Clock,
  Sprout,
  PackageOpen,
  BookOpen,
  Heart,
  MessageCircle,
  Bookmark,
  ArrowUpRight,
} from 'lucide-react';
import { posts, opportunities } from '../demo/content';
import { PreviewDialog } from '../components/PreviewDialog';

type Preview = {
  title: string;
  description: string;
  schedule?: string;
  location?: string;
};

function Post({
  post,
  onPreview,
}: {
  post: (typeof posts)[number];
  onPreview: (preview: Preview) => void;
}) {
  const [liked, setLiked] = useState(false);
  const [saved, setSaved] = useState(false);
  return (
    <article className="post-card">
      <div className="post-author">
        <span
          className={`avatar ${post.artwork ? 'garden-avatar' : 'person-avatar'}`}
        >
          {post.artwork ? <Sprout aria-hidden="true" /> : post.avatar}
        </span>
        <div>
          <h3>{post.author}</h3>
          <p>{post.byline}</p>
        </div>
      </div>
      <p className="post-text">{post.text}</p>
      {post.artwork && (
        <div className="post-art">
          <Sprout aria-hidden="true" />
          <div>
            <strong>Growing something good.</strong>
            <span>Together, one Saturday at a time.</span>
          </div>
        </div>
      )}
      <p className="post-stats" aria-live="polite">
        {post.likes + (liked ? 1 : 0)} likes · {post.comments} comments
      </p>
      <div className="post-actions">
        <button aria-pressed={liked} onClick={() => setLiked(!liked)}>
          <Heart aria-hidden="true" />
          <span>{liked ? 'Liked' : 'Like'}</span>
        </button>
        <button
          onClick={() =>
            onPreview({
              title: 'Keep the conversation going.',
              description:
                'Commenting is a preview. Live comments will be connected in a future phase.',
            })
          }
        >
          <MessageCircle aria-hidden="true" />
          Comment
        </button>
        <button aria-pressed={saved} onClick={() => setSaved(!saved)}>
          <Bookmark aria-hidden="true" />
          <span>{saved ? 'Saved' : 'Save'}</span>
        </button>
      </div>
    </article>
  );
}

export function Home({ name }: { name: string }) {
  const [preview, setPreview] = useState<Preview | null>(null);
  const initials = name.slice(0, 2).toUpperCase();
  return (
    <>
      <section className="home-welcome">
        <div className="content-width welcome-inner">
          <div>
            <span className="eyebrow">YOUR COMMUNITY STARTS HERE</span>
            <h1>Hi, {name}. Let’s do some good.</h1>
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
            <div className="composer">
              <span className="avatar">{initials}</span>
              <button
                onClick={() =>
                  setPreview({
                    title: 'Share a little good.',
                    description:
                      'Posting is a preview. Community posts will be connected in a future phase.',
                  })
                }
              >
                Share a little good with your community…
              </button>
            </div>
            {posts.map((post) => (
              <Post key={post.id} post={post} onPreview={setPreview} />
            ))}
          </section>
          <section
            className="event-column"
            aria-labelledby="opportunities-title"
          >
            <div className="column-heading">
              <h2 id="opportunities-title">Opportunities near you</h2>
              <MapPin aria-hidden="true" />
            </div>
            <p className="event-intro">Find your next way to give back.</p>
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
