FROM python:3.13-slim@sha256:70729b46c69b4f1e97c4822c1af3df53a1476cf5ddc6c087c0c10bc3a5678c2f
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY agent-service/requirements.lock ./requirements.lock
RUN pip install --no-cache-dir -r requirements.lock && useradd --uid 10001 --create-home agent && mkdir /state && chown agent:agent /state
COPY agent-service/agent_service ./agent_service
USER agent
ENV AGENT_DATA_DIRECTORY=/state
ENTRYPOINT ["python", "-m", "agent_service"]
CMD ["--help"]
