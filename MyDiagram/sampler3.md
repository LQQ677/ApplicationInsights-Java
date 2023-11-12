```mermaid
graph LR

A[attribute] --> C{match?}
B[samplingOverride.attributes] --> C
C --> |yes|D[override.percentage]
D --> E[fixSamplingPercentage]

```
