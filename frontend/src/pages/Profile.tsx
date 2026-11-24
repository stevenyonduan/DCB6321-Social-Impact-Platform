import {
  lazy,
  Suspense,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react';
import { Bookmark, ChevronRight, Pencil } from 'lucide-react';
import { ApiError, updateProfile, type Profile } from '../api/auth';
import { PageLink } from '../components/PageLink';
import { ProfileAvatar } from '../components/ProfileAvatar';
import { ProfileOrganization } from './ProfileOrganization';

const MyActivity = lazy(() =>
  import('../components/MyActivity').then(({ MyActivity }) => ({
    default: MyActivity,
  })),
);

const memberSince = new Intl.DateTimeFormat('en-US', {
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
});

export function ProfilePage({
  profile,
  accessToken,
  onUnauthorized,
  onRefresh,
  onProfileUpdated,
  onOrganizationCreated,
}: {
  profile: Profile;
  accessToken: string;
  onUnauthorized: () => void;
  onRefresh: () => void;
  onProfileUpdated: (updated: Profile) => void;
  onOrganizationCreated: (id: string) => void;
}) {
  const [draft, setDraft] = useState<{
    firstName: string;
    lastName: string;
    bio: string;
  }>();
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [saveError, setSaveError] = useState('');
  const pendingSave = useRef<AbortController | null>(null);
  const editButton = useRef<HTMLButtonElement>(null);
  const firstNameInput = useRef<HTMLInputElement>(null);
  const editing = draft !== undefined;

  useEffect(() => () => pendingSave.current?.abort(), []);
  useEffect(() => {
    if (editing) firstNameInput.current?.focus();
  }, [editing]);

  const names = [profile.firstName.trim(), profile.lastName.trim()];
  const initials = names
    .map((name) => Array.from(name)[0])
    .join('')
    .toUpperCase();
  function closeEditor() {
    setDraft(undefined);
    setSaveError('');
    requestAnimationFrame(() => editButton.current?.focus());
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!draft || pendingSave.current) return;
    const patch = {
      firstName:
        draft.firstName === profile.firstName ? undefined : draft.firstName,
      lastName:
        draft.lastName === profile.lastName ? undefined : draft.lastName,
      bio: draft.bio === (profile.bio ?? '') ? undefined : draft.bio || null,
    };
    if (
      [patch.firstName, patch.lastName].some(
        (name) =>
          name !== undefined && (!name.trim() || Array.from(name).length > 100),
      )
    ) {
      setSaveError('Enter a first and last name of 1 to 100 characters each.');
      return;
    }
    if (Object.values(patch).every((value) => value === undefined)) {
      closeEditor();
      return;
    }

    const controller = new AbortController();
    pendingSave.current = controller;
    setSaving(true);
    setSaveError('');
    try {
      const updated = await updateProfile(
        patch,
        accessToken,
        controller.signal,
      );
      if (controller.signal.aborted) return;
      if (updated.id !== profile.id)
        throw new ApiError(200, 'INVALID_RESPONSE');
      onProfileUpdated(updated);
      setSaved(true);
      closeEditor();
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) {
        onUnauthorized();
      } else if (error instanceof ApiError && error.status === 413) {
        setSaveError(
          'Your profile update is too large. Try shortening your bio.',
        );
      } else if (error instanceof ApiError && error.status === 400) {
        setSaveError('Check your profile details and try again.');
      } else {
        setSaveError(
          'The save could not be confirmed. Try again or reload to check your profile.',
        );
      }
    } finally {
      pendingSave.current = null;
      if (!controller.signal.aborted) setSaving(false);
    }
  }

  return (
    <div className="profile-page">
      <div className="home-welcome">
        <div className="content-width profile-intro personal-profile-intro">
          <div className="profile-identity">
            <ProfileAvatar
              avatarUrl={profile.avatarUrl}
              className="profile-avatar"
              fallback={initials}
            />
            <h1>{names.join(' ')}</h1>
          </div>
          {!draft && (
            <button
              className="button primary"
              type="button"
              ref={editButton}
              onClick={() => {
                setSaved(false);
                setDraft({
                  firstName: profile.firstName,
                  lastName: profile.lastName,
                  bio: profile.bio ?? '',
                });
              }}
            >
              <Pencil aria-hidden="true" /> Edit profile
            </button>
          )}
        </div>
      </div>
      <div className="content-width profile-details">
        {saved && (
          <p className="profile-wide" role="status">
            Profile updated.
          </p>
        )}
        {draft && (
          <form
            className="profile-card profile-wide profile-edit-form"
            aria-labelledby="profile-edit"
            onSubmit={(event) => void save(event)}
          >
            <h2 id="profile-edit">Edit your profile</h2>
            <fieldset disabled={saving}>
              <label>
                First name
                <input
                  ref={firstNameInput}
                  required
                  value={draft.firstName}
                  onChange={(event) =>
                    setDraft({ ...draft, firstName: event.target.value })
                  }
                />
              </label>
              <label>
                Last name
                <input
                  required
                  value={draft.lastName}
                  onChange={(event) =>
                    setDraft({ ...draft, lastName: event.target.value })
                  }
                />
              </label>
              <label>
                Bio
                <textarea
                  rows={4}
                  value={draft.bio}
                  onChange={(event) =>
                    setDraft({ ...draft, bio: event.target.value })
                  }
                />
              </label>
              {saveError && (
                <p className="form-error" role="alert">
                  {saveError}
                </p>
              )}
              <div className="profile-form-actions">
                <button className="button primary" type="submit">
                  {saving ? 'Saving…' : 'Save changes'}
                </button>
                <button className="button" type="button" onClick={closeEditor}>
                  Cancel
                </button>
              </div>
            </fieldset>
          </form>
        )}
        <div className="profile-community">
          <section className="profile-card" aria-labelledby="profile-bio">
            <h2 id="profile-bio">Bio</h2>
            {profile.bio?.trim() ? (
              <p className="profile-bio">{profile.bio}</p>
            ) : (
              <p className="profile-empty">No bio yet.</p>
            )}
          </section>
          <PageLink
            className="profile-card saved-posts-link"
            page="profile/saved-posts"
          >
            <Bookmark aria-hidden="true" /> Saved posts{' '}
            <ChevronRight aria-hidden="true" />
          </PageLink>
          <Suspense
            fallback={
              <p className="profile-empty" role="status">
                Loading activity…
              </p>
            }
          >
            <MyActivity account={{ profile, accessToken, onUnauthorized }} />
          </Suspense>
        </div>
        <section
          className="profile-card profile-account-details"
          aria-labelledby="profile-account"
        >
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
            <ProfileOrganization
              key={profile.organizationId}
              profile={profile}
              accessToken={accessToken}
              onUnauthorized={onUnauthorized}
              onRefresh={onRefresh}
              onCreated={onOrganizationCreated}
            />
          </dl>
        </section>
      </div>
    </div>
  );
}
