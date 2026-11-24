ALTER TABLE photograph_app.accounts
    ADD COLUMN phone varchar(16) UNIQUE,
    ADD COLUMN is_operator boolean NOT NULL DEFAULT false,
    ADD COLUMN accepts_paid_bookings boolean NOT NULL DEFAULT false,
    ADD COLUMN accepts_tfp_bookings boolean NOT NULL DEFAULT false;

ALTER TABLE photograph_app.photographer_profiles
    ADD COLUMN version integer NOT NULL DEFAULT 1 CHECK (version > 0);

CREATE TABLE photograph_app.otp_requests (
    phone varchar(16) PRIMARY KEY,
    challenge_id uuid NOT NULL UNIQUE,
    code_digest text NOT NULL,
    sent_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    window_started_at timestamptz NOT NULL,
    window_count integer NOT NULL,
    attempts_left integer NOT NULL DEFAULT 5 CHECK (attempts_left >= 0),
    consumed_at timestamptz
);

CREATE TABLE photograph_app.auth_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    access_digest text NOT NULL UNIQUE,
    refresh_digest text NOT NULL UNIQUE,
    access_expires_at timestamptz NOT NULL,
    refresh_expires_at timestamptz NOT NULL
);

CREATE TABLE photograph_app.photographer_review_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    photographer_id uuid NOT NULL REFERENCES photograph_app.photographer_profiles (id),
    operator_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    decision text NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED', 'SUSPENDED')),
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    profile_version integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (photographer_id, profile_version)
);
