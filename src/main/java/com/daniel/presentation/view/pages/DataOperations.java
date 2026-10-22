package com.daniel.presentation.view.pages;

import java.util.function.Consumer;

/**
 * A page that can replace the local data (BRK-30 restore). The shell reloads the visible page after
 * a replacement and blocks navigation, other actions and closing the window while it runs.
 */
public interface DataOperations {

    /** Called on the FX thread after the data were replaced. */
    void setOnDataReplaced(Runnable onDataReplaced);

    /** Called on the FX thread with true when a replacement starts and false when it ends. */
    void setOnDataBusy(Consumer<Boolean> onDataBusy);
}
