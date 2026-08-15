FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# System deps required by prophet (holidays, iso8601) and lightgbm-family build wheels
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential curl \
 && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# The prophet 1.1.6 wheels ship a prebuilt cmdstan tree but omit the `makefile`
# that cmdstanpy validates the install path against, so the Stan backend fails to
# load and the service quietly drops Prophet from the candidate pool. The model
# binary itself is fine; only that sentinel file is missing. Creating it is a
# no-op on any wheel that already ships one.
RUN python -c "import pathlib, prophet; [(d / 'makefile').touch() for d in (pathlib.Path(prophet.__file__).parent / 'stan_model').glob('cmdstan-*')]" \
 && python -c "from prophet import Prophet; Prophet(); print('prophet stan backend OK')"

COPY . .

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
