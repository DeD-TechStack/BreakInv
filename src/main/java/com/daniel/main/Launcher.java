package com.daniel.main;

/** Classpath/fat-JAR entry point; JavaFX is loaded by App, not the JVM module launcher. */
public final class Launcher {
    private Launcher() { }

    public static void main(String[] args) {
        App.main(args);
    }
}
