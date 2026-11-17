import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { Header, Footer } from './components/Shell';
import { Landing } from './pages/Landing';
import { Account } from './pages/Account';
import { ProfilePage } from './pages/Profile';
import { OrganizationPage } from './pages/Organization';
import { useAccount } from './auth/useAccount';
import type { Page } from './navigation';
import {
  getPage,
  isAccountPage,
  isOrganizationPage,
  NavigationContext,
} from './navigation';

const MyEvents = lazy(() =>
  import('./pages/MyEvents').then(({ MyEvents }) => ({ default: MyEvents })),
);
const Home = lazy(() =>
  import('./pages/Home').then(({ Home }) => ({ default: Home })),
);
const SavedPosts = lazy(() =>
  import('./pages/SavedPosts').then(({ SavedPosts }) => ({
    default: SavedPosts,
  })),
);

const pageTitles: Record<Exclude<Page, `organizations/${string}`>, string> = {
  landing: 'Making Service Social',
  login: 'Welcome back',
  signup: 'Join the community',
  home: 'Your community',
  events: 'My Events',
  profile: 'Profile',
  'profile/saved-posts': 'Saved posts',
};

export default function App() {
  const [page, setPage] = useState<Page>(() => getPage());
  const { state, signIn, logout, expireSession, retry, replaceProfile } =
    useAccount();
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
    document.title = `${isOrganizationPage(visiblePage) ? 'Organization profile' : pageTitles[visiblePage]} | SOW`;
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
        signedIn={state.status === 'ready'}
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
                if (!signal.aborted)
                  navigate(
                    isOrganizationPage(page) ||
                      page === 'events' ||
                      page === 'profile/saved-posts'
                      ? page
                      : 'home',
                  );
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
        {(visiblePage === 'home' || visiblePage === 'profile/saved-posts') &&
          state.status === 'ready' && (
            <Suspense
              fallback={
                <section className="account-section" role="status">
                  Loading posts…
                </section>
              }
            >
              {visiblePage === 'home' ? (
                <Home
                  key={state.profile.id}
                  account={{ ...state, onUnauthorized: expireSession }}
                />
              ) : (
                <SavedPosts
                  key={state.profile.id}
                  account={{ ...state, onUnauthorized: expireSession }}
                />
              )}
            </Suspense>
          )}
        {visiblePage === 'events' && state.status === 'ready' && (
          <Suspense
            fallback={
              <section className="account-section" role="status">
                Loading your events…
              </section>
            }
          >
            <MyEvents
              key={state.profile.id}
              accessToken={state.accessToken}
              onUnauthorized={expireSession}
            />
          </Suspense>
        )}
        {visiblePage === 'profile' && state.status === 'ready' && (
          <ProfilePage
            key={state.profile.id}
            profile={state.profile}
            accessToken={state.accessToken}
            onUnauthorized={expireSession}
            onRefresh={retry}
            onProfileUpdated={(profile) =>
              replaceProfile(profile, state.accessToken)
            }
            onOrganizationCreated={(id) => {
              retry();
              navigate(`organizations/${id}`);
            }}
          />
        )}
        {isOrganizationPage(visiblePage) && state.status === 'ready' && (
          <OrganizationPage
            key={visiblePage}
            id={visiblePage.slice('organizations/'.length).toLowerCase()}
            account={state.profile}
            accessToken={state.accessToken}
            onUnauthorized={expireSession}
          />
        )}
      </main>
      <Footer />
    </NavigationContext>
  );
}
