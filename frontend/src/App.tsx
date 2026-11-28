import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { Header, Footer } from './components/Shell';
import { Landing } from './pages/Landing';
import { Account } from './pages/Account';
import { ProfilePage } from './pages/Profile';
import { OrganizationPage } from './pages/Organization';
import { useAccount } from './auth/useAccount';
import { OwnAvatarContext, useOwnAvatar } from './auth/ownAvatar';
import type { Page } from './navigation';
import {
  getPage,
  isAccountPage,
  isOrganizationPage,
  isPostPage,
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
const PostDetail = lazy(() =>
  import('./pages/PostDetail').then(({ PostDetail }) => ({
    default: PostDetail,
  })),
);

const pageTitles: Record<
  Exclude<Page, `organizations/${string}` | `posts/${string}`>,
  string
> = {
  landing: 'Making Service Social',
  login: 'Welcome back',
  signup: 'Join the community',
  home: 'Your community',
  events: 'My Events',
  profile: 'Profile',
  'profile/saved-posts': 'Saved posts',
};

function postAfterSignIn() {
  const requested = new URLSearchParams(window.location.search).get('next');
  const route = requested?.startsWith('/') ? requested.slice(1) : '';
  return isPostPage(route) ? route : undefined;
}

function accountPath(mode: 'login' | 'signup', post: string) {
  return `/${mode}?${new URLSearchParams({ next: `/${post}` })}`;
}

export default function App() {
  const [page, setPage] = useState<Page>(() => getPage());
  const { state, signIn, logout, expireSession, retry, replaceProfile } =
    useAccount();
  const avatar = useOwnAvatar(
    state.status === 'ready' ? state : undefined,
    expireSession,
  );
  const main = useRef<HTMLElement>(null);
  const accountPage = isAccountPage(page);
  const redirectToLogin = accountPage && state.status === 'anonymous';
  const visiblePage = redirectToLogin ? 'login' : page;
  const returnPost = isPostPage(page)
    ? page
    : page === 'login' || page === 'signup'
      ? postAfterSignIn()
      : undefined;

  useEffect(() => {
    const onPopState = () => setPage(getPage());
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  useEffect(() => {
    if (redirectToLogin) {
      window.history.replaceState(
        null,
        '',
        returnPost ? accountPath('login', returnPost) : '/login',
      );
    }
    const title = isOrganizationPage(visiblePage)
      ? 'Organization profile'
      : isPostPage(visiblePage)
        ? 'Post'
        : pageTitles[visiblePage];
    document.title = `${title} | SOW`;
    main.current?.focus();
    window.scrollTo(0, 0);
  }, [redirectToLogin, returnPost, state.status, visiblePage]);

  function navigate(next: Page) {
    const path =
      (next === 'login' || next === 'signup') && returnPost
        ? accountPath(next, returnPost)
        : next === 'landing'
          ? '/'
          : `/${next}`;
    window.history.pushState(null, '', path);
    setPage(next);
  }

  const content = (
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
                    returnPost ??
                      (isOrganizationPage(page) ||
                      page === 'events' ||
                      page === 'profile/saved-posts'
                        ? page
                        : 'home'),
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
              profile={state.profile}
              accessToken={state.accessToken}
              onUnauthorized={expireSession}
            />
          </Suspense>
        )}
        {isPostPage(visiblePage) && state.status === 'ready' && (
          <Suspense
            fallback={
              <p className="content-width" role="status">
                Loading post…
              </p>
            }
          >
            <PostDetail
              key={`${state.profile.id}/${visiblePage}`}
              id={visiblePage.slice('posts/'.length).toLowerCase()}
              account={{ ...state, onUnauthorized: expireSession }}
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
            onProfileUpdated={(profile, section) =>
              replaceProfile(profile, state.accessToken, section)
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
  return <OwnAvatarContext value={avatar}>{content}</OwnAvatarContext>;
}
