# Primer

A tiny **meta-agent**: load every Java agent you drop into a folder with a single `-javaagent` flag.

The JVM attaches Java agents only via `-javaagent`, and each flag points at exactly one jar — there's no built-in way to load several agents, let alone discover them from a directory. Primer is that missing piece. It's the one agent you put on the command line; at startup it finds every agent jar in an `agents/` folder and loads them all, each with its own config.

Primer is deliberately minimal and not tied to any framework or application — it loads plain `java.lang.instrument` agents and gets out of the way. It works with any Java program.

## Usage

1. Build `primer.jar` (see below).
2. Add **one** flag to your Java launch command, before `-jar` (or your main class):
   ```
   java -javaagent:primer.jar -jar your-app.jar
   ```
   Point it at a different folder with `-javaagent:primer.jar=path/to/agents`. The default is `agents`, resolved against the working directory.
3. Drop agent jars into `./agents/`. They're loaded automatically on the next start — no further command-line changes.

## Agent contract

Any standard Java agent works — Primer just has to find it and call it:

- **Required:** a `Premain-Class` manifest attribute pointing at a class with
  `public static void premain(String args, Instrumentation inst)` (the single-arg
  `premain(String)` form is also accepted). Jars without `Premain-Class` are skipped.
- **Optional:** a `Primer-Default-Config` manifest attribute naming a resource bundled
  inside the jar. If that agent's `.conf` doesn't exist yet, Primer copies the resource
  out as the default config.
- **Config format:** simple `key=value` lines (`#` or `;` begin comments). Primer reads
  them into a `key=value,key=value` string and passes it as the agent's `premain` args,
  so agents that already parse inline args need no changes.

Agents are appended to the system class loader search via
`Instrumentation.appendToSystemClassLoaderSearch` and all share the one `Instrumentation`.
A misbehaving agent is caught and logged — it never aborts the host application's startup
or blocks the other agents.

## Build (ephemeral — nothing installed on your machine)

```powershell
.\build.ps1            # compile -> primer.jar  (downloads a JDK into .build\ if needed)
.\build.ps1 -Purge     # delete the local .build\ folder
```

No Gradle, no dependencies — Primer is plain Java. It compiles to Java 21 bytecode and runs on any Java 21+ JVM.

## Example

```
java -javaagent:primer.jar -jar your-app.jar
```
```
[Primer] Looking for agents in: /path/to/agents
[Primer] Generated default config: MyAgent.conf
[Primer] Loaded agent 'MyAgent' (com.example.MyAgent) with config [option=value]
[Primer] Done - loaded 1 of 1 agent jar(s).
```

## How it works

`premain` runs before the host application's `main`. Primer scans the agents directory, and for each jar it: reads the `Premain-Class`, generates the config from a bundled default if one is missing, appends the jar to the system class loader search, reads the `.conf`, and invokes the agent's `premain` with the shared `Instrumentation`. Because this all happens before the application's own classes load, agents that register a `ClassFileTransformer` can transform everything that follows.
