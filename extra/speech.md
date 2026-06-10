# Nexus AI & Studio, and PromptLint, RCP Session Speech

> ~38 min spoken content | 19 slides
> Tone: Technical, confident, conversational. Speaking to devs, architects, and tech management.
> Audience has attended a Python LangChain/LangGraph session, so they know graph/agent basics. Skip foundational concepts.

---

## Section 1: Context (Slides 1 to 2, ~3 min)

**[Slide 1, Title]**

Good morning everyone. I'm Akshay Dipta, Senior Engineer in CLM Tech. Today I'm going to take you inside Nexus AI & Studio, and PromptLint, things I've built that are changing how we do AI engineering across dbCLM.

You've already seen agents and graphs in the Python session. Today I'll show you a production-grade Java-native framework, a visual developer experience, and a static quality analyser for prompts.

Now, why Java and not Python? When this initiative started, there was a proposal to build it in Python. I realised pretty quickly that wouldn't work. Our domain microservices, our infrastructure, our developers are all Java. The goal isn't to build agents as separate apps. It's to make our existing services agentic, plug AI directly into the processes already running in production. We don't want a parallel stack, just a natural extension of what we already have.

**[Slide 2, The Problem + Vision]**

So let's start with the problem. When we began exploring AI agents in dbCLM, building a single agent took 18 to 24 hours. Every team was reimplementing auth, retry logic, model configuration from scratch. There were no quality gates for prompts, so bad prompts reached production silently. There was no observability, no audit trail of what the AI decided and why. And critically, no control over the LLM integration itself. Teams were locked into whatever the library gave them by default.

So the vision was clear. What if building a new agent took one hour, not twenty-four? What if you could express a twelve-agent pipeline with parallel execution and loops in twenty lines of code? What if observability, authentication, and prompts were all built in by default, no developer code needed? What if prompt quality was enforced in CI the same way we enforce code quality, deterministic, no LLM calls, fail the build if a prompt isn't good enough? And what if you could see your agents execute live, click any node, and inspect the full state at that point?

That's what I built. Let me show you what's inside.

---

## Section 2: What's Inside Nexus AI (Slide 3, ~3 min)

**[Slide 3, What's Inside Nexus AI]**

Nexus AI is built on top of LangChain and LangGraph. Everything I'm about to show you is what I've added on top of those foundations. There's a lot here, so let me walk through it column by column.

Starting with the core engine. I wrote custom chat models from scratch. LangChain gives you default models, but they didn't give me enough control. I needed custom response handling, specific token tracking, tight integration with our auth chain. So I replaced them entirely.

Custom serialization. I've overridden the default serialization and deserialization of the framework because I needed to support custom object types.

Auto checkpoints. The framework automatically persists graph state to the database at every node transition. If a workflow fails halfway through, you can see exactly where it stopped and what the state looked like. You don't write any code for this, it's built into the framework.

Now the listeners column. This is where observability lives. The AgentExecution listener logs every LLM input and output to the database automatically. You don't add logging code, it just happens. The Observability listener ties into Spring Boot's correlation IDs, so your AI calls show up in the same Splunk traces and Langfuse dashboards as everything else. And the Token Usage listener tracks how many tokens each agent used, broken down by type, so you always know what you're spending.

On the integration side, bank authentication is built in. That whole WIF to Azure to JWT chain that every team has to deal with, the framework handles it for you. I've also added per-model rate limiting so you don't blow through your quotas, and a table access API so you can query the framework's own data for reporting or dashboards.

All of this comes from one Maven dependency.

---

## Section 3: Architecture + Code (Slides 4 to 7, ~8 min)

**[Slide 4, Two-Tier Architecture]**

Here's the high-level architecture. Everything you see on this slide lives inside Nexus AI.

There are two tiers. Tier 1 is the workflow orchestration layer, built on LangGraph. It defines your workflow as a directed graph where nodes are processing steps. I've added checkpoints, state management, and observability on top. Now, some nodes are just standard Java logic, but the interesting ones are AI Nodes. That's where Tier 2 comes in.

Tier 2 is the agent pipeline layer, built on LangChain. This is where you define your agents, wire up tools, and compose them into pipelines. You can run agents in parallel, chain them one after another, or loop them until the output is good enough.

Spanning both tiers you have the database for prompts and state, Gemini for the LLM, and Azure for authentication. You don't wire these up yourself. Nexus AI connects to them automatically.

**[Slide 5, AgentSpec]**

Now let's go inside Tier 2. Every agent in Nexus AI is defined by an AgentSpec. This is purely declarative configuration.

The name maps directly to the prompt lookup in the database. All model parameters, system instructions, and temperature are loaded from the database at runtime. You change the prompt without changing code.

Manual descripton

You describe what the agent needs. The framework handles how it runs.

**[Slide 6, Spring Boot Agent Configuration]**

Manual description for simple sequence

On the right, a critic loop configuration. Two agents, a critic and a refiner, wired into a loop. The critic evaluates the output, the refiner improves it. The framework handles the iteration logic. You just define the agents and the quality threshold.

Both are just Spring @Bean methods. If you know Spring Boot, you already know how to configure Nexus AI agents.

**[Slide 7, Workflow Graph Definition]**

Here's what a workflow looks like in code. This is the real CSM extraction workflow.

Its all manual explaination.

---

## Section 4: Database Schema (Slides 8 to 12, ~8 min)

**[Slide 8, Entity Relationship Diagram]**

Now let me show you the data side. This is the entity relationship diagram for the four tables that power the framework.

At the top left, AI_CHAT_PROMPT. This is where all agent configurations live. Prompts, model settings, temperature, response schemas, everything. At the top right, AI_CHAT_WORKFLOW_RUN. This is the parent metadata table for every workflow execution.

Each workflow run links to two child tables. NEXUS_AI_AGENT_EXECUTIONS, that's every individual agent call within that run. And NEXUS_AI_CHECKPOINT, the graph state at every node transition.

The prompt table also links to executions through the version number, so you can always trace a result back to the exact prompt that produced it.

Let me walk through each one.

**[Slide 9, Prompt Table]**

This is the prompt table, AI_CHAT_PROMPT. Every agent's complete configuration lives here.

The primary key is prompt code plus version. And then you've got everything the agent needs in one row, the system instruction, model name, temperature, token limits, thinking budget, response schema. It's all here.

This is one of my favourite design decisions. Prompts live in the database, not in code. So if you want to change how an agent behaves, you just update this row. Next execution picks up the new config automatically. You don't need a code change or a deployment. Your prompt engineers can iterate without waiting on developers.

And here's the important part. Our framework loads and validates all prompt configurations at application start. If an input variable is referenced in the prompt template but not declared in the agent's inputs, the application throws an exception before it serves a single request. You find configuration errors at startup, not in production.

**[Slide 10, Workflow Run Table]**

This is the workflow run table, AI_WORKFLOW_RUN. Every workflow execution creates a record here.

The CW_RUN_ID is the key, and this is what links everything together. You can see the function code identifying which workflow ran, the status, running, finished, or failed. There's a run comment for describing the execution. The party ID tells you which client this ran for. The profile version ID is the version of the KYC review for that client. Then who created it, when, and when it was last updated.

This is your audit trail. You can look up any run and see when it started, what triggered it, what happened. And at the bottom of the slide you can see the relationship, that CW_RUN_ID is the thread that connects everything. One workflow run links to many agent executions and many checkpoints. You get full traceability from client to every LLM call.

**[Slide 11, Agent Execution Table]**

This is where it gets really interesting. NEXUS_AI_AGENT_EXECUTIONS, every single LLM call within a workflow run.

You can see three groups on the slide. On the left, identity and execution, the agent name, run ID, invocation order, status, and which prompt version was used. In the middle, timing and data, when it started, when it finished, duration in milliseconds, the full input sent to the LLM, the full output that came back, and the error message if something failed. On the right, token metrics, how many tokens went in, how many came out, how many were cached, how many the model spent thinking.

And at the bottom, four things you get from this. Zero code, the framework persists every agent run automatically. Full I/O capture, input and output stored for debugging and replay. Cost visibility, token breakdown per agent including cached and thinking tokens. And compliance ready, a complete audit trail of who ran what, when, and how long it took.

You don't write any database code for this. When an agent produces something unexpected, you just look up this table and see exactly what happened.

**[Slide 12, Checkpoints Table]**

The last table, NEXUS_AI_CHECKPOINT. The framework saves the full graph state at every node transition.

You can see the columns on screen. Checkpoint ID, the run ID linking back to the workflow, the node that just completed, the next node to execute, and state data as a CLOB, that's the full graph state snapshot with all scope variables as JSON. Plus a timestamp for when it was saved.

And at the bottom, four things this gives you. Replay, you can re-run from any checkpoint without restarting the entire workflow. Debug, inspect the full state at every node, what went in, what came out. Resume, if a workflow fails, the next node ID tells the framework exactly where to pick back up. And audit, every state transition is recorded and tied back to the run ID.

So now you have two views. The checkpoint table tells you what was happening at the graph level. The execution table tells you what each agent said to the LLM and what came back. Between the two, you can trace anything.

---

## Section 5: Nexus AI Studio (Slides 13 to 14, ~5 min)

**[Slide 13, Nexus AI Studio]**

You just saw the raw execution data, database tables, agent traces, token counts. That's powerful for auditing and debugging. But when you're developing a 12-agent pipeline with parallel waves and iterative loops, you need to see it. So I built Nexus AI Studio.

On the left, what Studio delivers today. It gives you graph visualization. The framework reads your workflow graph definition and renders it as an interactive UI. You can run workflows directly from the UI, trigger executions and watch them in real time. As agents execute, nodes light up, complete, or fail. And you can click any node to inspect the full state snapshot at that checkpoint.

On the right, where I'm taking it. The vision is Swagger for AI. If you've used Swagger for REST APIs, you know the value. It reads your API definitions and gives you a UI to explore and test them. That's what I want for agents. The framework will auto-discover every agent registered in your application context and build a UI for it. You can run individual agents in isolation, or full workflows end to end. You get one UI for everything. Like Swagger gave REST APIs a face, Studio gives agents a face.

It's a React and TypeScript single-page application, packaged as a Spring Boot JAR. Add one Maven dependency, you get the full UI.

**[Slide 14, Nexus AI Studio, Live Demo]**

Let me show you what this looks like. This is Nexus AI Studio running against the real CSM workflow. There are three panels. On the left, the thread list and input parameters. In the centre, the full graph topology, every node you saw in the code. On the right, the state inspector.

You can see every node has completed, green checkmarks. The parallel nodes activated simultaneously. And if I click a node, the state panel shows the full checkpoint data at that point, what went in, what came out.

This is the same execution data from the database tables, rendered as a developer experience.

---

## Section 6: PromptLint (Slides 15 to 17, ~6 min)

**[Slide 15, Prompts Are Code]**

Now let's switch to PromptLint. Think about this, what happens when a prompt goes bad?

Prompts define how your agents behave. What they extract, how they classify, what format they return. They're code. But nobody treats them like code. There's no linting, no static analysis, no quality gates.

So what happens is prompts silently degrade. Vague language gets added, the output schema gets removed, contradictory rules creep in. The LLM starts hallucinating or returning malformed output. And nobody can pinpoint when it broke, because nobody was testing the prompt.

That's why I built PromptLint. It's static analysis for prompts. It doesn't call any LLM, doesn't need API keys, and it runs in milliseconds. The same prompt always gives the same score.

**[Slide 16, 8 Quality Dimensions]**

PromptLint evaluates prompts across eight independent dimensions.

On the top row, Clarity checks sentence structure, ambiguous pronouns, vague quantifiers. Specificity checks whether the prompt gives concrete rules instead of vague instructions. Constraint Coverage checks whether the prompt covers edge cases and failure modes. And Consistency looks for contradictory instructions within the same prompt.

On the bottom row, Groundedness checks for hallucination risk and claims without context anchoring. Output Format catches missing output schemas and ambiguous response formats. Token Efficiency flags unnecessary verbosity relative to instruction density. And Injection Risk checks for prompt injection surface area and missing delimiters.

Each dimension scores 0 to 1. The overall score is a weighted composite, and you can configure thresholds per dimension or on the aggregate. You can also define custom profiles with your own weights.

**[Slide 17, JUnit API + CI Integration]**

Here's how you use it. On the left, a real test from our codebase. You fetch the prompt from the database, wrap it with its inputs, output key, and agent type. Then the analyser scores it. The assertion at the bottom says pass if the score is above 0.75 and there are no critical issues. If the prompt degrades, the test fails. Same JUnit runner, same CI pipeline as your Java code.

On the right, the actual output. You can see the overall score, 0.75 pass, and every dimension scored independently. Clarity at 1.0, groundedness at 0.92, but constraint coverage at 0.20, that's the weak spot. Below that, specific warnings telling you what's wrong and suggestions for how to fix it. This is what prints to your CI log.

It's just a dependency on the classpath. You don't need an extra service or API key.

---

## Section 7: Getting Started + Close (Slides 18 to 19, ~2 min)

**[Slide 18, Getting Started]**

To get started, three Maven dependencies. nexus-ai gives you the full workflow orchestration and agent pipeline framework. nexus-ai-studio gives you the graph visualization UI. And clm-prompt-lint gives you the static prompt analyser.

On the right, your application YAML. You configure your WIF provider and keystore paths for authentication, your Azure tenant and client IDs, your Google Cloud project and credentials for Gemini, and your rate limiting settings. For Studio, just set enabled to true and define the path. That's it.

Three dependencies, a few properties, and you're running. Everything I showed you today, the orchestration, the agents, the auth, the observability, the prompt quality gates, the Studio UI, it's all there. Running in production right now. If you want help getting started or building your first workflow, come talk to me.

**[Slide 19, Thank You & Q&A]**

Thank you. Happy to take questions.
