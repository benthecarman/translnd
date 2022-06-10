CREATE TABLE invoices
(
    hash           VARCHAR(254) PRIMARY KEY NOT NULL,
    preimage       VARCHAR(254) UNIQUE      NOT NULL,
    payment_secret VARCHAR(254) UNIQUE      NOT NULL,
    amount         INTEGER,
    invoice        VARCHAR(254) UNIQUE      NOT NULL,
    chan_id        INTEGER                  NOT NULL,
    idx            INTEGER UNIQUE           NOT NULL,
    settled        INTEGER                  NOT NULL
);
