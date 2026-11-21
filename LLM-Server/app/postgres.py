import os

import psycopg


def connect(**kwargs):
    return psycopg.connect(
        host=os.getenv("POSTGRES_HOST", "localhost"),
        port=int(os.getenv("POSTGRES_PORT", "5432")),
        user=os.getenv("POSTGRES_USER", "dcba"),
        password=os.environ["POSTGRES_PASSWORD"],
        dbname=os.getenv("POSTGRES_DB", "dcba"),
        connect_timeout=5,
        options="-c statement_timeout=30000",
        **kwargs,
    )
