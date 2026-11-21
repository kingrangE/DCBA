\set ON_ERROR_STOP on
\getenv app_user POSTGRES_APP_USER
\getenv app_password POSTGRES_APP_PASSWORD
\getenv app_db POSTGRES_DB
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', :'app_user', :'app_password') \gexec
SELECT format('ALTER DATABASE %I OWNER TO %I', :'app_db', :'app_user') \gexec
