#!/bin/sh
# The cloud image accepts configuration through documented environment variables only.
# The ordinary JAR/local launcher retain their SQLite and loopback defaults.
set -eu

fail() {
    printf '%s\n' "Black Box cloud startup refused: $1" >&2
    exit 64
}

[ "$#" -eq 0 ] || fail 'command arguments are unsupported; use the documented environment variables'
[ "${SPRING_PROFILES_ACTIVE:-}" = postgres ] || fail 'SPRING_PROFILES_ACTIVE must be postgres'
[ "${SBA_AUTH_ENABLED:-}" = true ] || fail 'SBA_AUTH_ENABLED must be true'
[ "${SBA_AUTH_SECURE_COOKIES-true}" = true ] || fail 'SBA_AUTH_SECURE_COOKIES must remain true'
case "${SBA_DATASOURCE_URL:-}" in
    jdbc:postgresql://*/*) ;;
    *) fail 'SBA_DATASOURCE_URL must be an explicit PostgreSQL JDBC URL' ;;
esac
case "$SBA_DATASOURCE_URL" in
    *[[:space:]]*) fail 'SBA_DATASOURCE_URL must not contain whitespace' ;;
esac
[ -n "${SBA_DATASOURCE_USERNAME:-}" ] || fail 'SBA_DATASOURCE_USERNAME is required'
[ -n "${SBA_DATASOURCE_PASSWORD:-}" ] || fail 'SBA_DATASOURCE_PASSWORD is required'
[ -n "${SBA_AUTH_PASSWORD:-}" ] || fail 'SBA_AUTH_PASSWORD is required'
[ -n "${SBA_AUTH_API_TOKEN:-}" ] || fail 'SBA_AUTH_API_TOKEN is required'
[ "${#SBA_AUTH_PASSWORD}" -ge 32 ] || fail 'SBA_AUTH_PASSWORD must contain at least 32 characters'
[ "${#SBA_AUTH_API_TOKEN}" -ge 32 ] || fail 'SBA_AUTH_API_TOKEN must contain at least 32 characters'
[ "$SBA_AUTH_PASSWORD" != "$SBA_AUTH_API_TOKEN" ] || fail 'browser password and API token must be different'
case "$SBA_AUTH_PASSWORD$SBA_AUTH_API_TOKEN" in
    *[[:space:]]*) fail 'authentication secrets must not contain whitespace' ;;
esac

# These channels can override the validated environment (or execute JVM agents). Reject them
# rather than accepting a configuration that says one thing while Spring/Java uses another.
[ -z "${JAVA_TOOL_OPTIONS:-}${JDK_JAVA_OPTIONS:-}${_JAVA_OPTIONS:-}" ] || fail 'Java option environment overrides are unsupported'
if env | grep -Eiq '^spring[._](application[._]json|config([._]|=))'; then
    fail 'Spring JSON and external configuration overrides are unsupported'
fi
if env | grep -Ei '^spring[._]profiles([._]|=)' | grep -vq '^SPRING_PROFILES_ACTIVE=postgres$'; then
    fail 'additional Spring profile selectors are unsupported'
fi
# Boot binds Hikari properties after base datasource properties; even a different pool JDBC URL,
# datasource class or JNDI name would bypass checks of the SBA URL alone. Reject the full competing
# namespace, including exact dotted names and Spring's relaxed underscore/case spelling variants.
if env | grep -Eiq '^spring[._]datasource([._]|=)'; then
    fail 'configure the datasource only through SBA_DATASOURCE variables'
fi
[ -z "${SBA_STORAGE_BACKEND:-}" ] || fail 'SBA_STORAGE_BACKEND is selected by the postgres profile'

# AuthSettings performs the full secret-quality validation before serving. These final fixed
# non-secret arguments also pin the advertised storage/auth contract at Spring's highest priority.
exec java -Xms128m -Xmx512m -Xss512k -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m \
    -XX:+ExitOnOutOfMemoryError -jar /app/app.jar \
    --spring.config.location=classpath:/application.yml \
    --spring.profiles.active=postgres --spring.datasource.driver-class-name=org.postgresql.Driver \
    --sba.storage.backend=postgres --SBA_AUTH_ENABLED=true --SBA_AUTH_SECURE_COOKIES=true
