-- Creates the database and user the backend's `local` profile expects, on the
-- already-running local MySQL 8.0 (service MySQL80, port 3306).
--
-- The `local` Spring profile (application.yml) points at
--   jdbc:mysql://localhost:3306/demand_forecast
-- and inherits username `supply` / password `supplypass` from the main datasource
-- block. These names are fixed by that config, not chosen here.
--
-- Host is 'localhost', not '%': a probe connection to 127.0.0.1:3306 was reported
-- back by the server as 'supply'@'localhost', so name resolution is on and this is
-- the host pattern JDBC's localhost connection is matched against.
--
-- Run it (you will be prompted for the MySQL root password — it is never passed to
-- Claude):
--   ! "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p < "E:\NEW SUPPLY\scripts\mysql-local-setup.sql"

CREATE DATABASE IF NOT EXISTS demand_forecast
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'supply'@'localhost' IDENTIFIED BY 'supplypass';

GRANT ALL PRIVILEGES ON demand_forecast.* TO 'supply'@'localhost';

FLUSH PRIVILEGES;

SELECT 'setup complete' AS status,
       (SELECT COUNT(*) FROM information_schema.schemata
        WHERE schema_name = 'demand_forecast') AS db_exists;
