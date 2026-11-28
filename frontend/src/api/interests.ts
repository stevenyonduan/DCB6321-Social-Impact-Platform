import { object, request } from './auth';

export type Interest = { id: string; name: string };
export type InterestScope = 'personal' | 'organization';

const selectionPaths = {
  personal: '/users/me/interests',
  organization: '/organizations/me/interests',
};

function isInterestList(value: unknown): value is Interest[] {
  return (
    Array.isArray(value) &&
    value.every(
      (item) =>
        object(item) &&
        typeof item.id === 'string' &&
        /^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i.test(item.id) &&
        typeof item.name === 'string' &&
        item.name.trim().length > 0,
    )
  );
}

function interestRequest(
  path: string,
  accessToken: string,
  signal: AbortSignal,
  interestIds?: string[],
) {
  return request(
    path,
    {
      method: interestIds ? 'PUT' : 'GET',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: 'application/json',
        ...(interestIds && { 'Content-Type': 'application/json' }),
      },
      body: interestIds && JSON.stringify({ interestIds }),
    },
    isInterestList,
    signal,
  );
}

export function loadInterestCatalog(accessToken: string, signal: AbortSignal) {
  return interestRequest('/interests', accessToken, signal);
}

export function loadInterestSelection(
  accessToken: string,
  signal: AbortSignal,
  organizationId?: string,
) {
  const path = organizationId
    ? `/organizations/${encodeURIComponent(organizationId)}/interests`
    : selectionPaths.personal;
  return interestRequest(path, accessToken, signal);
}

export function saveInterestSelection(
  scope: InterestScope,
  interestIds: string[],
  accessToken: string,
  signal: AbortSignal,
) {
  return interestRequest(
    selectionPaths[scope],
    accessToken,
    signal,
    interestIds,
  );
}
