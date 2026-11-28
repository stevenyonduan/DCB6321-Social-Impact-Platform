import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/auth';
import {
  loadInterestCatalog,
  loadInterestSelection,
  saveInterestSelection,
  type Interest,
} from '../api/interests';

type InterestList = Interest[] | 'error' | undefined;

export function useProfileInterests({
  editing,
  organizationId,
  enabled = true,
  accessToken,
  onUnauthorized,
}: {
  editing: boolean;
  organizationId?: string;
  enabled?: boolean;
  accessToken: string;
  onUnauthorized: () => void;
}) {
  const [catalog, setCatalog] = useState<InterestList>();
  const [saved, setSaved] = useState<InterestList>();
  const [draft, setDraft] = useState<string[]>();
  const [catalogAttempt, setCatalogAttempt] = useState(0);
  const [selectionAttempt, setSelectionAttempt] = useState(0);
  const pendingSelection = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    pendingSelection.current = controller;
    void loadInterestSelection(
      accessToken,
      controller.signal,
      organizationId,
    ).then(
      (selection) => {
        if (!controller.signal.aborted) setSaved(selection);
      },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) {
          onUnauthorized();
        } else {
          setSaved((current) => (Array.isArray(current) ? current : 'error'));
        }
      },
    );
    return () => controller.abort();
  }, [organizationId, enabled, accessToken, selectionAttempt, onUnauthorized]);

  useEffect(() => {
    if (!editing) return;
    const controller = new AbortController();
    void loadInterestCatalog(accessToken, controller.signal).then(
      (options) => {
        if (!controller.signal.aborted) setCatalog(options);
      },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) onUnauthorized();
        else
          setCatalog((current) => (Array.isArray(current) ? current : 'error'));
      },
    );
    return () => controller.abort();
  }, [editing, accessToken, catalogAttempt, onUnauthorized]);

  const savedIds = Array.isArray(saved) ? saved.map(({ id }) => id) : [];
  const selected = draft ?? savedIds;
  const changed = Boolean(
    draft &&
    Array.isArray(saved) &&
    (draft.length !== savedIds.length ||
      draft.some((id) => !savedIds.includes(id))),
  );

  function toggle(id: string, checked: boolean) {
    setDraft((current = savedIds) =>
      checked ? [...current, id] : current.filter((value) => value !== id),
    );
  }

  function retry() {
    if (catalog === 'error') {
      setCatalog(undefined);
      setCatalogAttempt((attempt) => attempt + 1);
    }
    if (saved === 'error') {
      setSaved(undefined);
      setSelectionAttempt((attempt) => attempt + 1);
    }
  }

  function close() {
    setDraft(undefined);
    setCatalog(undefined);
  }

  async function save(signal: AbortSignal) {
    const updated = await saveInterestSelection(
      organizationId ? 'organization' : 'personal',
      selected,
      accessToken,
      signal,
    );
    if (!signal.aborted) {
      pendingSelection.current?.abort();
      setSaved(updated);
      setDraft(undefined);
    }
  }

  return { catalog, saved, selected, changed, toggle, retry, close, save };
}
