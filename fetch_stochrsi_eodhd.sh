#!/bin/bash

YOUR_API_TOKEN=6a7067510fd1b9.87881807
curl --location "https://eodhd.com/api/technical/AAPL.US?function=stochrsi&fast_kperiod=14&fast_dperiod=14&api_token=${YOUR_API_TOKEN}&fmt=json"