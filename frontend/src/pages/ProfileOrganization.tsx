import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, type Profile } from '../api/auth';
import { createOrganization, organizationRequest } from '../api/organizations';
import { PageLink } from '../components/PageLink';

export function ProfileOrganization({
  profile,
  accessToken,
  onUnauthorized,
  onRefresh,
  onCreated,
}: {
  profile: Profile;
  accessToken: string;
  onUnauthorized: () => void;
  onRefresh: () => void;
  onCreated: (id: string) => void;
}) {
  const id = profile.organizationId;
  const [name, setName] = useState<string>();
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    void organizationRequest(id, accessToken, controller.signal).then(
      (organization) => {
        if (!controller.signal.aborted) setName(organization.name);
      },
      (error: unknown) => {
        if (
          !controller.signal.aborted &&
          error instanceof ApiError &&
          error.status === 401
        )
          onUnauthorized();
      },
    );
    return () => controller.abort();
  }, [id, accessToken, onUnauthorized]);

  return (
    <>
      <dt>My organization</dt>
      <dd>
        {id ? (
          <PageLink
            className="organization-website"
            page={`organizations/${id}`}
          >
            {name ?? 'View organization'}
          </PageLink>
        ) : profile.role === 'STANDARD' ? (
          <button
            ref={trigger}
            className="text-link create-organization-link"
            type="button"
            onClick={() => setOpen(true)}
          >
            <span aria-hidden="true">+</span>
            <span className="organization-website">Create organization</span>
          </button>
        ) : (
          <span className="profile-empty">No organization linked.</span>
        )}
        {open && (
          <CreateOrganizationDialog
            accessToken={accessToken}
            onUnauthorized={onUnauthorized}
            onRefresh={onRefresh}
            onCreated={onCreated}
            onClose={() => {
              setOpen(false);
              requestAnimationFrame(() => trigger.current?.focus());
            }}
          />
        )}
      </dd>
    </>
  );
}

function CreateOrganizationDialog({
  accessToken,
  onUnauthorized,
  onRefresh,
  onCreated,
  onClose,
}: {
  accessToken: string;
  onUnauthorized: () => void;
  onRefresh: () => void;
  onCreated: (id: string) => void;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const pending = useRef<AbortController | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [refreshNeeded, setRefreshNeeded] = useState(false);

  useEffect(() => {
    dialog.current?.showModal();
    return () => pending.current?.abort();
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current || refreshNeeded) return;
    const fields = new FormData(event.currentTarget);
    const name = String(fields.get('name')).trim();
    const description = String(fields.get('description')).trim() || null;
    const website = String(fields.get('website')).trim() || null;
    if (!name || Array.from(name).length > 255) {
      setError('Enter an organization name of 1 to 255 characters.');
      return;
    }
    if (
      website &&
      (!/^https?:\/\//i.test(website) || Array.from(website).length > 255)
    ) {
      setError('Enter an HTTP or HTTPS website URL of up to 255 characters.');
      return;
    }

    const controller = new AbortController();
    pending.current = controller;
    setBusy(true);
    setError('');
    try {
      const organization = await createOrganization(
        { name, description, website },
        accessToken,
        controller.signal,
      );
      if (!controller.signal.aborted) onCreated(organization.id);
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401)
        onUnauthorized();
      else if (
        failure instanceof ApiError &&
        [400, 403].includes(failure.status)
      ) {
        setError(
          failure.status === 400
            ? 'Check the organization fields and try again.'
            : 'This account cannot create an organization.',
        );
      } else {
        setRefreshNeeded(true);
        setError(
          failure instanceof ApiError && failure.status === 409
            ? 'This account is already linked to an organization. Refresh your profile to see it.'
            : 'Creation could not be confirmed. Refresh your profile to check whether it succeeded.',
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
      className="preview-dialog create-organization-dialog"
      aria-labelledby="create-organization-title"
      onCancel={(event) => {
        event.preventDefault();
        if (!pending.current) onClose();
      }}
    >
      <h2 id="create-organization-title">Create organization</h2>
      <p className="dialog-note">
        Set up an organization page using your current account.
      </p>
      <form onSubmit={(event) => void submit(event)}>
        <fieldset disabled={busy || refreshNeeded}>
          <label>
            Organization name (required)
            <input
              name="name"
              required
              placeholder="e.g. Atlanta Community Gardens"
            />
          </label>
          <label>
            Description (optional)
            <textarea
              name="description"
              rows={3}
              placeholder="Tell volunteers what your organization does"
            />
          </label>
          <label>
            Website (optional)
            <input
              name="website"
              type="url"
              placeholder="https://example.org"
            />
          </label>
        </fieldset>
        {error && (
          <p className="form-error" role="alert">
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
          {refreshNeeded ? (
            <button
              type="button"
              className="button primary"
              onClick={() => {
                onClose();
                onRefresh();
              }}
            >
              Refresh profile
            </button>
          ) : (
            <button type="submit" className="button primary" disabled={busy}>
              {busy ? 'Creating…' : 'Create organization'}
            </button>
          )}
        </div>
      </form>
    </dialog>
  );
}
