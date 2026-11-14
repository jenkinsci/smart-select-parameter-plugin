# Usage examples

[Detailed English guide](../docs/usage-examples.md) · [中文详细指南](../docs/usage-examples.zh-CN.md)

Each Jenkinsfile is a complete Pipeline. The examples only print selected values. Pipeline plugins are required to run Jenkinsfiles; Freestyle jobs can configure the parameter directly without Pipeline.

| File | Scenario | Prerequisites | Example result |
| --- | --- | --- | --- |
| [Jenkinsfile-single](Jenkinsfile-single) | One environment | Plugin + Pipeline | `ENV=pre` |
| [Jenkinsfile-multi](Jenkinsfile-multi) | Several services; handle empty selections | Plugin + Pipeline | Two messages, for `price` and `vehicle` |
| [Jenkinsfile-labels](Jenkinsfile-labels) | Friendly labels, stable values | Plugin + Pipeline | `SERVICES=price` |
| [Jenkinsfile-scripted](Jenkinsfile-scripted) | Scripted Pipeline | Preserve any other job properties in `properties(...)` | `ENV=pre; SERVICES=` |
| [Jenkinsfile-rest-independent](Jenkinsfile-rest-independent) | Root JSON array | Replace URL; authorize origin; implement endpoint | Selected values such as `price,vehicle` |
| [Jenkinsfile-rest-nested](Jenkinsfile-rest-nested) | Nested label/value JSON | Same; endpoint must return `data.items` | Selected values, not labels |
| [Jenkinsfile-rest](Jenkinsfile-rest) | Environment → services | Same; endpoint accepts `env` | Values allowed for the chosen environment |
| [Jenkinsfile-two-parents](Jenkinsfile-two-parents) | Environment + region → services | Same; endpoint accepts `env` and `region` | Values allowed for both parents |
| [Jenkinsfile-cascade](Jenkinsfile-cascade) | Environment → project → services | Same; implement both endpoints | Values allowed for the whole chain |
| [Jenkinsfile-static-cascade](Jenkinsfile-static-cascade) | Environment → services from a Groovy map | Plugin + Pipeline | `ENV=dev; SERVICE=service-a` |
| [Jenkinsfile-static-two-parents](Jenkinsfile-static-two-parents) | Environment + region → cluster from a Groovy map | Plugin + Pipeline | `ENV=dev; REGION=us-east; CLUSTER=cluster-dev-use1-a` |
| [Jenkinsfile-static-labels](Jenkinsfile-static-labels) | Friendly labels with stable values from a Groovy map | Plugin + Pipeline | `ENV=dev; SERVICE=svc-pricing` |
| [Jenkinsfile](Jenkinsfile) | Original combined static example | Plugin + Pipeline | Used by the existing integration test |

For a new Pipeline job, register parameters by running the selected Jenkinsfile once. Refresh the job page, choose **Build with Parameters**, then submit the desired values. These demonstration scripts do not deploy anything. Remote examples need working endpoints and authorized origins even during registration; dependent parameters have no context-free child defaults. Normal Pipeline side effects still execute on a registration run if you add them yourself.

Use `defaultValues` for values such as `price`, never for labels such as `Quote service`. `params.SERVICES` is a comma-separated **string**, not a list; see the multi-select example for conversion. If you later replace an example with a deployment script, enforce any required nonempty selection before deployment.
