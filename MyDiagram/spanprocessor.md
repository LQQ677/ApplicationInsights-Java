```mermaid
graph TD
A[spanProcessor] --> |names|B(span include)
A --> |names|C(span exclude)
A --> D(new span)
D --> E(fromAttributes)
D --> F(toAttributes/replace)
```
