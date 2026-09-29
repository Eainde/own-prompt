[SLIDE 1]

Thank you. I'd like to take a few minutes to talk about the work we've done on client ownership. [pause]

In the bank, as part of KYC, we have to know who really owns and controls each client. These are the beneficial owners. [pause] Today, analysts work this out by hand. They go through registry extracts, org charts and shareholder registers. They build the structure, layer by layer. Then they apply the rules, to decide who is a UBO and who is an IBO. [pause]

This creates three problems. [pause] It's slow. Mistakes creep in. And two analysts can look at the same structure, and reach different answers. [pause]

So what have we built? [pause] We've built AI agents that read client and public documents, build the ownership structure and chart, identify the potential IBOs and UBOs, and flag any gaps or conflicts. [pause] All of this is pre-populated in dbCLM, so analysts don't start from a blank page. [pause] And humans remain in the loop. [pause]

The impact is big. [pause] Today, a maker spends on average 198 minutes on a case. With AI, that comes down to between 5 and 15 minutes. [pause]

Today, we cover four entity types. Private entities, parent exchanges, DB recognised regulated entities, and listed entities. Together, that's around 90 percent of our perimeter. [pause] The remaining 10 percent is spread across more than 1,800 entity types. These are the complex cases, and they are our future scope. [pause]

Now, where are we? [pause] Our first production release was in July, and since mid August, 70 users have been testing it with positive results. [pause] On the 5th of October, we expand to around 400 users, with go live planned for the end of October or November. [pause]

Our next step is to agree the sequencing with the Ownership team and other related programmes. And all of our timelines depend on the AI's accuracy and consistency, which is exactly what this testing is measuring. [pause]

One key step in this plan is the calculator integration. [pause]

[SLIDE 2]

Today, the AI also suggests the potential IBOs and UBOs. That is an interim step. [pause] In our target state, we've split the job in two, and given each part to the right tool. AI builds the ownership structure. A rules based calculator decides who the beneficial owners are. [pause]

On the left is the AI. It reads the documents, pulls out the ownership details, and builds the full structure. Every shareholder and every percentage is linked back to the document it came from. [pause] But the AI does not make the decision. [pause]

On the right is the Beneficial Ownership Calculator. This is where the decision is made. It applies our policy rules, the same way, every time. [pause] Same structure in, same answer out. And because it's one service for the whole Group, every business gets the same result, with a clear audit trail. [pause]

Two things don't change. First, our people stay in control. The AI supports their judgement. It doesn't replace it. [pause] Second, there's no new tool to learn. Analysts keep working in dbCLM, just as they do today. [pause]

So, AI takes away the manual work. The calculator gives us one consistent answer. And our people keep the final say. [pause]

[SLIDE 3]

This is the agent framework behind it. It runs in three steps, which we call waves. [pause]

Wave 0 collects the documents. There's no AI in this step. We pull every document we hold for the client from DocLib. So the AI always works from the full picture. [pause]

Wave 1 sorts the documents by type. The red line on the slide is a critic loop. A second agent checks the first agent's work, and sends it back if it's wrong. So we never rely on one answer alone. [pause] The green boxes are parts we reused from the CSMs framework. We didn't rebuild what already works. [pause]

Wave 2 is where the real value is. [pause] The ownership agent reads the documents, and builds the ownership chart. That chart goes to the Ownership Calculator in dbCLM. The calculator identifies the UBOs and IBOs, and shows the full chain of control. The result appears on the analyst's screen in dbCLM, ready for review. [pause]

To sum up. We've cut the time per case from over three hours to minutes. We've reused what already works. AI now does the heavy reading. One calculator makes the decision for the whole Group. And our people and our controls stay exactly where they are. [pause] That puts us in a strong position for AMLA. [pause]

Now, rather than just telling you how it works, let's see it in action. I'll hand over to Pankaj, who will take you through a live demo. [pause] Over to you, Pankaj.
