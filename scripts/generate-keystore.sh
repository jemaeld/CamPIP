#!/usr/bin/env bash
# Genera la llave de firma de TV Cam PiP y muestra los 4 valores que van en los
# Secrets de GitHub (Settings > Secrets and variables > Actions > New repository secret).
#
# Uso:  ./scripts/generate-keystore.sh [archivo.jks]
#
# IMPORTANTE: guarda el archivo .jks y la contraseña en un lugar seguro (gestor de contraseñas,
# respaldo). Si se pierde, ya no se podrá actualizar la app instalada: habría que desinstalarla.
# Nunca subas el .jks al repositorio (ya está en .gitignore).
set -euo pipefail

OUT="${1:-tvcampip-release.jks}"
ALIAS="tvcampip"

if [ -e "$OUT" ]; then
  echo "Ya existe $OUT. Bórralo o elige otro nombre; no se sobrescribe una llave existente." >&2
  exit 1
fi

command -v keytool >/dev/null || { echo "Falta keytool (instala un JDK 17)." >&2; exit 1; }

PASS="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"

keytool -genkeypair \
  -keystore "$OUT" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=TV Cam PiP, O=Personal, C=MX" >/dev/null 2>&1

B64="$(base64 -w0 "$OUT" 2>/dev/null || base64 "$OUT" | tr -d '\n')"

cat <<EOF

Llave creada: $OUT

Crea estos 4 secrets en GitHub (repo jemaeld/CamPIP):

  KEYSTORE_BASE64    = (el texto largo de abajo)
  KEYSTORE_PASSWORD  = $PASS
  KEY_ALIAS          = $ALIAS
  KEY_PASSWORD       = $PASS

KEYSTORE_BASE64:
$B64

Guarda $OUT y la contraseña en un lugar seguro.
EOF
