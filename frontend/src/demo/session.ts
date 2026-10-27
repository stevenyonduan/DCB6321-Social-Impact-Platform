// A local presentation convenience, never an authentication/authorization boundary.
export const DEMO_SESSION_KEY = 'sow-demo-session';
export type DemoSession = { displayName: string };

export function readDemoSession(): DemoSession | null {
  try {
    const raw = sessionStorage.getItem(DEMO_SESSION_KEY);
    if (!raw) return null;
    const value: unknown = JSON.parse(raw);
    if (
      typeof value === 'object' &&
      value !== null &&
      'displayName' in value &&
      typeof value.displayName === 'string' &&
      value.displayName.trim() &&
      value.displayName.length <= 100
    ) {
      return { displayName: value.displayName };
    }
  } catch {
    // The walkthrough still works in memory when browser storage is unavailable.
  }
  return null;
}

export function startDemoSession(displayName: string): DemoSession {
  const session = { displayName: displayName.trim().slice(0, 100) || 'Alex' };
  try {
    sessionStorage.setItem(DEMO_SESSION_KEY, JSON.stringify(session));
  } catch {
    /* Optional persistence only. */
  }
  return session;
}

export function endDemoSession() {
  try {
    sessionStorage.removeItem(DEMO_SESSION_KEY);
  } catch {
    /* No persistent session to clear. */
  }
}
