/**
 * Host-neutral Studio composition. Pages supply localized labels, controlled raw values and
 * callbacks; this package owns control geometry, clipping and presentation state. A host adapts
 * font measurement, input, clipboard and painting without interpreting business identifiers.
 *
 * <p>
 * Build into a fresh or cleared {@link net.ptcrys.fpsmatch.editor.common.ui.component.StudioFrame},
 * then obtain its immutable snapshot. Validate/publish the snapshot's nodes to the host control
 * tree before replacing live visuals, callbacks or retained text. A rejected frame must leave the
 * previous live frame operational. Paint from that same published snapshot, using each node's
 * visible bounds for both clipping and pointer geometry.
 *
 * <pre>{@code
 * var frame = new StudioFrame();
 * var ui = new StudioComponents(frame, StudioMetrics.compact(), host::measureText);
 * try (var clip = frame.clipped(contentBounds)) {
 *     ui.tabs("document-tabs", tabBounds, 0, documentActions, true, tabOffset);
 *     ui.form("properties", fields, formBounds, 0, scrollOffset, host::scrollTo);
 * }
 * var next = frame.snapshot();
 * tree.publish(next.nodes()); // duplicate IDs reject before live callbacks change
 * host.publish(next);        // bind retained inputs, reconcile scroll capture, render
 * }</pre>
 *
 * <p>
 * Use stable, unique IDs from the page's semantic context, never from row positions or labels.
 * Recomposition with the same input ID preserves the host's edit history. Choose kind, variant,
 * icon and selection explicitly; renderer adapters must not infer styles from ID prefixes.
 * Layout helpers and compound controls retain caller clips by intersection. Forms share field
 * layout and blur action groups, including narrow vector reflow. Scroll regions identify their
 * actual published track; hosts route press/wheel through the control tree and reconcile capture
 * after successful publication so overlays and removed controls cannot retain stale callbacks.
 *
 * <p>
 * A dock keeps its title outside one StudioComponents.scrollContent body. Compose body headings,
 * fields, errors and footer actions inside that callback so short docks can reach every item.
 * Measure fields with formRowsHeight at the body's actual width (the viewport width minus its
 * single 8-pixel rail), then draw them with formRowsContent. These field helpers do not reserve
 * another rail or create scroll regions; drawing inherits the current clip and uses the supplied
 * bounds' origin and width. formRows remains the convenience wrapper for a standalone scrolling
 * form and should not be nested inside an already scrolling body.
 * Ancestor clips determine the body's effective vertical origin, height, scroll range and track;
 * its declared horizontal origin and measured width stay unchanged. Even a clipped short dock
 * can therefore reveal both the first and last content items. Empty effective apertures compose
 * nothing. Fixed frame.clipped scopes constrain the reveal viewport; contentClipped scopes move
 * with component content and clip the target's geometry instead. Both still intersect ordinary
 * node painting/hit clips, including horizontal overflow. Toolbars, commands and tabs use moving
 * local clips so an offscreen row can be revealed without making clipped-out controls focusable.
 *
 * <p>
 * scrollContent also declares keyboard reveal metadata for every interactive child. Keep the
 * body's ID stable within a document/page context and change it when that context changes. Keep
 * offscreen form/card nodes in the frame: their clip still prevents drawing, hits and actual
 * ControlTree focus. InputBridge.cycleFocus selects from top-layer semantic order, requests the
 * minimum scroll, then focuses the target only after a successful matching publication makes it
 * visible. Text state stays bound to its original ID; the source blur settles once and retains
 * same-row action-group suppression. Rejected publications retain the previous navigation data.
 * Moving through same-group action controls retains the source until focus leaves that group;
 * returning to the source restores normal blur ownership. Hosts call InputBridge.beforeAction
 * only immediately before an enabled pointer-release or keyboard action actually runs. That
 * retires the group's pending automatic commit so the action owns apply/discard semantics. A
 * pointer press or navigation cancellation alone does not activate or discard the group source.
 * Source removal, document-scope changes, modal coverage and window inactivity settle it once.
 * A keyboard-only anchor also reveals the same target after a source blur shrinks earlier layout;
 * this does not repeat the source blur. Hosts invalidate after Tab and call cancelNavigation on
 * pointer scrolling, resize, removal or explicit page switches; it clears both pending reveal and
 * the keyboard anchor. Explicit focus and window inactivity cancel internally. Target removal, a new modal,
 * or changed region identity/geometry also cancels delayed focus. Nested scopes use their nearest
 * body; revealing a chain of entirely hidden nested scroll containers and virtualized list/tree
 * traversal are outside this contract. Home/End and local text undo remain text-model operations.
 *
 * <p>
 * Docking uses one retained {@link net.ptcrys.fpsmatch.editor.common.ui.component.StudioDocking}
 * controller per host workspace context. Publish title controls with StudioComponents.dockHeader
 * and derive regions from the same header/body bounds used for painting. Reconcile stable panel
 * identities, control IDs and regions only after successful frame publication. Route a title
 * press through the published ControlTree, then feed drag/release to the controller. Apply its
 * optional Completion exactly once; a null target means ordinary title activation. The controller
 * carries no document data and never changes workspace state itself.
 *
 * <p>
 * Cancel capture on Escape, window inactivity, resize, host removal or modal transition.
 * Replacing the workspace or title identity set also cancels capture. Empty receiving regions are
 * explicit layout slots rendered with StudioComponents.dockTarget; do not infer targets over the
 * world viewport. Target borders and source markers are decorative and must not intercept input.
 * Workspace movement preserves panel UUID, binding, raw caches and other view state. Compact
 * hosts may expose the same move action through a menu and restore focus to that visible control.
 */
package net.ptcrys.fpsmatch.editor.common.ui.component;
