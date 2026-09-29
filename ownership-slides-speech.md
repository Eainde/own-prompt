[SLIDE 7]

Thank you. I'd like to take a few minutes to talk about the work we've done on client ownership. [pause]

In the bank, as part of KYC, we have to know who really owns and controls each client. These are the beneficial owners. [pause] Today, analysts work this out by hand. They go through registry extracts, org charts and shareholder registers. They build the structure, layer by layer. Then they apply the rules, to decide who is a UBO and who is an IBO. [pause]

This creates three problems. [pause] It's slow. Mistakes creep in. And two analysts can look at the same structure, and reach different answers. [pause]

So what have we done about it? [pause] We've split the job in two, and given each part to the right tool. AI builds the ownership structure. A rules based calculator decides who the beneficial owners are. [pause]

On the left is the AI. It reads the documents, pulls out the ownership details, and builds the full structure. Every shareholder and every percentage is linked back to the document it came from. [pause] But the AI does not make the decision. [pause]

On the right is the Beneficial Ownership Calculator. This is where the decision is made. It applies our policy rules, the same way, every time. [pause] Same structure in, same answer out. And because it's one service for the whole Group, every business gets the same result, with a clear audit trail. [pause]

Two things don't change. First, our people stay in control. The AI supports their judgement. It doesn't replace it. [pause] Second, there's no new tool to learn. Analysts keep working in dbCLM, just as they do today. [pause]

So, AI takes away the manual work. The calculator gives us one consistent answer. And our people keep the final say. [pause]

[SLIDE 8]

This is the agent framework behind it. It runs in three steps, which we call waves. [pause]

Wave 0 collects the documents. There's no AI in this step. We pull every document we hold for the client from DocLib. So the AI always works from the full picture. [pause]

Wave 1 sorts the documents by type. The red line on the slide is a critic loop. A second agent checks the first agent's work, and sends it back if it's wrong. So we never rely on one answer alone. [pause] The green boxes are parts we reused from the CSMs framework. We didn't rebuild what already works. [pause]

Wave 2 is where the real value is. [pause] The ownership agent reads the documents, and builds the ownership chart. That chart goes to the Ownership Calculator in dbCLM. The calculator identifies the UBOs and IBOs, and shows the full chain of control. The result appears on the analyst's screen in dbCLM, ready for review. [pause]

Where are we today? [pause] All of these agents are live in production, with a small group of users testing them. We're using their feedback to decide if we need more agents. [pause]

To sum up. We've reused what already works. AI now does the heavy reading. One calculator makes the decision for the whole Group. And our people and our controls stay exactly where they are. [pause] That puts us in a strong position for AMLA. [pause]

Now, rather than just telling you how it works, let's see it in action. I'll hand over to Pankaj, who will take you through a live demo. [pause] Over to you, Pankaj.
