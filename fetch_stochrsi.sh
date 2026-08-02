#!/usr/bin/env bash
#
# Fetches StochRSI data for XRP/USDT (1d) from TAAPI.io and appends it to a CSV file.
# Each CSV row is: date,exchange,symbol,interval,index,valueFastK,valueFastD
#
# Usage:
#   ./fetch_stochrsi.sh
#
set -euo pipefail

# TAAPI API key (from application.properties)
SECRET="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJjbHVlIjoiNmE2YTU2NzkxZGMzM2EyOTU0YWU2NjE2IiwiaWF0IjoxNzg1MzU0MTI4LCJleHAiOjMzMjg5ODE4MTI4fQ.jgOEGX38Xeol9RVEpZqt61-bilL3ydc31JTMnu0Sekc"

# Current date (first column)
DATE="$(date +%F)"

EXCHANGE="binance"
SYMBOL="XRP/USDT"
INTERVAL="1d"
RESULTS="100"
FILE="stochrsi${DATE}.csv"

# URL-encode the symbol's "/" as "%2F"
SYMBOL_ENCODED="${SYMBOL//\//%2F}"

# Write a header only when the file does not exist yet
[ -f "$FILE" ] || echo "date,exchange,symbol,interval,index,valueFastK,valueFastD" > "$FILE"

# Fetch and append the data
curl -s "https://api.taapi.io/stochrsi?secret=${SECRET}&exchange=${EXCHANGE}&symbol=${SYMBOL_ENCODED}&interval=${INTERVAL}&results=${RESULTS}" \
  | jq -r --arg date "$DATE" --arg exchange "$EXCHANGE" --arg symbol "$SYMBOL" --arg interval "$INTERVAL" 'range(0; (.valueFastK|length)) as $i | [$date, $exchange, $symbol, $interval, $i, .valueFastK[$i], .valueFastD[$i]] | @csv' \
  >> "$FILE"

echo "Appended StochRSI data for ${SYMBOL} (${INTERVAL}) to ${FILE}"
