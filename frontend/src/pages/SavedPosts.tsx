import { ArrowLeft } from 'lucide-react';
import type { CommunityAccount } from '../components/CommunityPost';
import { CommunityPosts } from '../components/CommunityPosts';
import { PageLink } from '../components/PageLink';

export function SavedPosts({ account }: { account: CommunityAccount }) {
  return (
    <>
      <div className="home-welcome">
        <div className="content-width welcome-inner saved-posts-heading">
          <div>
            <PageLink page="profile" className="text-link">
              <ArrowLeft aria-hidden="true" /> Back to profile
            </PageLink>
            <h1>Saved posts</h1>
          </div>
        </div>
      </div>
      <section
        className="content-width home-body saved-posts-content"
        aria-label="Your saved posts"
      >
        <CommunityPosts account={account} saved />
      </section>
    </>
  );
}
