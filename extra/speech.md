# Nexus AI & Studio, and PromptLint, RCP Session Speech

> ~38 min spoken content | 19 slides
> Tone: Technical, confident, conversational. Speaking to devs, architects, and tech management.
> Audience has attended a Python LangChain/LangGraph session, so they know graph/agent basics. Skip foundational concepts.

---

## Section 1: Context (Slides 1 to 2, ~3 min)

**[Slide 1, Title]**

Good morning everyone. I'm Akshay Dipta, Senior Engineer in CLM Tech. Today I'm going to take you inside Nexus AI & Studio, and PromptLint, things I've built that are changing how we do AI engineering across dbCLM.

You've already seen agents and graphs in the Python session. Today is different. I'm going to show you a production-grade Java-native framework, how it works internally, how you define agents, how you compose pipelines, what the database schema looks like, and how you get observability for free. Then Nexus AI Studio, a visual developer experience for your workflows, think Swagger but for agents. After that, PromptLint, a static quality analyser for LLM prompts. And we'll wrap up with how to get started in your own project. We have about 40 minutes of content and then plenty of time for questions.

**[Slide 2, The Problem + Vision]**

So let's start with the problem. When we began exploring AI agents in dbCLM, building a single agent took 18 to 24 hours. Every team was reimplementing auth, retry logic, model configuration from scratch. There were no quality gates for prompts, so bad prompts reached production silently. No observability, no audit trail of what the AI decided and why. And critically, no control over the LLM integration itself. Teams were locked into whatever the library gave them by default.

So the vision was clear. What if building a new agent took one hour, not twenty-four? What if a twelve-agent orchestration pipeline with parallel execution and iterative loops was expressible in twenty lines of code? What if observability, authentication, and database-driven prompts were all built in by default, zero developer code? What if prompt quality was enforced in CI, the same way we enforce code quality, deterministic, no LLM calls, fail the build if a prompt drops below threshold? And what if you could see your agents execute live, click any node, inspect the full state at that checkpoint?

That's what I built. Let me show you what's inside.

---

## Section 2: What's Inside Nexus AI (Slide 3, ~3 min)

**[Slide 3, What's Inside Nexus AI]**

Before I show you the architecture, I want to give you a sense of what Nexus AI actually provides under the hood. There's a lot here, so let me walk through it column by column.

Starting with the core engine. We have custom chat models. We're not using the default models provided by LangChain4j. We wrote our own. Why? Because we needed more control. LangChain4j's default models have limited features for what we needed in production, things like custom response handling, specific token tracking, and tight integration with our authentication chain. So we built our own chat model implementations.

Auto checkpoints. The framework automatically persists graph state to the database at every node transition. If a workflow fails halfway through, you can see exactly where it stopped and what the state looked like. No developer code needed for this, it's built into the framework.

Custom serialization. We've overridden the default serialization and deserialization of the framework because we needed to support our own custom object types that flow through the graph state.

Now the listeners column. This is where observability lives. The AgentExecution listener logs every single LLM input and output to the database. Every prompt sent, every response received, recorded automatically. The Observability listener integrates with Spring Boot's correlation ID infrastructure, so your AI agent calls show up in the same Splunk traces and Langfuse dashboards as your existing services. End-to-end tracking across your whole application. And the Token Usage listener tracks exactly how many tokens each agent consumed, input, output, cached, and thinking tokens, all saved to the execution table.

On the integration side, bank authentication is built in. The custom WIF to Azure to JWT chain that every team needs, it's handled internally. Rate limiting. We've implemented custom per-model rate limiting so you don't exceed your quotas. And table access API, methods to query the framework's own tables, so you can build reporting or dashboards on top.

That's nine capabilities you get for free by adding one dependency.

---

## Section 3: Architecture + Code (Slides 4 to 7, ~8 min)

**[Slide 4, Two-Tier Architecture]**

Here's the high-level architecture. Nexus AI is the complete platform, and everything you see on this slide lives inside it.

There are two tiers. Tier 1 is the workflow orchestration layer, built on LangGraph4j, the Java port of LangGraph that you saw in the Python session. But we've added checkpoints, graph state management, and observability on top. Tier 1 defines the overall workflow as a directed graph. Nodes are processing steps. Some are standard Java logic, but one or more nodes are AI Nodes, and that's where Tier 2 comes in.

Tier 2 is the agent pipeline layer, built on LangChain4j. AgentFactory, AgentSpec, tools, everything needed to orchestrate multi-agent AI pipelines with parallel execution, sequential chaining, and iterative loops.

And spanning both tiers, the database for prompts and state, Gemini as our LLM, and Azure for authentication. These aren't external services you wire up yourself. Nexus AI connects to them automatically.

Now, why Java and not Python? When this initiative started, there was a proposal to build it in Python. I rejected that. Our ecosystem is Spring Boot. Our teams are Java engineers. Java-native means direct access to dependency injection, the bank's WIF authentication libraries, and our existing database infrastructure, all natively. No bridges, no adapters, no second deployment pipeline.

**[Slide 5, AgentSpec]**

Now let's go inside Tier 2. Every agent in Nexus AI is defined by an AgentSpec. This is purely declarative configuration.

The name maps directly to the prompt lookup in the database. All model parameters, system instructions, and temperature are loaded from the database at runtime. You change the prompt without changing code.

Inputs declares which keys this agent reads from the shared scope. The framework resolves the values automatically. OutputKey is where this agent writes its result. Downstream agents read from this key.

Tools are Java objects with @Tool-annotated methods. The LLM sees these as callable functions. And the listener is the AgentMonitor that tracks timing, inputs, outputs, and errors.

You describe what the agent needs. The framework handles how it runs.

**[Slide 6, Spring Boot Agent Configuration]**

This is how you configure agents in your Spring Boot application. On the left, a simple agent configuration. You build an AgentSpec, set the name, declare inputs and outputs, attach tools and the listener. It's a Spring bean, standard dependency injection.

On the right, a critic loop configuration. Two agents, a critic and a refiner, wired into a loop. The critic evaluates the output, the refiner improves it. The framework handles the iteration logic. You just define the agents and the quality threshold.

Both are just Spring @Bean methods. If you know Spring Boot, you already know how to configure Nexus AI agents.

**[Slide 7, Workflow Graph Definition]**

Here's what a workflow looks like in code. This is the real CSM extraction workflow.

We register each node, download documents, upload to cloud storage, CSM extraction, auto-answer trigger. Then we define the edges, start to download, download to upload, upload to extraction, extraction to persistence, persistence to auto-answer, auto-answer to end. Compile. That's it.

The interesting part is that CSM Extraction AI Node. It looks like just another node in the graph. But inside it, there's a full 12-agent Tier 2 pipeline. The graph doesn't need to know about that complexity. It just sees a node that takes input state and returns output state.

---

## Section 4: Database Schema (Slides 8 to 12, ~8 min)

**[Slide 8, Entity Relationship Diagram]**

Now let me show you the data side. This is the entity relationship diagram for the four tables that power the framework.

At the top left, AI_CHAT_PROMPT. This is where all agent configurations live. Prompts, model settings, temperature, response schemas, everything. At the top right, AI_CHAT_WORKFLOW_RUN. This is the parent metadata table for every workflow execution.

The workflow run table has a one-to-many relationship with two tables. NEXUS_AI_AGENT_EXECUTIONS, every individual agent call within that workflow. And NEXUS_AI_CHECKPOINT, the graph state snapshot at every node transition.

The prompt table also links to agent executions through the prompt version, so you always know which version of the prompt produced a given result.

Four tables. Config, metadata, execution trace, and state persistence. Let me walk through each one.

**[Slide 9, Prompt Table]**

This is the prompt table, AI_CHAT_PROMPT. Every agent's complete configuration lives here.

You can see the prompt code and version as the composite primary key. The system instruction, model name, temperature, top P, top K, max output tokens, thinking budget, response schema, it's all here.

This is one of my favourite design decisions. Prompts live in the database, not in code. You want to change how an agent behaves? Update this row. The next execution picks up the new configuration. No code change, no pull request, no deployment. Prompt engineers iterate independently from developers.

And here's the important part. Our framework loads and validates all prompt configurations at application start. If an input variable is referenced in the prompt template but not declared in the agent's inputs, the application throws an exception before it serves a single request. You find configuration errors at startup, not in production.

**[Slide 10, Workflow Run Table]**

This is the workflow run table, AI_CHAT_WORKFLOW_RUN. Every workflow execution creates a record here.

The RUN_ID is the key, and this is what links everything together. You can see the function code identifying which workflow ran, the status, running, finished, or failed. The KYC_ID tells you which client this ran for. Profile version, timestamps, who created it.

This is your compliance and audit trail. You can query any run, when it started, when it finished, what triggered it, what the outcome was. And the RUN_ID is the foreign key that connects to both the agent execution table and the checkpoints table. One RUN_ID, complete traceability of everything that happened.

**[Slide 11, Agent Execution Table]**

This is where it gets really interesting. NEXUS_AI_AGENT_EXECUTIONS, every single LLM call within a workflow run.

Look at the columns. Agent ID, agent name, the run ID linking back to the workflow, invocation order, status. Input data, the full prompt that was sent. Output data, the full response that came back. Error message if something failed. Start time, end time, duration in milliseconds.

And then the token metrics section. Input tokens, output tokens, total tokens, cached content tokens, thinking tokens, tool use prompt tokens, LLM call count, and a full token usage details JSON. You know exactly what every agent consumed.

The Nexus AI framework automatically saves every agent execution here. The developer doesn't write a single line of database code. You define your agents, run your pipeline, and every interaction with the LLM is recorded. When an agent produces unexpected output, you don't guess. You look up this table and see exactly what happened.

**[Slide 12, Checkpoints Table]**

The last table, NEXUS_AI_CHECKPOINT. This stores the graph state at every node transition.

Checkpoint ID, run ID linking back to the workflow, the node that just completed, the next node in the graph, and the full state data as a CLOB, the complete state snapshot at that point in the execution.

This is what enables resumability and debugging. If a workflow fails at node 4, you have the complete state from node 3. You can see exactly what data was flowing through the graph at that point. And because we store the next node ID, the framework knows exactly where to resume from.

Combined with the agent execution table, you have two complementary views. The checkpoint table shows you the graph-level state flow, and the execution table shows you the agent-level LLM interactions. Together, full observability from start to finish.

---

## Section 5: Nexus AI Studio (Slides 13 to 14, ~5 min)

**[Slide 13, Nexus AI Studio]**

You just saw the raw execution data, database tables, agent traces, token counts. That's powerful for auditing and debugging. But when you're developing a 12-agent pipeline with parallel waves and iterative loops, you need to see it. So I built Nexus AI Studio.

On the left, what Studio delivers today. Graph visualization. The framework reads your workflow graph definition and renders it as an interactive UI. You can run workflows directly from the UI, trigger executions and watch them in real time. As agents execute, nodes light up, complete, or fail. And you can click any node to inspect the full state snapshot at that checkpoint.

On the right, where we're taking it. The vision is Swagger for AI. If you've used Swagger for REST APIs, you know the value. It reads your API definitions and gives you a UI to explore and test them. That's what we want for agents. The framework will auto-discover every agent registered in your application context and build a UI for it. Run individual agents in isolation, or full workflows end to end. One UI for everything. Like Swagger gave REST APIs a face, Studio gives agents a face.

It's a React and TypeScript single-page application, packaged as a Spring Boot JAR. Add one Maven dependency, you get the full UI.

**[Slide 14, Nexus AI Studio, Live Demo]**

Let me show you what this looks like. This is Nexus AI Studio running against the real CSM workflow. Three panels. On the left, the thread list and input parameters. In the centre, the full graph topology, every node you saw in the code. On the right, the state inspector.

You can see every node has completed, green checkmarks. The parallel nodes activated simultaneously. And if I click a node, the state panel shows the full checkpoint data at that point, what went in, what came out.

This is the same execution data from the database tables, rendered as a developer experience.

---

## Section 6: PromptLint (Slides 15 to 17, ~6 min)

**[Slide 15, Prompts Are Code]**

Now let's switch to PromptLint. And I want to start with a fundamental question. What happens when a prompt goes bad?

Prompts are code. They define how LLM agents behave, what they extract, how they classify, what format they return. But unlike application code, prompts have no linting. No static analysis. No automated quality gates.

In practice, prompts silently degrade. Someone adds vague language. Someone removes the output schema. Someone introduces contradictory rules. The LLM starts hallucinating or returning malformed output. And nobody can pinpoint when the regression happened, because nobody was testing the prompt.

PromptLint changes that. It brings the discipline of static analysis to LLM prompts. Rule-based and deterministic, no LLM calls, no API keys, no latency. Runs in milliseconds. And it's fully deterministic. The same prompt always produces the same score.

**[Slide 16, 8 Quality Dimensions]**

PromptLint evaluates prompts across eight independent dimensions.

Clarity, does the prompt have a clear role and task definition? Specificity, are there numbered rules, concrete examples, quantified thresholds? Groundedness, does it instruct the LLM to ground answers in source material and avoid hallucination? Output Format, is the expected JSON schema documented with examples?

Conciseness, is there redundancy or repetition? Consistency, do template variables match declared inputs, are there contradictory instructions? Token Efficiency, is the prompt concise relative to its complexity? And Injection Risk, is there system/user boundary enforcement, input sanitisation?

Each dimension scores 0 to 1. The overall score is a weighted average, and the weights depend on the agent type. An extraction agent weights groundedness highest. A classification agent weights specificity. You can also define custom profiles with your own weights.

**[Slide 17, JUnit API + CI Integration]**

Here's how you use it. On the left, a real test from our codebase. It fetches the CSM_CLASSIFIER prompt from the database, wraps it in a PromptUnderTest with the agent's declared inputs, output key, agent type profile, and response schema. Then the analyser runs against it. The assertion at the bottom, passesThreshold 0.75 and hasNoCriticalIssues. If the prompt degrades below that threshold, the test fails. Same JUnit runner, same CI pipeline, same quality bar as your Java code.

On the right, the actual output. The quality report for a document-summarizer prompt using the extraction profile. Overall score 0.75, pass. You can see every dimension scored independently. Clarity and consistency at 1.0, groundedness at 0.92, but constraint coverage at 0.20, that's the weakest. Below the scores, specific warnings. No positive examples, no empty input handling, no defence against embedded prompts. And then actionable suggestions, add examples, fix JSON types, add null handling. This is what prints to your CI log. You don't guess what's wrong with your prompt, the report tells you exactly what to fix.

No extra service, no API key, no network call. Just a dependency on the classpath. Runs in milliseconds.

---

## Section 7: Getting Started + Close (Slides 18 to 19, ~2 min)

**[Slide 18, Getting Started]**

To get started, three Maven dependencies. nexus-ai gives you the full workflow orchestration and agent pipeline framework. nexus-ai-studio gives you the graph visualization UI. And clm-prompt-lint gives you the static prompt analyser.

On the right, your application YAML. You configure your WIF provider and keystore paths for authentication. Azure tenant and client IDs. Google Cloud project, location, and credentials for Gemini. Rate limiting settings. And Studio, just set enabled to true and define the path. That's it.

Three dependencies. A few properties. You have the whole framework, workflow orchestration, agent pipelines, enterprise authentication, observability, prompt quality gates, and a visual developer experience. All built in Java, running in production, available for your team to adopt. If you have questions or want help building your first workflow, reach out to me directly.

**[Slide 19, Thank You & Q&A]**

Thank you. Happy to take questions.
