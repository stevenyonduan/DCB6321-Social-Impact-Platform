import { object, request } from './auth';

export type RegistrationType = 'INTERNAL' | 'EXTERNAL';

export type PublishOpportunityInput = {
  title: string;
  description: string;
  location: string;
  eventStart: string;
  eventEnd: string;
  imageUrl: string | null;
  registrationType: RegistrationType;
  registrationUrl: string | null;
};

export type Opportunity = PublishOpportunityInput & {
  id: string;
  organizationId: string;
  creatorId: string;
  createdAt: string;
};

type OpportunityPage = {
  items: Opportunity[];
  page: number;
  size: number;
  hasNext: boolean;
};

export function opportunityWebUrl(value: string | null) {
  if (!value || !/^https?:\/\//i.test(value)) return null;
  try {
    const url = new URL(value);
    return url.hostname && !url.username && !url.password && url.port !== '0'
      ? url
      : null;
  } catch {
    return null;
  }
}

function isOpportunity(
  value: unknown,
  organizationId: string,
): value is Opportunity {
  return (
    object(value) &&
    value.organizationId === organizationId &&
    ['id', 'creatorId', 'title', 'description', 'location'].every(
      (key) => typeof value[key] === 'string' && value[key].length > 0,
    ) &&
    ['eventStart', 'eventEnd', 'createdAt'].every(
      (key) =>
        typeof value[key] === 'string' &&
        Number.isFinite(Date.parse(value[key])),
    ) &&
    (value.imageUrl === null || typeof value.imageUrl === 'string') &&
    ((value.registrationType === 'INTERNAL' &&
      value.registrationUrl === null) ||
      (value.registrationType === 'EXTERNAL' &&
        typeof value.registrationUrl === 'string'))
  );
}

export function listOpportunities(
  id: string,
  page: number,
  token: string,
  signal: AbortSignal,
) {
  return request(
    `/organizations/${encodeURIComponent(id)}/opportunities?page=${page}&size=20`,
    {
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
    },
    (value): value is OpportunityPage =>
      object(value) &&
      value.page === page &&
      value.size === 20 &&
      typeof value.hasNext === 'boolean' &&
      Array.isArray(value.items) &&
      value.items.every((item: unknown) => isOpportunity(item, id)),
    signal,
  );
}

export async function loadUpcomingOpportunityDescription(
  organizationId: string,
  eventId: string,
  token: string,
  signal: AbortSignal,
) {
  for (let page = 0; page <= 10_000; page++) {
    const result = await listOpportunities(organizationId, page, token, signal);
    signal.throwIfAborted();
    const opportunity = result.items.find((item) => item.id === eventId);
    if (opportunity) return opportunity.description;
    if (!result.hasNext) break;
  }
  return null;
}

export function publishOpportunity(
  id: string,
  input: PublishOpportunityInput,
  token: string,
  signal: AbortSignal,
) {
  return request(
    '/organizations/me/opportunities',
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(input),
    },
    (value): value is Opportunity => isOpportunity(value, id),
    signal,
  );
}
