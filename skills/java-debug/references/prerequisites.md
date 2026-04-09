# JDWP Launch Prerequisites

Build-system-specific details for launching JVMs with the JDWP debug agent.

## Maven Surefire Tests

```bash
mvn test -Dtest=<TestClass> -Dmaven.surefire.debug
```

This forks the test JVM with the JDWP agent on port 5005, `suspend=y`.

**Surefire `<argLine>` gotcha:** Surefire poms that hardcode `<argLine>` will silently override `-DargLine=` from the command line. If `-Dmaven.surefire.debug` doesn't open port 5005, change the pom's `<argLine>X</argLine>` to `<argLine>X ${argLine}</argLine>` and add `<argLine></argLine>` to `<properties>` as the empty default.

## Gradle Tests

```bash
./gradlew test --tests "com.example.MyTest" --debug-jvm
```

`--debug-jvm` enables JDWP on port 5005 with `suspend=y` and disables the test timeout. Works with `test`, `integrationTest`, or any `Test`-typed task. If `maxParallelForks > 1`, only the first worker gets the port.

For manual control over the agent string (different port, `suspend=n`, or specific test tasks):

```kotlin
tasks.test {
    jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005")
    timeout.set(Duration.ofHours(1))  // Required when suspending
}
```

## Gradle — Running the Application

Spring Boot: `./gradlew bootRun --debug-jvm`
Application plugin: `./gradlew run --debug-jvm`

Both pin port 5005 with `suspend=y`. For a long-running service where you want to attach later without pausing startup:

```kotlin
tasks.named<JavaExec>("bootRun") {  // or "run"
    jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")
}
```

## Other Targets

Launch the JVM directly:

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar app.jar
```

Or set `JAVA_TOOL_OPTIONS` so any child JVM picks it up automatically:

```bash
export JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```
