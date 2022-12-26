CREATE TABLE IF NOT EXISTS clients (
    'id' BLOB NOT NULL,
    'ip' BLOB NOT NULL,
    'signature' BLOB NOT NULL,
    'port' INTEGER,
    'priority' INTEGER,
    'client' INTEGER,
    'timestamp' INTEGER,
    'ttl' INTEGER
);
CREATE INDEX IF NOT EXISTS id_index ON clients (id);