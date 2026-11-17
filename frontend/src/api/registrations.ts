import { object, request } from './auth';

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
