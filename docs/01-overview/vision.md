# Vision

## The Problem

Remote engineering teams produce a constant stream of decisions, discussions, and documents. Architecture choices, sprint retrospectives, onboarding notes, incident write-ups — all of it ends up scattered across Notion pages, Slack threads, and Google Docs that nobody can find six weeks later.

The result is a specific, familiar frustration: a new team member asks "wait, why did we go with this approach?" and the answer requires someone with institutional memory to spend twenty minutes searching Slack. Or nobody remembers at all.

Generic AI tools make this worse, not better. You can paste a document into ChatGPT and get a summary — but the AI has no idea who your team is, what you decided last sprint, or how this document connects to the one written three months ago. Every conversation starts from zero.

## What CollabSpace Is

CollabSpace is a document collaboration platform for small remote engineering teams — typically five to fifteen people — built around a single insight: **your team's documents, taken together, are a knowledge base. They should be searchable like one.**

The platform has two parts that work together:

**A shared writing space.** Teams write and store documents — decisions, proposals, notes, runbooks — in one place. Editing is straightforward: one person edits at a time, with clear notifications when something changes while you're looking at it. You can see who's reading a document right now. Comments arrive in real time. It is deliberately simple; the complexity lives elsewhere.

**An AI assistant that has read everything.** In the background, CollabSpace reads and indexes every document in the workspace. The result is an assistant you can ask conversational questions: *"What did we decide about authentication last month?"* or *"Which documents mention the payment integration?"* It answers in plain language and always shows you which documents it drew from, so you can verify the source.

The experience that matters: getting an answer to "what did we decide about X?" in five seconds instead of twenty minutes of scrolling Slack.

## Who It's For

CollabSpace is designed for remote engineering teams that are small enough that everyone works across the same projects, but large enough that decisions stop being memorable three sprints later. The people who feel the pain most are:

- **Engineers** who join mid-project and need to understand why things are the way they are.
- **Tech leads** who are asked the same context questions repeatedly and want a place to point people.
- **Anyone** who has ever spent an afternoon reconstructing a decision that was definitely written down somewhere.

## What It Is Not

CollabSpace is not a replacement for Slack or a real-time whiteboard. It does not support simultaneous editing of the same document. It is not trying to be Notion or Confluence with more features. It is a focused tool: async-first document collaboration, with an AI layer that makes the accumulated knowledge of the workspace actually retrievable.

## The Core Bet

Most team knowledge tools are write-only in practice — things go in, but retrieval depends on people remembering where they put things. CollabSpace bets that a small team with a connected AI layer can turn its own documentation into something closer to a shared memory: one that you can ask questions to, and that answers with evidence.
