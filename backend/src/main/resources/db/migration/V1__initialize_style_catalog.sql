CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;

CREATE TABLE photograph_app.styles (
    id text PRIMARY KEY,
    name text NOT NULL UNIQUE CHECK (btrim(name) <> ''),
    display_order integer NOT NULL CHECK (display_order >= 0)
);

INSERT INTO photograph_app.styles (id, name, display_order) VALUES
    ('style_natural_light', '自然光', 10),
    ('style_documentary', '纪实', 20),
    ('style_street', '街拍', 30),
    ('style_retro', '复古', 40);
