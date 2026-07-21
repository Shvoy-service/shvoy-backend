-- No users table existed yet (this is the first story to need one), so this
-- migration creates it directly with `role` included from the start, rather
-- than creating an empty table and then altering it to add the column.
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    role VARCHAR(20) NOT NULL
);
