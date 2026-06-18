#!/usr/bin/env bash

set -Eeuo pipefail

port="${SERVER_PORT:-8080}"

exec 3<>"/dev/tcp/127.0.0.1/${port}"

printf \
  'GET /readyz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' \
  >&3

IFS= read -r status_line <&3

[[ "${status_line}" == *" 200 "* ]]