CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

ALTER TABLE photograph_app.photographer_profiles
    ADD COLUMN calendar_visibility text NOT NULL DEFAULT 'PRIVATE' CHECK (calendar_visibility IN ('PRIVATE', 'PUBLIC')),
    ADD COLUMN calendar_zone text NOT NULL DEFAULT 'Asia/Shanghai',
    ADD COLUMN booking_buffer_minutes integer NOT NULL DEFAULT 0 CHECK (booking_buffer_minutes BETWEEN 0 AND 120);

CREATE TABLE photograph_app.availability_windows (
    photographer_id uuid NOT NULL REFERENCES photograph_app.photographer_profiles (id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    CHECK (ends_at > starts_at),
    PRIMARY KEY (photographer_id, starts_at),
    EXCLUDE USING gist (photographer_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
);

CREATE TABLE photograph_app.bookings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    photographer_id uuid NOT NULL REFERENCES photograph_app.photographer_profiles (id),
    photographer_account_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    package_id uuid NOT NULL REFERENCES photograph_app.photography_packages (id),
    package_snapshot jsonb NOT NULL,
    engagement_type text NOT NULL CHECK (engagement_type IN ('PAID', 'TFP')),
    settlement_mode text NOT NULL CHECK (settlement_mode IN ('OFFLINE', 'NO_PAYMENT')),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    busy_until timestamptz NOT NULL,
    buffer_minutes integer NOT NULL,
    zone_id text NOT NULL,
    expires_at timestamptz NOT NULL,
    meeting_area varchar(200) NOT NULL,
    note varchar(1000) NOT NULL DEFAULT '',
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONFIRMED', 'AWAITING_DELIVERY',
        'DELIVERED', 'COMPLETED', 'REJECTED', 'WITHDRAWN', 'CONFLICT_CLOSED', 'EXPIRED', 'CANCELLED')),
    version integer NOT NULL DEFAULT 1,
    cancellation_reason varchar(80),
    customer_shoot_at timestamptz,
    photographer_shoot_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at AND busy_until >= ends_at),
    CHECK (customer_id <> photographer_account_id),
    EXCLUDE USING gist (photographer_id WITH =, tstzrange(starts_at, busy_until, '[)') WITH &&)
        WHERE (status IN ('CONFIRMED', 'AWAITING_DELIVERY', 'DELIVERED', 'COMPLETED'))
);
CREATE INDEX bookings_customer_history ON photograph_app.bookings (customer_id, id);
CREATE INDEX bookings_photographer_history ON photograph_app.bookings (photographer_account_id, id);
CREATE INDEX bookings_due ON photograph_app.bookings (expires_at) WHERE status = 'PENDING';
CREATE INDEX bookings_pending_by_photographer ON photograph_app.bookings (photographer_id) WHERE status = 'PENDING';

CREATE TABLE photograph_app.booking_reschedules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL REFERENCES photograph_app.bookings (id),
    proposed_by uuid NOT NULL REFERENCES photograph_app.accounts (id),
    starts_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CLOSED')),
    decided_at timestamptz
);
CREATE UNIQUE INDEX booking_open_reschedule ON photograph_app.booking_reschedules (booking_id) WHERE status = 'PENDING';

CREATE TABLE photograph_app.booking_requests (
    account_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    scope varchar(150) NOT NULL,
    request_key varchar(120) NOT NULL,
    request_digest char(64) NOT NULL,
    result jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, scope, request_key)
);

CREATE TABLE photograph_app.booking_notifications (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    booking_id uuid NOT NULL REFERENCES photograph_app.bookings (id),
    event_type text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX booking_notifications_recipient ON photograph_app.booking_notifications (account_id, id);
