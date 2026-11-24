CREATE TABLE photograph_app.accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    nickname varchar(80) NOT NULL CHECK (btrim(nickname) <> ''),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE photograph_app.photographer_profiles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL UNIQUE REFERENCES photograph_app.accounts (id),
    city varchar(100) NOT NULL CHECK (btrim(city) <> ''),
    bio varchar(2000) NOT NULL DEFAULT '',
    approval_status text NOT NULL DEFAULT 'PENDING'
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED'))
);

CREATE TABLE photograph_app.photographer_styles (
    photographer_id uuid NOT NULL REFERENCES photograph_app.photographer_profiles (id),
    style_id text NOT NULL REFERENCES photograph_app.styles (id),
    PRIMARY KEY (photographer_id, style_id)
);

CREATE TABLE photograph_app.photography_packages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    photographer_id uuid NOT NULL REFERENCES photograph_app.photographer_profiles (id),
    name varchar(120) NOT NULL CHECK (btrim(name) <> ''),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    duration_minutes integer NOT NULL CHECK (duration_minutes > 0),
    retouched_count integer NOT NULL CHECK (retouched_count >= 0),
    delivery_days integer NOT NULL CHECK (delivery_days > 0),
    price_minor integer NOT NULL CHECK (price_minor >= 0),
    currency text NOT NULL DEFAULT 'CNY' CHECK (currency = 'CNY'),
    is_listed boolean NOT NULL DEFAULT false
);

CREATE INDEX photography_packages_public_lookup
    ON photograph_app.photography_packages (photographer_id, id)
    WHERE is_listed;
