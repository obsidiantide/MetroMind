import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Unmount whatever a test rendered so the next one starts from an empty document.
afterEach(() => cleanup());

// jsdom has no PointerEvent, so pointer events fired in tests would lose their coordinates; a MouseEvent carries them.
if (typeof window !== "undefined" && !("PointerEvent" in window)) {
  class PointerEvent extends MouseEvent {
    readonly pointerId: number;
    constructor(type: string, init: PointerEventInit = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 1;
    }
  }
  Object.assign(window, { PointerEvent });
}
