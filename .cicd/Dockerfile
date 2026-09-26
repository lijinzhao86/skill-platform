FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    SKILL_PLATFORM_DB=/opt/data/skill-platform/skill_platform.db

WORKDIR /app

COPY pyproject.toml README.md ./
COPY skill_platform/ ./skill_platform/
RUN pip install --no-cache-dir .

# The CLI writes its SQLite store to SKILL_PLATFORM_DB, so the directory has to
# exist and be writable by the unprivileged user the container runs as.
RUN useradd --create-home --uid 10001 skill \
    && mkdir -p /opt/data/skill-platform \
    && chown -R skill:skill /opt/data
USER skill

ENTRYPOINT ["skill-platform"]
