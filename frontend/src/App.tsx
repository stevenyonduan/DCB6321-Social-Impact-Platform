import { useEffect, useRef, useState } from 'react';
import { Header, Footer } from './components/Shell';
import { Landing } from './pages/Landing';
import { Account } from './pages/Account';
import { Home } from './pages/Home';
import { ProfilePage } from './pages/Profile';
import { useAccount } from './auth/useAccount';
import type { Page } from './navigation';
import { getPage, isAccountPage, NavigationContext } from './navigation';

const pageTitles: Record<Page, string> = {
  landing: 'Making Service Social',
  login: 'Welcome back',
  signup: 'Join the community',
  home: 'Your community',
  profile: 'Profile',
};

export default function App() {
  const [page, setPage] = useState<Page>(() => getPage());
  const { state, signIn, logout, retry } = useAccount();
  const main = useRef<HTMLElement>(null);
  const accountPage = isAccountPage(page);
  const redirectToLogin = accountPage && state.status === 'anonymous';
  const visiblePage = redirectToLogin ? 'login' : page;

  useEffect(() => {
    const onPopState = () => setPage(getPage());
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  useEffect(() => {
    if (redirectToLogin) {
      window.history.replaceState(null, '', '/login');
    }
    document.title = `${pageTitles[visiblePage]} | SOW`;
    main.current?.focus();
    window.scrollTo(0, 0);
  }, [redirectToLogin, state.status, visiblePage]);

  function navigate(next: Page) {
    window.history.pushState(null, '', next === 'landing' ? '/' : `/${next}`);
    setPage(next);
  }

  return (
    <NavigationContext value={navigate}>
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <Header
        page={visiblePage}
        onLogout={() => {
          void logout().then(() => navigate('landing'));
        }}
      />
      <main id="main-content" ref={main} tabIndex={-1}>
        {visiblePage === 'landing' && <Landing />}
        {state.message && state.status !== 'error' && (
          <p className="content-width form-error" role="status">
            {state.message}
          </p>
        )}
        {(visiblePage === 'login' || visiblePage === 'signup') &&
          state.status !== 'signing-out' && (
            <Account
              key={visiblePage}
              mode={visiblePage}
              onEnter={async (input, signal) => {
                await signIn(visiblePage, input, signal);
                if (!signal.aborted) navigate('home');
              }}
            />
          )}
        {(accountPage && state.status === 'loading') ||
        state.status === 'signing-out' ? (
          <section className="account-section" role="status">
            {state.status === 'signing-out'
              ? 'Signing out…'
              : 'Loading your profile…'}
          </section>
        ) : null}
        {accountPage && state.status === 'error' && (
          <section className="account-section">
            <div className="account-card">
              <p role="alert">{state.message}</p>
              <button className="button primary" onClick={retry}>
                Try again
              </button>
            </div>
          </section>
        )}
        {visiblePage === 'home' && state.status === 'ready' && (
          <Home name={state.profile.firstName} />
        )}
        {visiblePage === 'profile' && state.status === 'ready' && (
          <ProfilePage profile={state.profile} />
        )}
      </main>
      <Footer />
    </NavigationContext>
  );
}
