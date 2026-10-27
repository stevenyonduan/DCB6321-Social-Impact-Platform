import { useState } from 'react';
import type { FormEvent } from 'react';
import { ArrowRight, Eye, EyeOff } from 'lucide-react';
import { PageLink } from '../components/PageLink';

export function Account({
  mode,
  onEnter,
}: {
  mode: 'login' | 'signup';
  onEnter: (name: string) => void;
}) {
  const signup = mode === 'signup';
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    const first = String(fields.get('firstName') ?? '').trim();
    const last = String(fields.get('lastName') ?? '').trim();
    if (signup && (!first || !last)) {
      setError('Please enter your first and last name.');
      return;
    }
    if (!String(fields.get('password') ?? '').trim()) {
      setError('Please enter your password.');
      return;
    }
    // Credentials never leave this form or enter session storage.
    form.reset();
    onEnter(signup ? first : 'Alex');
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
        <form onSubmit={submit} autoComplete="off">
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
              autoComplete="new-password"
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
          <button className="button primary submit-button" type="submit">
            {signup ? 'Create account' : 'Log in'}
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
