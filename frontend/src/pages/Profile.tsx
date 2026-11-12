import { useState } from 'react';
import type { Profile } from '../api/auth';

const memberSince = new Intl.DateTimeFormat('en-US', {
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
});

function avatarSource(value: string | null) {
  if (!value?.trim()) return undefined;
  try {
    const url = new URL(value, window.location.origin);
    if (url.protocol === 'https:' || url.protocol === 'http:') return url.href;
  } catch {
    return undefined;
  }
}

function ProfileAvatar({ src, initials }: { src?: string; initials: string }) {
  const [failed, setFailed] = useState(false);

  return (
    <div className="avatar profile-avatar" aria-hidden="true">
      {src && !failed ? (
        <img
          src={src}
          alt=""
          referrerPolicy="no-referrer"
          onError={() => setFailed(true)}
        />
      ) : (
        initials
      )}
    </div>
  );
}

export function ProfilePage({ profile }: { profile: Profile }) {
  const names = [profile.firstName.trim(), profile.lastName.trim()];
  const initials = names
    .map((name) => Array.from(name)[0])
    .join('')
    .toUpperCase();
  const src = avatarSource(profile.avatarUrl);

  return (
    <div className="profile-page">
      <div className="home-welcome">
        <div className="content-width profile-intro">
          <ProfileAvatar key={src} src={src} initials={initials} />
          <h1>{names.join(' ')}</h1>
        </div>
      </div>
      <div className="content-width profile-details">
        <section className="profile-card" aria-labelledby="profile-bio">
          <h2 id="profile-bio">Bio</h2>
          {profile.bio?.trim() ? (
            <p className="profile-bio">{profile.bio}</p>
          ) : (
            <p className="profile-empty">No bio yet.</p>
          )}
        </section>
        <section className="profile-card" aria-labelledby="profile-account">
          <h2 id="profile-account">Account details</h2>
          <dl>
            <dt>Email</dt>
            <dd>{profile.email}</dd>
            <dt>Member since</dt>
            <dd>
              <time dateTime={profile.createdAt}>
                {memberSince.format(new Date(profile.createdAt))}
              </time>
            </dd>
          </dl>
        </section>
      </div>
    </div>
  );
}
