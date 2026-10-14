"""Generate private local credentials and headless Langfuse initialization."""
from pathlib import Path
import secrets

target = Path(__file__).resolve().parents[1] / ".env.langfuse"
if target.exists():
    print("Existing .env.langfuse preserved.")
else:
    postgres, minio = secrets.token_hex(24), secrets.token_hex(24)
    public, secret = "pk-lf-"+secrets.token_hex(16), "sk-lf-"+secrets.token_hex(24)
    values = {
        "POSTGRES_PASSWORD": postgres, "DATABASE_URL": f"postgresql://postgres:{postgres}@postgres:5432/postgres",
        "SALT": secrets.token_hex(24), "ENCRYPTION_KEY": secrets.token_hex(32),
        "NEXTAUTH_SECRET": secrets.token_hex(32), "REDIS_AUTH": secrets.token_hex(24),
        "CLICKHOUSE_PASSWORD": secrets.token_hex(24), "MINIO_ROOT_PASSWORD": minio,
        "LANGFUSE_S3_EVENT_UPLOAD_SECRET_ACCESS_KEY": minio,
        "LANGFUSE_S3_MEDIA_UPLOAD_SECRET_ACCESS_KEY": minio,
        "LANGFUSE_S3_BATCH_EXPORT_SECRET_ACCESS_KEY": minio,
        "LANGFUSE_INIT_ORG_ID": "agent-mvp-org", "LANGFUSE_INIT_ORG_NAME": "Local Agent MVP",
        "LANGFUSE_INIT_PROJECT_ID": "agent-mvp-project", "LANGFUSE_INIT_PROJECT_NAME": "Video Agent",
        "LANGFUSE_INIT_PROJECT_PUBLIC_KEY": public, "LANGFUSE_INIT_PROJECT_SECRET_KEY": secret,
        "LANGFUSE_INIT_USER_EMAIL": "agent@localhost.test", "LANGFUSE_INIT_USER_NAME": "Local Developer",
        "LANGFUSE_INIT_USER_PASSWORD": secrets.token_hex(20),
        "LANGFUSE_PUBLIC_KEY": public, "LANGFUSE_SECRET_KEY": secret,
        "LANGFUSE_BASE_URL": "http://localhost:3035",
    }
    target.write_text("\n".join(f"{key}={value}" for key,value in values.items())+"\n")
    target.chmod(0o600)
    print("Private .env.langfuse created; credentials were not printed.")
