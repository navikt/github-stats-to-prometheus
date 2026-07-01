FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25

WORKDIR /app

COPY --chmod=755 build/install/app/ /app/

ENTRYPOINT ["java", "-cp", "/app/lib/*", "no.nav.github_stats.MainKt"]
