CREATE TABLE photograph_app.conversations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    first_account_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    second_account_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    last_seq bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (first_account_id, second_account_id),
    CHECK (first_account_id < second_account_id)
);

CREATE INDEX conversations_first_inbox ON photograph_app.conversations (first_account_id, updated_at DESC, id DESC);
CREATE INDEX conversations_second_inbox ON photograph_app.conversations (second_account_id, updated_at DESC, id DESC);

CREATE TABLE photograph_app.conversation_messages (
    conversation_id uuid NOT NULL REFERENCES photograph_app.conversations (id),
    seq bigint NOT NULL,
    sender_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    client_message_id uuid NOT NULL,
    text varchar(2000) NOT NULL CHECK (btrim(text) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, seq),
    UNIQUE (conversation_id, sender_id, client_message_id)
);

CREATE TABLE photograph_app.conversation_reads (
    conversation_id uuid NOT NULL REFERENCES photograph_app.conversations (id),
    account_id uuid NOT NULL REFERENCES photograph_app.accounts (id),
    read_seq bigint NOT NULL CHECK (read_seq > 0),
    PRIMARY KEY (conversation_id, account_id)
);
