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
      object(value) &&
      value.id === id &&
      typeof value.name === 'string' &&
      (value.description === null || typeof value.description === 'string') &&
      (value.website === null || typeof value.website === 'string') &&
      typeof value.isVerified === 'boolean' &&
      typeof value.createdAt === 'string' &&
      Number.isFinite(Date.parse(value.createdAt)),
    signal,
  );
}
