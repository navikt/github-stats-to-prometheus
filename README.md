sif-github-stats
================

Naisjob som henter statistikk fra github og pusher til prometheus.

Bruker nais/google secret  med navnet `sif-stats-github-pat` for k9saksbehandling.

Secreten har 1 års varighet og trenger permissions: 

```
repo (Full control of private repositories — nødvendig for å lese pull requests og innhold i interne/private repoer)
read:org
```

> **Merk:** Det fulle `repo`-scopet er påkrevd fordi GitHub ikke tilbyr et granulært "read-only" scope for private/interne repoer med classic PATs. Uten dette scopet returnerer API-kall mot interne repoer 404. Alternativt kan en fine-grained PAT brukes med kun leserettigheter på spesifikke repoer.

---

# Henvendelser

Enten:
Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub

## For NAV-ansatte

Interne henvendelser kan sendes via Slack i kanalen #sif_saksbehandling_tech
  

# Lokal testing av Main.kt
Sett env variabler:
sif-stats-github-pat=din_PAT
GITHUB_API_URL=https://api.github.com/
PUSH_GATEWAY_ADDRESS=dummy