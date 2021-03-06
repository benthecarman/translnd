CREATE TABLE invoices
(
    hash           TEXT PRIMARY KEY NOT NULL,
    preimage       TEXT UNIQUE      NOT NULL,
    payment_secret TEXT UNIQUE      NOT NULL,
    amount         INTEGER,
    amount_paid    INTEGER,
    expire_time    INTEGER,
    invoice        TEXT UNIQUE      NOT NULL,
    idx            INTEGER UNIQUE   NOT NULL,
    state          TEXT             NOT NULL
);

CREATE TABLE channel_ids
(
    id   SERIAL PRIMARY KEY NOT NULL,
    hash TEXT               NOT NULL,
    scid BIGINT             NOT NULL
);
