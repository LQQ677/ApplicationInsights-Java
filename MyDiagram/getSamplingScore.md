```mermaid
graph LR
A[traceId] --> B{length < 8}
B --> |yes|C(append cycle)
C --> D(opId)
B --> |no|D
D --> |iter|E(aa)
E --> F(score/max_value)
```
