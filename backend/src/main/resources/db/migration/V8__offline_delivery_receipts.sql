ALTER TABLE photograph_app.bookings
    ADD COLUMN delivered_at timestamptz,
    ADD COLUMN completed_at timestamptz;
