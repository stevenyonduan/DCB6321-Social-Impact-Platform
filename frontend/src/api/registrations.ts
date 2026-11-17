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
  for (let page = 0; page <= 10_000; page++) {
    const result = await request(
      `/users/me/registrations?page=${page}&size=${size}`,
      {
        headers: {
          Authorization: `Bearer ${token}`,
          Accept: 'application/json',
        },
      },
      (value): value is { items: MyRegistration[]; hasNext: boolean } =>
        object(value) &&
        value.page === page &&
        value.size === size &&
        typeof value.hasNext === 'boolean' &&
        Array.isArray(value.items) &&
        value.items.every(isMyRegistration),
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
