import { object, request, type Profile } from './auth';

export type OrganizationProfile = {
  id: string;
  name: string;
  description: string | null;
  website: string | null;
  isVerified: boolean;
  createdAt: string;
};

type OrganizationPatch = Partial<
  Pick<OrganizationProfile, 'name' | 'description' | 'website'>
>;

export type OrganizationInput = Pick<
  OrganizationProfile,
  'name' | 'description' | 'website'
>;

function organizationProfile(value: unknown): value is OrganizationProfile {
  return (
    object(value) &&
    typeof value.id === 'string' &&
    /^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i.test(value.id) &&
    typeof value.name === 'string' &&
    (value.description === null || typeof value.description === 'string') &&
    (value.website === null || typeof value.website === 'string') &&
    typeof value.isVerified === 'boolean' &&
    typeof value.createdAt === 'string' &&
    Number.isFinite(Date.parse(value.createdAt))
  );
}

export function createOrganization(
  input: OrganizationInput,
  accessToken: string,
  signal: AbortSignal,
) {
  const { name, description, website } = input;
  return request(
    '/organizations',
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ name, description, website }),
    },
    organizationProfile,
    signal,
  );
}

export function canEditOrganization(profile: Profile, organizationId: string) {
  return (
    profile.role === 'ORGANIZATION' && profile.organizationId === organizationId
  );
}

export function organizationRequest(
  id: string,
  accessToken: string,
  signal: AbortSignal,
  patch?: OrganizationPatch,
) {
  return request(
    `/organizations/${encodeURIComponent(id)}`,
    {
      method: patch ? 'PATCH' : 'GET',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: 'application/json',
        ...(patch && { 'Content-Type': 'application/json' }),
      },
      body: patch && JSON.stringify(patch),
    },
    (value): value is OrganizationProfile =>
      organizationProfile(value) && value.id === id,
    signal,
  );
}
