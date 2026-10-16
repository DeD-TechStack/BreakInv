package com.daniel.presentation.view.pages;

import com.daniel.presentation.view.util.Breakpoint;

/** A page that re-flows its containers when the usable content width crosses a breakpoint. */
public interface ResponsivePage extends Page {
    void onBreakpoint(Breakpoint bp);
}
