### What this tool do
This tool will find all `@CircuitBreaker` annotated method and its corresponding endpoint(mix up if endpoint have multiple circuit breaker method underhood). **This tool only works with spring web and resilience4j.**

### How to run
* clone this repo to local
* generate target source code `./gradlew myDelombok --path="your project source path"` path is the source files (java files) directory
* scan target source code: `./gradlew run`
* get result at `./result.json`, enjoy
