import { ApiError, object, request } from './auth';

type Registration = {
  id: string;
  registeredAt: string;
};

function isRegistration(value: unknown): value is Registration {
  return (
    object(value) &&
    typeof value.id === 'string' &&
    value.id.length > 0 &&
    typeof value.registeredAt === 'string' &&
    Number.isFinite(Date.parse(value.registeredAt))
  );
}

type RegistrationPage<T> = { items: T[]; hasNext: boolean };
const lastRegistrationPage = 10_000;

function registrationPage<T>(
  path: string,
  page: number,
  size: number,
  validate: (value: unknown) => value is T,
  token: string,
  signal: AbortSignal,
) {
  return request(
    `${path}?page=${page}&size=${size}`,
    {
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
    },
    (value): value is RegistrationPage<T> =>
      object(value) &&
      value.page === page &&
      value.size === size &&
      typeof value.hasNext === 'boolean' &&
      Array.isArray(value.items) &&
      value.items.every(validate),
    signal,
  );
}

export type EventAttendee = Registration & {
  userId: string;
  firstName: string;
  lastName: string;
};

function isEventAttendee(value: unknown): value is EventAttendee {
  return (
    object(value) &&
    typeof value.userId === 'string' &&
    value.userId.length > 0 &&
    typeof value.firstName === 'string' &&
    typeof value.lastName === 'string' &&
    isRegistration(value)
  );
}

export function loadEventAttendees(
  eventId: string,
  page: number,
  token: string,
  signal: AbortSignal,
) {
  return registrationPage(
    `/organizations/me/opportunities/${encodeURIComponent(eventId)}/registrations`,
    page,
    20,
    isEventAttendee,
    token,
    signal,
  ).then(({ items, hasNext }) => ({
    items,
    hasNext: hasNext && page < lastRegistrationPage,
  }));
}

export type MyRegistration = Registration & {
  eventId: string;
  organizationId: string;
  title: string;
  eventStart: string;
  eventEnd: string;
  location: string;
};

function isMyRegistration(value: unknown): value is MyRegistration {
  return (
    object(value) &&
    [value.eventId, value.organizationId, value.title, value.location].every(
      (field) => typeof field === 'string' && field.length > 0,
    ) &&
    [value.eventStart, value.eventEnd].every(
      (field) =>
        typeof field === 'string' && Number.isFinite(Date.parse(field)),
    ) &&
    isRegistration(value)
  );
}

export async function loadMyRegistrations(token: string, signal: AbortSignal) {
  const size = 100;
  const registrations = new Map<string, MyRegistration>();
  for (let page = 0; page <= lastRegistrationPage; page++) {
    const result = await registrationPage(
      '/users/me/registrations',
      page,
      size,
      isMyRegistration,
      token,
      signal,
    );
    signal.throwIfAborted();
    for (const registration of result.items)
      registrations.set(registration.eventId, registration);
    if (!result.hasNext) return [...registrations.values()];
  }
  throw new ApiError(200, 'INVALID_RESPONSE');
}

export function registrationRequest(
  eventId: string,
  method: 'GET' | 'POST' | 'DELETE',
  token: string,
  signal: AbortSignal,
) {
  const path = `/opportunities/${encodeURIComponent(eventId)}/registrations/me`;
  const init = {
    method,
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
  };
  if (method === 'DELETE')
    return request(
      path,
      init,
      (value): value is undefined => value === undefined,
      signal,
    ).then(() => false);
  if (method === 'POST')
    return request(
      path,
      init,
      (value): value is Registration & { eventId: string; userId: string } =>
        object(value) &&
        value.eventId === eventId &&
        typeof value.userId === 'string' &&
        value.userId.length > 0 &&
        isRegistration(value),
      signal,
    ).then(() => true);
  return request(
    path,
    init,
    (value): value is { registered: boolean } =>
      object(value) &&
      value.eventId === eventId &&
      ((value.registered === true && isRegistration(value.registration)) ||
        (value.registered === false && value.registration === null)),
    signal,
  ).then(({ registered }) => registered);
}
