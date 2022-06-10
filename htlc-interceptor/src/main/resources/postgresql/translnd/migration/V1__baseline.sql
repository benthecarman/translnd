CREATE TABLE invoices
(
    hash           TEXT PRIMARY KEY NOT NULL,
    preimage       TEXT UNIQUE      NOT NULL,
    payment_secret TEXT UNIQUE      NOT NULL,
    amount         INTEGER,
    invoice        TEXT UNIQUE      NOT NULL,
    chan_id        BIGINT           NOT NULL,
    idx            INTEGER UNIQUE   NOT NULL,
    settled        INTEGER          NOT NULL
);
