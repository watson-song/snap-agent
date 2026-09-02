---
name: trend-prediction
description: "Predicts trends from historical metrics and warns about potential issues. Queries 7-day metric trends, detects if current growth rate is unsustainable, estimates time-to-threshold, and generates early warnings. Use for proactive capacity planning."
tools: [metrics_query]
inputs:
  - key: metric
    label: "PromQL expression for the metric to analyze"
    required: true
---

# Trend Prediction

## Step 1: Query 7-Day Trend
Use the `metrics_query` tool to query the metric `{metric}` over a 7-day range to understand the growth pattern.

## Step 2: Detect Growth Rate
Analyze the trend data:
- Calculate the average daily growth rate
- Determine if the growth is linear, exponential, or stable
- Identify any recent acceleration

## Step 3: Estimate Time-to-Threshold
Based on the growth rate:
- Estimate when the metric will reach a critical threshold
- Flag if time-to-threshold is less than 7 days
- Note any inflection points in the trend

## Step 4: Generate Early Warning
Output a prediction summary:
- Current value and trend direction
- Projected time-to-threshold (if applicable)
- Confidence level of the prediction
- Recommended actions (scale up, investigate, monitor)
