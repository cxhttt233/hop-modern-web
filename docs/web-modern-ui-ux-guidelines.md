# Hop Modern Web UI / UX Guidelines

> Status: product UI/UX acceptance guidance for the native React editor.
>
> Architecture authority remains `docs/web-modern-architecture-plan.md`. This document does not redefine architecture, module boundaries, API ownership, or execution semantics.

## 1. Product goal

The editor should feel like one coherent daily-use product rather than a collection of technical demos.

A user should be able to understand the current object, current state, available action, and result of an action with as little explanatory text as possible.

The primary path is:

`open/new -> edit graph -> configure transform -> connect -> save/reopen`

Pipeline execution is a separate readiness track and must not block a complete non-execution editor experience.

## 2. Core principles

### 2.1 Intuition before explanation

- Prefer an interaction that is self-explanatory over adding help text.
- High-frequency actions should be directly reachable.
- Avoid unnecessary steps, page jumps, confirmation layers, and mode switches.
- If navigation or surrounding context already explains the page, do not repeat the same meaning with another large title or decorative subtitle.
- Reduce text without reducing comprehensibility.

### 2.2 Core object is the visual focus

- The pipeline, selected transform, current file, and editing state are more important than framework chrome.
- Do not let toolbars, cards, labels, section headings, branding, or status ornaments compete with the graph.
- Selected/active/dirty/error states should be clear, but normal state should remain visually quiet.

### 2.3 Build hierarchy with layout, not decoration

Use these first:

- proximity;
- grouping;
- alignment;
- spacing and whitespace;
- typography size/weight;
- contrast;
- stable placement.

Do not create hierarchy mainly by adding more borders, cards, background blocks, badges, captions, glow, or decorative text.

Gestalt rules should be visible in the layout: related controls are near each other, unrelated groups are separated, aligned items read as one system, and the most important object has the strongest contrast.

### 2.4 Keep the interface restrained

- Avoid atmosphere-only copy and labels with no information gain.
- Avoid redundant titles, descriptions, status slogans, and helper text.
- Avoid decorative animation. Motion should communicate a real state or transition.
- Normal state should be quiet; warnings/errors may increase visual priority.
- Avoid a patched-together or dashboard-card collage feeling.

### 2.5 Preserve familiar mental models where useful

- Reuse proven Hop editor mental models when they reduce learning cost.
- Do not mechanically copy RAP/SWT behavior when a native web interaction is clearer.
- Keep canvas interactions conventional: select, drag, connect, multi-step editing, delete, undo/redo, contextual actions.
- Low-frequency actions should prefer context menus or lightweight contextual surfaces instead of permanently occupying the main UI.

### 2.6 Consistency is part of usability

- New and Edit use the same information structure and control language.
- Similar operations use the same placement, wording, feedback, disabled states, and keyboard behavior.
- A transform configuration should be semantically grouped, not rendered as an unstructured wall of label/input pairs.
- Search, selection, dialogs/panels, save feedback, errors, and loading states should use one consistent interaction model.

## 3. Editor layout guidance

### Top area

Keep only editor-level actions and state:

- New / Open;
- Save;
- Undo / Redo;
- current file / pipeline name;
- dirty state;
- concise failure feedback when necessary.

Avoid a second layer of explanatory editor titles when the product/navigation context already makes the page clear.

### Left side: Transform Palette

- Search first.
- Transform groups/categories should support scanning, not create visual noise.
- Transform name is the primary label.
- Drag affordance should be obvious without instructional paragraphs.
- Icons may improve recognition, but text remains authoritative.
- Do not turn every transform into a large card.

### Center: Pipeline Canvas

- The graph is the dominant visual area.
- Keep maximum usable canvas space.
- Node selection, hover, handles, hops, move and delete must be obvious through interaction states rather than permanent decoration.
- Do not place nonessential panels over the canvas.
- Canvas operations must mutate the real authoritative graph, never a UI-only mock state.

### Configuration surface

- Double-click / Configure should open the real transform configuration surface.
- Use semantic groups and reasonable field density.
- Common scalar fields should feel native and lightweight.
- Complex fallback fields may remain generic temporarily, but the UI must clearly remain editable and understandable.
- Cancel/Save placement and behavior must be stable across transforms.

### Contextual / low-frequency operations

Prefer:

- context menu;
- compact popover;
- contextual toolbar near the selected object;

over permanently visible secondary controls.

## 4. Interaction rules

- Frequent actions should require the fewest reasonable steps.
- Dragging a transform into the canvas must create a real transform.
- Connecting nodes must create a real Hop.
- Delete / Undo / Redo / Save must operate on authoritative state.
- Double-clicking a transform must open the real config path.
- Saving must provide clear success/failure feedback without disruptive modal ceremony.
- Dirty state must be visible before destructive navigation or reopen.
- Keyboard Delete and standard editor shortcuts should be supported when safe and unambiguous.
- Errors should say what failed and what the user can do next; avoid technical noise unless it is actionable.

## 5. Visual hierarchy rules

- One primary focal area per screen.
- Use fewer, stronger visual levels instead of many similar levels.
- Related controls should share alignment and spacing.
- Primary and secondary actions must be visually distinguishable.
- Secondary metadata should recede.
- Avoid using color as the only state signal.
- Avoid excessive gray/cyan decoration or low-contrast chrome that weakens the canvas.
- Borders and shadows should clarify spatial grouping, not decorate every component.

## 6. Motion and feedback

Animation is allowed only when it explains:

- loading;
- dragging;
- connection creation;
- panel/dialog transition;
- successful state transition;
- warning/error attention where necessary.

Do not use continuous decorative motion.

Normal state should be calm. Failure and warning states may temporarily gain priority.

## 7. Productization acceptance for the non-execution editor

A front-end experience is considered usable only when a real user can complete this path without RAP `/ui`:

1. open a real `.hpl`;
2. identify the current pipeline/file and dirty state;
3. search/browse the Transform Palette;
4. drag multiple different transforms into the real graph;
5. select, move and delete nodes;
6. connect transforms;
7. undo/redo meaningful graph edits;
8. double-click/configure a transform through the real generic config path;
9. write configuration and see clear feedback;
10. save;
11. reopen/reload and recover nodes, hops and configuration;
12. understand failures without reading implementation logs.

Execution controls may remain unavailable or clearly marked as not ready; they must not make the editor itself feel incomplete.

## 8. Things to reject during review

Reject a UI change when it:

- adds explanatory text instead of fixing the interaction;
- introduces another redundant heading/card/container;
- makes a secondary object more visually prominent than the pipeline;
- increases steps for a frequent action;
- creates a one-off interaction inconsistent with the rest of the editor;
- uses decorative animation without state meaning;
- implements UI-only graph state instead of authoritative Hop state;
- copies RAP behavior only for visual similarity;
- looks polished in a screenshot but is slower or less intuitive to use;
- feels like several separately designed modules assembled together.

## 9. Review method

UI/UX work is not complete from code inspection alone.

For each meaningful UI change:

1. exercise the real interaction in the browser;
2. check hierarchy, proximity, grouping, alignment and contrast;
3. check whether any text/control can be removed without reducing understanding;
4. verify frequent actions remain direct;
5. verify authoritative server state matches the visible result;
6. compare New/Edit and similar flows for consistency;
7. reject temporary patchwork that would immediately need redesign.

