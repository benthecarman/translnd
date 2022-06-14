CREATE TABLE invoices
(
    hash           VARCHAR(254) PRIMARY KEY NOT NULL,
    preimage       VARCHAR(254) UNIQUE      NOT NULL,
    payment_secret VARCHAR(254) UNIQUE      NOT NULL,
    amount         INTEGER,
    expire_time    INTEGER,
    invoice        VARCHAR(254) UNIQUE      NOT NULL,
    idx            INTEGER UNIQUE           NOT NULL,
    expired        INTEGER                  NOT NULL,
    settled        INTEGER                  NOT NULL
);

CREATE TABLE channel_ids
(
    id   INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    hash VARCHAR(254) NOT NULL,
    scid INTEGER      NOT NULL
);
