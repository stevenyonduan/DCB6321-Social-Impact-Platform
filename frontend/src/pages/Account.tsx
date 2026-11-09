import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { ArrowRight, Eye, EyeOff } from 'lucide-react';
import { PageLink } from '../components/PageLink';
import type { Credentials, Signup } from '../api/auth';

export function Account({
  mode,
  onEnter,
}: {
  mode: 'login' | 'signup';
  onEnter: (input: Credentials | Signup, signal: AbortSignal) => Promise<void>;
}) {
  const signup = mode === 'signup';
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const active = useRef<AbortController | null>(null);
  useEffect(() => () => active.current?.abort(), []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (active.current) return;
    const form = event.currentTarget;
    const fields = new FormData(form);
    const first = String(fields.get('firstName') ?? '');
    const last = String(fields.get('lastName') ?? '');
    if (signup && (!first.trim() || !last.trim())) {
      setError('Please enter your first and last name.');
      return;
    }
    if (!String(fields.get('password') ?? '').trim()) {
      setError('Please enter your password.');
      return;
    }
    const controller = new AbortController();
    active.current = controller;
    setPending(true);
    setError('');
    try {
      const credentials = {
        email: String(fields.get('email') ?? ''),
        password: String(fields.get('password') ?? ''),
      };
      await onEnter(
        signup
          ? { ...credentials, firstName: first, lastName: last }
          : credentials,
        controller.signal,
      );
      if (!controller.signal.aborted) form.reset();
    } catch (failure) {
      if (!controller.signal.aborted) {
        setError(
          failure instanceof Error
            ? failure.message
            : 'Sign-in could not be completed. Please try again.',
        );
        const password = form.elements.namedItem('password');
        if (password instanceof HTMLInputElement) password.value = '';
      }
    } finally {
      if (!controller.signal.aborted) {
        active.current = null;
        setPending(false);
      }
    }
  }

  return (
    <section className="account-section">
      <div className="account-card">
        <nav className="account-switch" aria-label="Account access">
          <PageLink page="login" aria-current={!signup ? 'page' : undefined}>
            Log in
          </PageLink>
          <PageLink page="signup" aria-current={signup ? 'page' : undefined}>
            Sign up
          </PageLink>
        </nav>
        <h1>{signup ? 'Find your people.' : 'Welcome back.'}</h1>
        <p className="account-subtitle">
          {signup
            ? 'Join a community that turns caring into action.'
            : 'Your next good thing is waiting.'}
        </p>
        <form onSubmit={submit} aria-busy={pending}>
          {signup && (
            <div className="name-fields">
              <label htmlFor="first-name">
                First name
                <input
                  id="first-name"
                  name="firstName"
                  placeholder="Alex"
                  required
                  maxLength={100}
                  autoComplete="given-name"
                  disabled={pending}
                />
              </label>
              <label htmlFor="last-name">
                Last name
                <input
                  id="last-name"
                  name="lastName"
                  placeholder="Morgan"
                  required
                  maxLength={100}
                  autoComplete="family-name"
                  disabled={pending}
                />
              </label>
            </div>
          )}
          <label htmlFor="email">
            Email address
            <input
              id="email"
              name="email"
              type="email"
              placeholder="you@example.com"
              required
              maxLength={255}
              autoComplete="username"
              disabled={pending}
            />
          </label>
          <label htmlFor="password">Password</label>
          <div className="password-field">
            <input
              id="password"
              name="password"
              type={showPassword ? 'text' : 'password'}
              placeholder="Enter your password"
              required
              maxLength={1024}
              autoComplete={signup ? 'new-password' : 'current-password'}
              disabled={pending}
            />
            <button
              type="button"
              aria-label={showPassword ? 'Hide password' : 'Show password'}
              aria-pressed={showPassword}
              onClick={() => setShowPassword(!showPassword)}
            >
              {showPassword ? (
                <EyeOff aria-hidden="true" />
              ) : (
                <Eye aria-hidden="true" />
              )}
            </button>
          </div>
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          <button
            className="button primary submit-button"
            type="submit"
            disabled={pending}
          >
            {pending
              ? signup
                ? 'Creating account…'
                : 'Logging in…'
              : signup
                ? 'Create account'
                : 'Log in'}
            <ArrowRight aria-hidden="true" />
          </button>
        </form>
        <p className="account-alternative">
          {signup ? 'Already part of the community?' : 'New to SOW?'}{' '}
          <PageLink page={signup ? 'login' : 'signup'} className="text-link">
            {signup ? 'Log in instead' : 'Join the community'}
          </PageLink>
        </p>
      </div>
    </section>
  );
}
