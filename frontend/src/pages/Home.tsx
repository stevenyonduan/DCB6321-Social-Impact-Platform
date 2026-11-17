import { MapPin } from 'lucide-react';
import type { CommunityAccount } from '../components/CommunityPost';
import { CommunityPosts } from '../components/CommunityPosts';
import { HomeOpportunities } from '../components/HomeOpportunities';

export function Home({ account }: { account: CommunityAccount }) {
  return (
    <>
      <section className="home-welcome">
        <div className="content-width welcome-inner">
          <div>
            <span className="eyebrow">YOUR COMMUNITY STARTS HERE</span>
            <h1>Hi, {account.profile.firstName}. Let’s do some good.</h1>
            <p>A small step today can make a difference close to home.</p>
          </div>
          <span className="location">
            <MapPin aria-hidden="true" />
            Atlanta, GA
          </span>
        </div>
      </section>
      <div className="content-width home-body">
        <div className="feed-layout">
          <section className="feed-column" aria-labelledby="feed-title">
            <div className="column-heading">
              <h2 id="feed-title">Your community</h2>
              <span>Recent posts</span>
            </div>
            <CommunityPosts account={account} />
          </section>
          <HomeOpportunities account={account} />
        </div>
      </div>
    </>
  );
}
