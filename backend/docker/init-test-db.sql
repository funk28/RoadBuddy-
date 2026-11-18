-- A separate database for pytest, so tests never wipe local dev data.
CREATE DATABASE roadbuddy_test;
\connect roadbuddy_test
CREATE EXTENSION IF NOT EXISTS postgis;
\connect roadbuddy
CREATE EXTENSION IF NOT EXISTS postgis;
