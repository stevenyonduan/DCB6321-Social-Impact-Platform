import { useId } from 'react';
import type { useProfileInterests } from './useProfileInterests';

type Interests = ReturnType<typeof useProfileInterests>;

export function InterestPicker({ interests }: { interests: Interests }) {
  const { catalog, saved, selected, toggle, retry } = interests;
  return (
    <fieldset className="interest-picker">
      <legend>Interests</legend>
      {catalog === 'error' || saved === 'error' ? (
        <>
          <p className="form-error" role="alert">
            Interests could not be loaded.
          </p>
          <button className="button" type="button" onClick={retry}>
            Try again
          </button>
        </>
      ) : catalog === undefined || saved === undefined ? (
        <p className="profile-empty" role="status">
          Loading interests…
        </p>
      ) : catalog.length === 0 ? (
        <p className="profile-empty">No interests are available.</p>
      ) : (
        <div className="interest-options">
          {catalog.map(({ id, name }) => (
            <label key={id} className="interest-option">
              <input
                type="checkbox"
                checked={selected.includes(id)}
                onChange={(event) => toggle(id, event.target.checked)}
              />
              {name}
            </label>
          ))}
        </div>
      )}
    </fieldset>
  );
}

export function ProfileInterestTags({
  interests: { saved, retry },
  editing,
}: {
  interests: Interests;
  editing: boolean;
}) {
  const headingId = useId();
  return (
    <div className="profile-interests">
      <h3 id={headingId}>Interests</h3>
      {saved === 'error' ? (
        <>
          <p className="profile-empty">Interests could not be loaded.</p>
          {!editing && (
            <button className="button" type="button" onClick={retry}>
              Try again
            </button>
          )}
        </>
      ) : saved === undefined ? (
        <p className="profile-empty" role={editing ? undefined : 'status'}>
          Loading interests…
        </p>
      ) : saved.length === 0 ? (
        <p className="profile-empty">No interests yet.</p>
      ) : (
        <ul className="profile-interest-tags" aria-labelledby={headingId}>
          {saved.map(({ id, name }) => (
            <li key={id}>{name}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
