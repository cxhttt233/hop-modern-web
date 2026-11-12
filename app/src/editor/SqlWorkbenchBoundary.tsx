import { Component, type ReactNode } from "react";

interface BoundaryState {
  failed: boolean;
}

/**
 * An optional, lazily loaded code editor must never take the whole Hop
 * configuration form down when its JS chunk cannot load. The authoritative
 * native SQL field remains mounted above this enhancement.
 */
export class SqlWorkbenchBoundary extends Component<{ children: ReactNode }, BoundaryState> {
  state: BoundaryState = { failed: false };

  static getDerivedStateFromError(): BoundaryState {
    return { failed: true };
  }

  render() {
    if (this.state.failed) {
      return (
        <p className="config-sql-editor-fallback" role="alert">
          专业 SQL 编辑器未能加载，请继续使用上方 SQL 字段；已填写的草稿不会丢失。
        </p>
      );
    }
    return this.props.children;
  }
}
