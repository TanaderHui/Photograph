CREATE TABLE photograph_app.community_posts (
    id uuid PRIMARY KEY,
    author_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    body varchar(2000) NOT NULL CHECK (btrim(body) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    removed_at timestamptz
);

CREATE INDEX community_posts_feed ON photograph_app.community_posts (created_at DESC, id DESC)
    WHERE removed_at IS NULL;

CREATE TABLE photograph_app.account_follows (
    follower_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    followed_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    PRIMARY KEY (follower_id, followed_id),
    CHECK (follower_id <> followed_id)
);

-- Only approved public display variants belong in this read model.
CREATE TABLE photograph_app.public_photography_works (
    id uuid PRIMARY KEY,
    photographer_id uuid NOT NULL REFERENCES photograph_app.photographer_profiles (id),
    title varchar(120) NOT NULL CHECK (btrim(title) <> ''),
    caption varchar(1000) NOT NULL DEFAULT '',
    style_id text REFERENCES photograph_app.styles (id),
    thumbnail_url text NOT NULL CHECK (thumbnail_url LIKE 'https://%'),
    published_at timestamptz NOT NULL DEFAULT now(),
    withdrawn_at timestamptz
);

CREATE INDEX public_photography_work_feed ON photograph_app.public_photography_works (published_at DESC, id DESC)
    WHERE withdrawn_at IS NULL;
