export type AccountUser = {
  id: string;
  email: string;
  role: 'STANDARD' | 'ADMIN' | 'ORGANIZATION';
  firstName: string;
  lastName: string;
};

export type Profile = AccountUser & {
  bio: string | null;
  avatarUrl: string | null;
  organizationId: string | null;
  createdAt: string;
};

export type AuthResult = {
  user: AccountUser;
  session: {
    accessToken: string;
    refreshToken: string;
    tokenType: string;
    expiresIn: number;
    expiresAt: number;
  };
};

export type Credentials = { email: string; password: string };
export type Signup = Credentials & { firstName: string; lastName: string };

const messages: Record<string, string> = {
  INVALID_REQUEST: 'Please check your details and try again.',
  INVALID_CREDENTIALS: 'Invalid email or password.',
  SIGNUP_CONFLICT:
    'Registration could not proceed. Try logging in or contact support.',
  REQUEST_TOO_LARGE: 'Your request is too large. Please check your details.',
  AUTH_RATE_LIMITED: 'Too many attempts. Please wait before trying again.',
  ACCOUNT_PROVISIONING_FAILED:
    'Your account setup could not finish. Contact support before signing up again.',
  ACCOUNT_PROVISIONING_REQUIRED:
    'Your account setup needs attention. Please contact support.',
  ACCOUNT_UNAVAILABLE:
    'Your profile is temporarily unavailable. Please try again.',
  UNAUTHORIZED: 'Your session has expired. Please log in again.',
};

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
  ) {
    super(
      messages[code] ?? 'The service is unavailable. Please try again later.',
    );
    this.name = 'ApiError';
  }
}

export function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function account(value: unknown): value is AccountUser {
  return (
    object(value) &&
    ['id', 'email', 'firstName', 'lastName'].every(
      (key) => typeof value[key] === 'string' && value[key].length > 0,
    ) &&
    typeof value.role === 'string' &&
    ['STANDARD', 'ADMIN', 'ORGANIZATION'].includes(value.role)
  );
}

function authResult(value: unknown): value is AuthResult {
  if (!object(value) || !account(value.user) || !object(value.session))
    return false;
  const session = value.session;
  return (
    ['accessToken', 'refreshToken'].every(
      (key) => typeof session[key] === 'string' && session[key].length > 0,
    ) &&
    session.tokenType === 'bearer' &&
    typeof session.expiresIn === 'number' &&
    Number.isSafeInteger(session.expiresIn) &&
    session.expiresIn > 0 &&
    typeof session.expiresAt === 'number' &&
    Number.isSafeInteger(session.expiresAt) &&
    session.expiresAt > Date.now() / 1000
  );
}

function profile(value: unknown): value is Profile {
  if (!account(value)) return false;
  const record = value as unknown as Record<string, unknown>;
  return (
    ['bio', 'avatarUrl', 'organizationId'].every(
      (key) => record[key] === null || typeof record[key] === 'string',
    ) &&
    typeof record.createdAt === 'string' &&
    Number.isFinite(Date.parse(record.createdAt))
  );
}

export async function request<T>(
  path: string,
  init: RequestInit,
  validate: (value: unknown) => value is T,
  signal?: AbortSignal,
): Promise<T> {
  const timeout = AbortSignal.timeout(30000);
  const combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
  try {
    const base = (import.meta.env.VITE_API_BASE_URL || '/api').replace(
      /\/$/,
      '',
    );
    const response = await fetch(`${base}${path}`, {
      ...init,
      signal: combined,
      cache: 'no-store',
      credentials: 'omit',
      redirect: 'error',
    });
    const contentType = response.headers
      .get('Content-Type')
      ?.split(';')[0]
      .trim()
      .toLowerCase();
    if (contentType !== 'application/json')
      throw new ApiError(response.status, 'INVALID_RESPONSE');
    const body: unknown = await response.json();
    if (!response.ok) {
      throw new ApiError(
        response.status,
        object(body) && typeof body.code === 'string'
          ? body.code
          : 'UNKNOWN_ERROR',
      );
    }
    if (!validate(body))
      throw new ApiError(response.status, 'INVALID_RESPONSE');
    return body;
  } catch (error) {
    if (signal?.aborted)
      throw new DOMException('Request cancelled', 'AbortError');
    if (error instanceof ApiError) throw error;
    throw new ApiError(0, 'SERVICE_UNAVAILABLE');
  }
}

export function authenticate(
  mode: 'login' | 'signup',
  input: Signup | Credentials,
  signal?: AbortSignal,
) {
  // Construct the allowlist explicitly: never forward role or identity fields.
  const body =
    mode === 'signup' && 'firstName' in input
      ? {
          email: input.email,
          password: input.password,
          firstName: input.firstName,
          lastName: input.lastName,
        }
      : { email: input.email, password: input.password };
  return request(
    `/auth/${mode}`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(body),
    },
    authResult,
    signal,
  );
}

export function getProfile(accessToken: string, signal?: AbortSignal) {
  return request(
    '/users/me',
    {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: 'application/json',
      },
    },
    profile,
    signal,
  );
}
