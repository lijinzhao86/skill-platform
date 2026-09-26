FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    SKILL_PLATFORM_DB=/opt/data/skill-platform/skill_platform.db

WORKDIR /app

COPY skill-platform-server/ ./skill-platform-server/
RUN pip install --no-cache-dir ./skill-platform-server

# The gateway skill is served from the API (/.well-known/... and /gateway/SKILL.md),
# so the image has to carry its source.
COPY gateway/ ./gateway/

# Nothing in the image reads SKILL_PLATFORM_DB yet — the code that did went with the
# baseline CLI. The variable and this directory are the contract the P0 server has to
# honour: its SQLite path, in a directory the unprivileged user can write.
RUN useradd --create-home --uid 10001 skill \
    && mkdir -p /opt/data/skill-platform \
    && chown -R skill:skill /opt/data
USER skill

# The server has no ASGI app yet, so this entry point fails at run time with a
# clear "no module named skill_platform_server.__main__" instead of silently
# dropping into a REPL. It becomes the real command once P0 supplies the app.
CMD ["python", "-m", "skill_platform_server"]
