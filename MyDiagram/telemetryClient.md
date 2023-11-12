```mermaid
graph TD
A[TelemetryClient] --> |trackAsync|C(TelemetryItem)
B1[span/logs/metrics] --> C

C --> D{isMetricData?}
D --> |Yes|E(MetricBatchItemProcessor)
D --> |No|F(GeneralBatchItemProcessor)

E --> G(queue.offer)
F --> G

H(BatchItemProcessor) --> |init|I(httpPipeline)
I --> I1(telemetryPipeline)
I --> I2(telemetryPipelineListener)
I1 --> I3(telemetryExporter)
I2 --> I3
I3 --> J(byteBufers)
G --> |encode|J
J --> |conectionString|J1(send request)

K1(httpClient) -.-> I
K2(httpPipelinePolicy) -.-> I


```
