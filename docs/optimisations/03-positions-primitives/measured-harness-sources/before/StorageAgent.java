package org.pixelwar.pixelwar.domain;

import java.lang.instrument.Instrumentation;

/** Measures only the arrays storing colors; shared enum singleton objects are excluded. */
public final class StorageAgent {
    public static volatile Instrumentation instrumentation;
    public static void premain(String args, Instrumentation value) { instrumentation = value; }
}
