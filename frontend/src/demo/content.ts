// Presentation fixtures, not API contracts or records from real organizations.
export const opportunities = [
  {
    id: 'garden',
    theme: 'garden',
    category: 'Environment',
    title: 'Community garden morning',
    description: 'Plant something good and meet your neighbors along the way.',
    location: 'Atlanta community garden',
    schedule: 'Saturday · 9:00 AM–12:00 PM',
    artwork: 'GROW TOGETHER',
  },
  {
    id: 'food',
    theme: 'food',
    category: 'Food & community',
    title: 'Pack meals. Share kindness.',
    description:
      'Join a friendly team preparing food packs for local families.',
    location: 'Atlanta donation center',
    schedule: 'Saturday · 10:00 AM–1:00 PM',
    artwork: 'GIVE A LITTLE GOOD',
  },
  {
    id: 'books',
    theme: 'books',
    category: 'Learning & mentorship',
    title: 'Books for brighter beginnings',
    description:
      'Help sort and prepare donated books for community reading spaces.',
    location: 'Atlanta community center',
    schedule: 'Sunday · 1:00 PM–3:00 PM',
    artwork: 'OPEN NEW CHAPTERS',
  },
] as const;

export const posts = [
  {
    id: 'garden-post',
    author: 'Neighborhood Garden Collective',
    byline: 'Community organization · 2 hours ago',
    avatar: 'garden',
    text: 'Fresh soil, new connections, and a whole lot of good. Thank you to everyone who spent the morning helping our community garden grow! 🌱',
    artwork: true,
    likes: 12,
    comments: 3,
  },
  {
    id: 'volunteer-post',
    author: 'Jordan Taylor',
    byline: 'Volunteer · 4 hours ago',
    avatar: 'JT',
    text: 'My first time volunteering at a food packing event today. Came on my own and left knowing a few more neighbors. Already looking forward to the next one!',
    artwork: false,
    likes: 8,
    comments: 2,
  },
] as const;
