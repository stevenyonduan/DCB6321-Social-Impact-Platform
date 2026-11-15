import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ArrowUpRight, CircleCheck, Pencil } from 'lucide-react';
import { ApiError, type Profile } from '../api/auth';
import {
  canEditOrganization,
  organizationRequest,
  type OrganizationProfile,
} from '../api/organizations';
import { OrganizationOpportunities } from './OrganizationOpportunities';

const monthAndYear = new Intl.DateTimeFormat('en-US', {
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
});

function websiteUrl(value: string | null) {
  if (!value || !/^https?:\/\//i.test(value)) return null;
  try {
    const url = new URL(value);
    return (url.protocol === 'https:' || url.protocol === 'http:') &&
      !url.username &&
      !url.password
      ? url
      : null;
  } catch {
    return null;
  }
}

type Draft = { name: string; description: string; website: string };

export function OrganizationPage({
  id,
  account,
  accessToken,
  onUnauthorized,
}: {
  id: string;
  account: Profile;
  accessToken: string;
  onUnauthorized: () => void;
}) {
  const [organization, setOrganization] = useState<OrganizationProfile>();
  const [loadError, setLoadError] = useState<{
    message: string;
    retry: boolean;
  }>();
  const [attempt, setAttempt] = useState(0);
  const [draft, setDraft] = useState<Draft>();
  const [saveError, setSaveError] = useState('');
  const [saved, setSaved] = useState(false);
  const [denied, setDenied] = useState(false);
  const [saving, setSaving] = useState(false);
  const pendingSave = useRef<AbortController | null>(null);
  const editButton = useRef<HTMLButtonElement>(null);
  const feedback = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    void organizationRequest(id, accessToken, controller.signal).then(
      (value) => {
        if (!controller.signal.aborted) setOrganization(value);
      },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) {
          onUnauthorized();
          return;
        }
        if (error instanceof ApiError && error.status === 404) {
          setLoadError({ message: 'Organization not found.', retry: false });
        } else {
          setLoadError({
            message: 'The organization profile could not be loaded.',
            retry: true,
          });
        }
      },
    );
    return () => {
      controller.abort();
      pendingSave.current?.abort();
    };
  }, [id, accessToken, attempt, onUnauthorized]);

  if (!organization) {
    return (
      <section className="content-width organization-status">
        <h1>Organization profile</h1>
        {loadError ? (
          <>
            <p role="alert">{loadError.message}</p>
            {loadError.retry && (
              <button
                className="button primary"
                onClick={() => {
                  setLoadError(undefined);
                  setAttempt(attempt + 1);
                }}
              >
                Try again
              </button>
            )}
          </>
        ) : (
          <p role="status">Loading organization profile…</p>
        )}
      </section>
    );
  }

  const profile = organization;
  const canEdit = canEditOrganization(account, id) && !denied;
  const website = websiteUrl(profile.website);

  function closeEditor() {
    setDraft(undefined);
    setSaveError('');
    requestAnimationFrame(() => editButton.current?.focus());
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!draft || !canEdit || pendingSave.current) return;
    if (!draft.name.trim() || Array.from(draft.name).length > 255) {
      setSaveError('Enter an organization name of 1 to 255 characters.');
      return;
    }
    if (
      draft.website &&
      (!websiteUrl(draft.website) || Array.from(draft.website).length > 255)
    ) {
      setSaveError(
        'Enter an absolute HTTP or HTTPS website URL of up to 255 characters, or leave it blank.',
      );
      return;
    }
    const patch = {
      ...(draft.name !== profile.name && { name: draft.name }),
      ...(draft.description !== (profile.description ?? '') && {
        description: draft.description || null,
      }),
      ...(draft.website !== (profile.website ?? '') && {
        website: draft.website || null,
      }),
    };
    if (Object.keys(patch).length === 0) {
      closeEditor();
      return;
    }
    const controller = new AbortController();
    pendingSave.current = controller;
    setSaving(true);
    setSaveError('');
    try {
      const updated = await organizationRequest(
        id,
        accessToken,
        controller.signal,
        patch,
      );
      if (controller.signal.aborted) return;
      setOrganization(updated);
      setSaved(true);
      closeEditor();
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) {
        onUnauthorized();
      } else if (error instanceof ApiError && error.status === 403) {
        setDenied(true);
        setDraft(undefined);
        setSaveError(
          'Changes were not saved. You do not have permission to edit this organization.',
        );
        requestAnimationFrame(() => feedback.current?.focus());
      } else {
        setSaveError(
          error instanceof ApiError && error.status === 400
            ? 'The organization details were rejected. Check the fields and try again.'
            : 'The save could not be confirmed. Try again or reload to check the latest details.',
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
        <div className="content-width profile-intro organization-intro">
          <div>
            <p className="organization-label">Organization profile</p>
            <h1>{profile.name}</h1>
            {profile.isVerified && (
              <p className="organization-verified">
                <CircleCheck aria-hidden="true" /> Verified
              </p>
            )}
          </div>
          {canEdit && !draft && (
            <button
              ref={editButton}
              className="button primary"
              onClick={() => {
                setSaved(false);
                setDraft({
                  name: profile.name,
                  description: profile.description ?? '',
                  website: profile.website ?? '',
                });
                requestAnimationFrame(() =>
                  document.getElementById('organization-name')?.focus(),
                );
              }}
            >
              <Pencil aria-hidden="true" /> Edit profile
            </button>
          )}
        </div>
      </div>
      <div className="content-width profile-details organization-details">
        {saveError && !draft && (
          <p
            className="form-error organization-wide"
            ref={feedback}
            tabIndex={-1}
            role="alert"
          >
            {saveError}
          </p>
        )}
        {saved && (
          <p className="organization-wide" role="status">
            Organization profile updated.
          </p>
        )}
        {draft && (
          <form
            className="profile-card organization-wide"
            aria-labelledby="organization-edit"
            onSubmit={(event) => void save(event)}
          >
            <h2 id="organization-edit">Edit organization profile</h2>
            <fieldset disabled={saving}>
              <label htmlFor="organization-name">
                Organization name
                <input
                  id="organization-name"
                  required
                  value={draft.name}
                  onChange={(event) =>
                    setDraft({ ...draft, name: event.target.value })
                  }
                />
              </label>
              <label>
                Description
                <textarea
                  rows={4}
                  value={draft.description}
                  onChange={(event) =>
                    setDraft({ ...draft, description: event.target.value })
                  }
                />
              </label>
              <label>
                Website
                <input
                  type="url"
                  value={draft.website}
                  onChange={(event) =>
                    setDraft({ ...draft, website: event.target.value })
                  }
                />
              </label>
              {saveError && (
                <p className="form-error" role="alert">
                  {saveError}
                </p>
              )}
              <div className="organization-form-actions">
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
        <section className="profile-card" aria-labelledby="organization-about">
          <h2 id="organization-about">About</h2>
          {profile.description?.trim() ? (
            <p className="profile-bio">{profile.description}</p>
          ) : (
            <p className="profile-empty">No description yet.</p>
          )}
        </section>
        <section
          className="profile-card"
          aria-labelledby="organization-details"
        >
          <h2 id="organization-details">Organization details</h2>
          <dl>
            <dt>Website</dt>
            <dd>
              {website ? (
                <a
                  className="organization-website"
                  href={website.href}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {profile.website
                    ?.replace(/^https?:\/\//i, '')
                    .replace(/\/$/, '')}{' '}
                  <ArrowUpRight aria-hidden="true" />
                </a>
              ) : (
                <span className="profile-empty">No website listed.</span>
              )}
            </dd>
            <dt>On SOW since</dt>
            <dd>
              <time dateTime={profile.createdAt}>
                {monthAndYear.format(new Date(profile.createdAt))}
              </time>
            </dd>
          </dl>
        </section>
        <OrganizationOpportunities
          id={id}
          accessToken={accessToken}
          canPublish={canEdit}
          onUnauthorized={onUnauthorized}
        />
      </div>
    </div>
  );
}
