package com.daniel.presentation.view.pages;

import javafx.scene.Parent;

public interface Page {
    Parent view();
    default void onShow() {}
}
