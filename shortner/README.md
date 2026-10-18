# Shortner

## Run locally

Use Java 21 or newer with the Java 21 toolchain installed.

```sh
docker compose up -d
./gradlew copyEnvDev
./gradlew bootRunDev
```

Edit the repository-root `.env.dev` if your local settings differ from the
Compose defaults. `bootRunDev` loads that file and activates the `local` profile.
For production-like settings, create repository-root `.env.prod` from
`.env.example`, replace every local value, and run `./gradlew bootRunProd`.
Keep both env files out of version control.

Snowflake IDs require an explicit datacenter ID and a hostname-to-machine-ID
allocation. Each datacenter has 32 machine slots (0–31); assign each live
hostname a different slot and keep that allocation stable for the lifetime of
the instance. Set `SNOWFLAKE_HOSTNAME`, `SNOWFLAKE_DATACENTER_ID`, and
`SNOWFLAKE_MACHINE_IDS` in each deployment. `SNOWFLAKE_MACHINE_IDS` is a
comma-separated list of `hostname=id` entries, for example
`shortner-0=0,shortner-1=1`. Startup fails if the local hostname is missing,
the configuration is invalid, or multiple hostnames share a slot.

## API documentation

With the application running on its default port:

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

Swagger UI automatically documents the controllers, including `POST /generate`
and `GET /{shortCode}`. Use **Try it out** on `POST /generate` to create a short URL.

### Testing redirects

Open the generated short URL directly in your browser to follow its redirect.
`GET /{shortCode}` returns **302 Found** with the original URL in the `Location`
header. Swagger UI follows redirects using a browser fetch request, so an external
destination that does not allow cross-origin reads can cause a CORS error in
**Try it out**. Adding CORS headers to this application cannot grant access to
the destination website's response.

To inspect the redirect without following it, replace `YOUR_SHORT_CODE` below:

```sh
curl -i http://localhost:8080/YOUR_SHORT_CODE
```

## Redis cache

URL lookups use cache-aside: check Redis first, query PostgreSQL on a miss, and
cache the returned URL for 30 minutes. Creating a URL writes to PostgreSQL only.
Keys use `shortner:v1:urls::<shortCode>` and values are plain URL strings.
Reads do not refresh the TTL. Redis failures are logged and lookups fall back
to PostgreSQL; cache write failures do not prevent redirects.

Local Redis is exposed on port 6380. Set `REDIS_PASSWORD` when starting the
shortener; use an empty value for the local Compose Redis. Production must set
`REDIS_PASSWORD` through its secret store. Compose limits Redis cache memory to
256 MB and uses `allkeys-lfu` to evict less frequently used entries under memory
pressure. PostgreSQL remains the durable source of truth.

Run `./gradlew test` to check application startup and cache hit, miss, failure,
and missing/invalid URL behavior. The application startup test needs PostgreSQL.

The shortener requires `DB_USERNAME`, `DB_PASSWORD`, and `REDIS_PASSWORD` at
startup. The Compose-only local database credentials are `s_user` and
`s_password`; they must not be used in production. Actuator exposes only
`health`, `info`, and `metrics` on port 8082, bound to `127.0.0.1` by default.
For remote monitoring, set `MANAGEMENT_BIND_ADDRESS` to a private interface
address and restrict the port to the private monitoring network.
