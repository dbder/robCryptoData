#!/bin/bash

# Define input and output files
INPUT_CSV="input.csv"
OUTPUT_CSV="stochrsi_$(date +%F).csv"


# TAAPI API key (from application.properties)
SECRET="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJjbHVlIjoiNmE2YTU2NzkxZGMzM2EyOTU0YWU2NjE2IiwiaWF0IjoxNzg1MzU0MTI4LCJleHAiOjMzMjg5ODE4MTI4fQ.jgOEGX38Xeol9RVEpZqt61-bilL3ydc31JTMnu0Sekc"


# Define retry parameters
RETRIES=3
DELAY=16

# Function to fetch data with retries
fetch_data_with_retries() {
  local exchange=$1
  local indicator=$2
  local interval=$3
  local symbol=$4
  local retry_count=0

  while [ $retry_count -lt $RETRIES ]; do
    request_url="https://api.taapi.io/$indicator?secret=${SECRET}&exchange=$exchange&symbol=${symbol}&interval=$interval"

    echo "Request URL: $request_url"
    response=$(curl -s "$request_url")

    if echo "$response" | jq . > /dev/null 2>&1; then
      return 0
    fi

    echo "Failed to fetch data for ${symbol} (${interval}) with indicator ${indicator}. Retrying..."

    sleep $DELAY
    retry_count=$((retry_count + 1))
  done

  return 1
}

# Write a header only when the file does not exist yet
[ -f "$OUTPUT_CSV" ] || echo "date,exchange,symbol,interval,index,valueFastK,valueFastD" > "$OUTPUT_CSV"

# Read each row from the input CSV and process it
while IFS=',' read -r exchange indicator interval symbol; do
  echo "start ${exchange}"

  fetch_data_with_retries "$exchange" "$indicator" "$interval" "$symbol"

  if [ $? -eq 0 ]; then
    request_url="https://api.taapi.io/$indicator?exchange=$exchange&symbol=${symbol}&interval=$interval&secret=$SECRET"

    echo "Request URL: $request_url"

    response=$(curl -s "$request_url")
    echo $response

    jq -r --arg date "$(date +%F)" --arg exchange "$exchange" --arg symbol "$symbol" --arg interval "$interval" 'range(0; (.valueFastK|length)) as $i | [$date, $exchange, $symbol, $interval, $i, .valueFastK[$i], .valueFastD[$i]] | @csv' \
      <<< "$response" >> "$OUTPUT_CSV"

    echo "Appended data for ${symbol} (${interval}) with indicator ${indicator}"
  else
    echo "Failed to fetch data for ${symbol} (${interval}) with indicator ${indicator}. All retries failed."
  fi

  sleep $DELAY
done < "$INPUT_CSV"

echo "Appended StochRSI and RSI data to $OUTPUT_CSV"
