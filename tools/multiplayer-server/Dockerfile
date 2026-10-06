FROM python:3.12-alpine
WORKDIR /app
COPY acgc_relay.py .
ENV ACGC_DATA=/data ACGC_PORT=8765
VOLUME /data
EXPOSE 8765
CMD ["python3", "-u", "acgc_relay.py"]
