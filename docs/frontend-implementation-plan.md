# Analysis
- Current Monaco integration registers Aster language and applies markers via the compiler hook, but the UI does not surface counts or a problems list (`src/components/policy/monaco-policy-editor.tsx`, `src/hooks/useAsterCompiler.ts`).
- The execute page already renders status, matched rules, and actions, but has no decision trace visualization (`src/app/[locale]/(dashboard)/policies/[id]/execute/execute-policy-content.tsx`).
- There is no visible CI workflow in repo; build verification for `@aster-cloud/aster-lang-ts` changes needs to be planned at the pipeline level (`package.json`).

# Architecture Decision
- **Diagnostics UX**: Keep `useAsterCompiler` as the single source of truth for diagnostics; expose counts and detail via a lightweight UI layer (badge + problems panel) in `MonacoPolicyEditor`.
- **Decision Trace UI**: Add a dedicated `DecisionTracePanel` that uses shadcn/ui `Accordion` for nested traces, with visual and semantic indicators for taken vs not-taken branches.
- **Build Verification**: Add a CI workflow that installs dependencies and runs `pnpm build` (and a minimal test suite) to validate dependency updates, especially `@aster-cloud/aster-lang-ts`.

# Implementation Plan
## Task 1 - P1.8 Parser Error Recovery (Monaco UX)
1. **Expose diagnostics in editor UI**
   - Update `MonacoPolicyEditor` to read diagnostics from `useAsterCompiler` and compute counts.
   - Add a small status strip below the editor for counts and a toggle to open a problems panel.
   - Proposed new component: `MonacoProblemsPanel`.
2. **Component architecture**
   - New components:
     - `MonacoDiagnosticsBadge` (optional inline component)
       - Props: `{ errorCount: number; warningCount: number; infoCount: number }`
     - `MonacoProblemsPanel`
       - Props:
         ```ts
         interface MonacoProblemsPanelProps {
           diagnostics: TypecheckDiagnostic[];
           onReveal: (line: number, col: number) => void;
           isOpen: boolean;
           onToggle: () => void;
         }
         ```
   - `MonacoPolicyEditor`:
     - `const { diagnostics } = useAsterCompiler(...)`
     - `const errorCount = diagnostics.filter((d) => d.severity === 'error').length`
3. **Visual design (Tailwind)**
   - Badge strip: `flex items-center gap-3 mt-2 text-xs text-gray-500`
   - Error badge: `inline-flex items-center rounded-full bg-red-100 text-red-700 px-2 py-0.5 font-medium`
   - Warning badge: `bg-amber-100 text-amber-700`
   - Info badge: `bg-blue-100 text-blue-700`
   - Problems panel container: `mt-3 rounded-lg border border-gray-200 bg-white max-h-48 overflow-y-auto`
   - Row: `flex items-start gap-2 px-3 py-2 hover:bg-gray-50 cursor-pointer`
4. **Pseudo-JSX**
   ```tsx
   <div className="relative">
     <Editor ... />
     <div className="flex items-center justify-between mt-2">
       <div className="flex items-center gap-2 text-xs text-gray-500">
         <MonacoDiagnosticsBadge errorCount={errorCount} warningCount={warningCount} infoCount={infoCount} />
       </div>
       <button className="text-xs text-indigo-600" onClick={toggleProblems}>
         {t('diagnostics.viewProblems')}
       </button>
     </div>
     <MonacoProblemsPanel ... />
   </div>
   ```
5. **Accessibility**
   - Badge strip: `role="status"` and `aria-live="polite"` for counts.
   - Problems list: `role="list"`; each item `role="listitem"` and `tabIndex=0`.
   - Keyboard: allow `Enter`/`Space` to reveal diagnostics.
6. **i18n keys**
   - `editor.diagnostics.errors`, `editor.diagnostics.warnings`, `editor.diagnostics.info`
   - `editor.diagnostics.viewProblems`, `editor.diagnostics.hideProblems`
   - `editor.diagnostics.empty`
7. **Test plan (Vitest + RTL)**
   - Render editor wrapper with diagnostics and verify badge counts.
   - Toggle problems panel and verify list items, keyboard activation, and `onReveal` calls.
   - Snapshot test for empty diagnostics state.

## Task 2 - P3.6 Enable Disabled Tests (Frontend Impact)
1. **No UI changes required.**
2. **Ensure frontend tests remain stable** after parser changes:
   - Add a smoke test that a policy with multiple parse errors still renders Monaco without crashing.

## Task 3 - P3.7 Decision Trace UI (Execute Page)
1. **Data model and props**
   - Extend `ExecutionResult`:
     ```ts
     interface DecisionTraceNode {
       id: string;
       label: string;
       kind: 'rule' | 'if' | 'match' | 'branch' | 'expr';
       outcome?: 'true' | 'false' | 'matched' | 'not_matched' | 'unknown';
       durationMs?: number;
       children?: DecisionTraceNode[];
       source?: { line?: number; col?: number };
     }
     ```
2. **Component architecture**
   - New components:
     - `DecisionTracePanel`
       - Props: `{ trace?: DecisionTraceNode[]; locale: string }`
     - `DecisionTraceTree`
       - Props: `{ nodes: DecisionTraceNode[] }`
     - `DecisionTraceNodeItem`
       - Props: `{ node: DecisionTraceNode; depth: number }`
   - Use shadcn/ui `Accordion` to handle nested children.
3. **Visual design (Tailwind)**
   - Panel container: `mt-4 rounded-lg border border-gray-200 bg-gray-50 p-4`
   - Header row: `flex items-center justify-between`
   - Node row: `flex items-center gap-2 text-sm text-gray-700`
   - Outcome badge:
     - Taken/Matched: `bg-green-100 text-green-700`
     - Not taken: `bg-gray-200 text-gray-600`
     - Unknown: `bg-amber-100 text-amber-700`
   - Indentation: `pl-4` per depth, plus a `border-l border-gray-200` for hierarchy.
4. **Pseudo-JSX**
   ```tsx
   <DecisionTracePanel trace={result?.decisionTrace} locale={locale} />
   // inside DecisionTracePanel
   <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
     <div className="flex items-center justify-between">
       <h4 className="text-sm font-semibold">{t('decisionTrace.title')}</h4>
     </div>
     {trace?.length ? (
       <Accordion type="multiple" className="mt-3">
         {trace.map((node) => (
           <DecisionTraceNodeItem key={node.id} node={node} depth={0} />
         ))}
       </Accordion>
     ) : (
       <p className="text-xs text-gray-500">{t('decisionTrace.empty')}</p>
     )}
   </div>
   ```
5. **Accessibility**
   - Accordion provides keyboard navigation; ensure `AccordionTrigger` has descriptive text.
   - Add `aria-label` to the panel: `aria-label={t('decisionTrace.ariaLabel')}`.
   - For status badges, include visually hidden text: `sr-only` span with outcome text.
6. **i18n keys**
   - `decisionTrace.title`, `decisionTrace.empty`, `decisionTrace.ariaLabel`
   - `decisionTrace.node.rule`, `decisionTrace.node.if`, `decisionTrace.node.match`
   - `decisionTrace.branch.taken`, `decisionTrace.branch.notTaken`, `decisionTrace.branch.unknown`
   - `decisionTrace.duration`
7. **Test plan (Vitest + RTL)**
   - Render panel with a nested trace and verify accordion expands/collapses.
   - Verify badges map to `outcome`.
   - Verify empty-state text when trace is missing.
   - Accessibility: `getByRole('button')` for AccordionTrigger and keyboard activation.

## Task 4 - Build Verification / CI Pipeline
1. **Add CI workflow** (GitHub Actions or equivalent):
   - Install dependencies: `pnpm install --frozen-lockfile`
   - Build: `pnpm build`
   - Optional: `pnpm test:run`
2. **Pipeline triggers**
   - `on: [push, pull_request]` with path filters:
     - `package.json`, `pnpm-lock.yaml`, `src/**`
3. **Performance**
   - Enable pnpm store cache to speed builds.
4. **Test plan**
   - CI job should fail if `@aster-cloud/aster-lang-ts` breaks builds or runtime bundling.
   - Add a minimal smoke test for `DecisionTracePanel` to avoid regressions.

# Considerations
- **Performance**: Parser recovery may increase diagnostics volume; consider capping the visible list (e.g., top 50) and letting Monaco manage the rest.
- **Maintainability**: Keep trace components small and composable; do not bake API details into UI.
- **Accessibility**: Avoid color-only indicators; pair badges with text and `sr-only` labels.
- **i18n**: Maintain all new labels under `policies.execute` or a dedicated `decisionTrace` namespace.
